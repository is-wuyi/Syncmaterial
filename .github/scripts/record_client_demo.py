#!/usr/bin/env python3
"""Record a client GameTest run as demo media.

The script owns the CI demo pipeline:

1. starts an Xvfb display (or reuses ``DISPLAY`` when Xvfb is unavailable),
2. runs ``./gradlew`` with that display in the environment,
3. tails ``<run-dir>/logs/latest.log`` for one scenario or the complete client suite,
4. captures the Minecraft window with ``ffmpeg`` x11grab; suite mode keeps recording through Gradle exit,
5. remuxes the recording and produces ``raw.mp4``, ``demo.mp4``, ``preview.gif``,
   ``poster.png``, ``events.tsv`` and ``manifest.json``.

The Gradle exit code is authoritative: media or ffmpeg problems only emit
warnings and never turn a passing run red. ``SYNCMATERIAL_DEMO_STEP <stepId>
<epochMillis>`` lines are consumed separately from the existing START/PASS/FAIL
report tags; step titles come from the scenario map in this file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve()
REPO_ROOT = SCRIPT_PATH.parents[2]

SCHEMA_VERSION = 1
DEFAULT_TIMEOUT_SECONDS = 1800.0
POLL_INTERVAL_SECONDS = 0.25
CAPTURE_STOP_TIMEOUT_SECONDS = 20.0
MEDIA_TIMEOUT_SECONDS = 300.0
MAX_CAPTURE_SECONDS = 900
CAPTURE_FPS = 30

GIF_MIN_SECONDS = 8.0
GIF_MAX_SECONDS = 20.0
GIF_MAX_BYTES = 5 * 1024 * 1024
GIF_WIDTH = 800
GIF_FPS = 10
DEMO_TIME_SCALE = 1.0
GIF_PLAYBACK_SCALE = 1.0
GIF_TIME_SCALE = GIF_PLAYBACK_SCALE / DEMO_TIME_SCALE

DEFAULT_SCENARIO = "warehouse-ref-popup"
FONT_FAMILY = "Noto Sans CJK SC"
FONT_CANDIDATES = (
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
    "/usr/share/fonts/truetype/noto/NotoSansCJKsc-Regular.otf",
)

# Adding a scenario only needs a target test class, a default title and the
# step-id -> human title map. Unknown step ids fall back to the id itself.
SCENARIOS = {
    DEFAULT_SCENARIO: {
        "target": "net.syncmaterial.syncmaterial.gametest.client."
                  "WarehouseRefPopupClientGameTest",
        "title": "仓库引用弹窗闭环演示",
        "explanation": "在编辑器中选择仓库，保存或移除项目引用",
        "steps": {
            "editor-open": "打开备货区编辑器",
            "open-editor": "打开备货区编辑器",
            "popup-open": "打开仓库引用弹窗",
            "open-popup": "打开仓库引用弹窗",
            "popup-select": "选择仓库并保存项目关联",
            "select-warehouse": "选择仓库并保存项目关联",
            "reference-added": "仓库关联已保存并经服务端校验",
            "reference-removed": "项目仓库引用已移除",
            "ref-remove": "移除项目仓库引用",
            "remove-reference": "移除项目仓库引用",
            "remove-ref": "移除项目仓库引用",
            "manager-open": "打开仓库管理器",
            "open-manager": "打开仓库管理器",
            "manager-close": "关闭仓库管理器",
            "close-manager": "关闭仓库管理器",
            "demo-complete": "仓库引用弹窗闭环完成",
        },
    },
    "legacy-pickup-ref-refresh": {
        "target": "net.syncmaterial.syncmaterial.gametest.client."
                  "LegacyPickupRefRefreshClientGameTest",
        "title": "旧客户端取货引用即时刷新",
        "explanation": "旧客户端无需重开取货，仓库引用增删后立即同步高亮",
        "steps": {
            "pickup-baseline": "旧客户端取货基线：两个项目的容器已高亮",
            "add-shared-reference": "项目A新增共享仓库引用",
            "shared-reference-added": "新增引用已生效，容器无需重开即可高亮",
            "remove-project-a-reference": "项目A移除共享引用，项目B继续保留",
            "shared-reference-preserved": "跨项目共享仓库与容器继续保留",
            "remove-last-shared-reference": "项目B移除最后一个共享引用",
            "shared-reference-removed": "共享引用消失，只保留项目A的容器",
            "open-project-a": "打开项目A材料清单并核对线框",
            "project-a-wireframe": "项目A线框与取货容器一致",
            "remove-final-reference": "移除项目A最后一个仓库引用",
            "pickup-cleared": "空快照已到达，旧高亮全部清除",
            "demo-complete": "断线重连后旧订阅与高亮均未复活",
        },
    },
}

BACKEND_TASKS = {
    "legacy": "runClientGameTest",
    "foundation": "runFoundationClientGameTest",
    "optional-legacy": "runOptionalClientGameTest",
    "optional-foundation": "runOptionalFoundationClientGameTest",
}

SUITE_TEST_TITLES = {
    "SmokeTestClientGameTest": "客户端启动与基础连接",
    "InventorySyncClientGameTest": "背包库存同步",
    "DisconnectCleanupClientGameTest": "断线后的客户端状态清理",
    "HudToggleDataFlowClientGameTest": "HUD开关与后台数据流",
    "StagingAreaSelectionClientGameTest": "准星选区操作",
    "PickupHighlightClientGameTest": "取货高亮显示",
    "WarehouseCrudClientGameTest": "仓库创建、编辑、引用与删除",
    "OwnerManagementClientGameTest": "负责人与副负责人管理",
    "MultiplayerClaimBroadcastClientGameTest": "多人认领状态广播",
    "MaterialListOpenClientGameTest": "材料清单打开与数据加载",
    "BatchAssignKickClientGameTest": "批量分配与按材料踢人",
    "RefreshPickupClientGameTest": "手动刷新与取货开关",
    "LeaveAndUnsubscribeClientGameTest": "退出认领与取消订阅",
    "WarehousePickupIndicatorClientGameTest": "仓库取货指示器",
    "WarehouseContainerPushClientGameTest": "容器变化主动推送",
    "MaterialListSortClientGameTest": "材料清单排序与筛选",
    "ConfigPersistenceClientGameTest": "配置保存与重载",
    "ConcurrencyClaimsClientGameTest": "多人并发认领",
    "OwnerActionConflictClientGameTest": "负责人操作冲突处理",
    "StressContainerPushClientGameTest": "大量容器推送压力测试",
    "StagingAreaScanPerfClientGameTest": "备货区扫描性能",
    "StagingAreaSnapshotClientGameTest": "备货区库存快照",
    "StagingAreaEditorClientGameTest": "备货区编辑器操作",
    "LegacyServerReopenClientGameTest": "旧服务端关闭与重开",
    "ProductionUiClientGameTest": "生产界面与多语言布局",
    "ClaimIntentClosedLoopClientGameTest": "认领意图完整闭环",
    "ActiveProjectBackgroundUpdateClientGameTest": "后台项目数据持续更新",
    "CarriedItemHudHighlightClientGameTest": "手持物品HUD与高亮",
    "WarehouseRefPopupClientGameTest": "仓库引用弹窗操作",
    "ClientSessionLifecycleClientGameTest": "客户端会话生命周期",
    "StagingAreaDimensionChangeClientGameTest": "换维度后的区域状态",
    "LegacyPickupRefRefreshClientGameTest": "旧客户端引用变化即时刷新",
}

SUITE_METADATA = {
    "legacy": "src/gametest/resources/fabric.mod.json",
    "foundation": "src/foundationtest/resources/fabric.mod.json",
    "optional-legacy": "src/optionaltest/resources/fabric.mod.json",
    "optional-foundation": "src/optionaltest/resources/fabric.mod.json",
}

REPORT_RE = re.compile(r"SYNCMATERIAL_CLIENT_TEST_(START|PASS|FAIL)\s+(\S+)\s+(\S+)")
STEP_RE = re.compile(r"SYNCMATERIAL_DEMO_STEP\s+(\S+)\s+(\d+)")


class DemoError(RuntimeError):
    """Fatal setup problem that prevents Gradle from running."""


@dataclass(frozen=True)
class Scenario:
    key: str
    target_class: str
    title: str
    step_titles: dict
    explanation: str = ""


@dataclass
class StepMark:
    index: int
    step_id: str
    title: str
    epoch_ms: int
    video_seconds: float


@dataclass
class DemoEvent:
    kind: str
    epoch_ms: int
    video_seconds: float
    step_id: str = ""
    title: str = ""


@dataclass
class DemoTimeline:
    """Pure log state machine for one target GameTest class."""

    target_class: str
    step_titles: dict
    start_epoch_ms: int | None = None
    result: str | None = None
    result_epoch_ms: int | None = None
    steps: list = field(default_factory=list)
    events: list = field(default_factory=list)

    def matches_target(self, class_name):
        if class_name == self.target_class:
            return True
        return class_name.rsplit(".", 1)[-1] == self.target_class.rsplit(".", 1)[-1]

    def step_title(self, step_id):
        return self.step_titles.get(step_id, step_id)

    def consume(self, line, detected_epoch_ms):
        """Consume one log line and return ``start``/``step``/``pass``/``fail``/None."""
        step_match = STEP_RE.search(line)
        if step_match:
            if self.start_epoch_ms is None or self.result is not None:
                return None
            step_id = step_match.group(1)
            epoch_ms = int(step_match.group(2))
            video_seconds = max(0.0, (epoch_ms - self.start_epoch_ms) / 1000.0)
            step = StepMark(len(self.steps) + 1, step_id, self.step_title(step_id),
                            epoch_ms, video_seconds)
            self.steps.append(step)
            self.events.append(DemoEvent("STEP", epoch_ms, video_seconds,
                                         step_id=step.step_id, title=step.title))
            return "step"

        report = REPORT_RE.search(line)
        if not report:
            return None
        status, _mod, class_name = report.groups()
        if not self.matches_target(class_name):
            return None
        if status == "START":
            if self.start_epoch_ms is not None:
                return None
            self.start_epoch_ms = detected_epoch_ms
            self.events.append(DemoEvent("START", detected_epoch_ms, 0.0))
            return "start"
        if self.start_epoch_ms is None or self.result is not None:
            return None
        self.result = status
        self.result_epoch_ms = detected_epoch_ms
        self.events.append(DemoEvent(status, detected_epoch_ms,
                                     max(0.0, (detected_epoch_ms - self.start_epoch_ms) / 1000.0)))
        return "pass" if status == "PASS" else "fail"


class SuiteTimeline:
    """Tracks every client test while keeping the whole suite on one timeline."""

    def __init__(self, test_classes, test_titles):
        self.test_classes = list(test_classes)
        self.test_titles = dict(test_titles)
        self.expected = len(self.test_classes)
        self.start_epoch_ms = None
        self.result = None
        self.result_epoch_ms = None
        self.steps = []
        self.events = []
        self.statuses = {}
        self.completed_statuses = []

    def title_for(self, class_name):
        short = class_name.rsplit(".", 1)[-1]
        return self.test_titles.get(class_name, self.test_titles.get(short, short))

    def consume(self, line, detected_epoch_ms):
        report = REPORT_RE.search(line)
        if not report:
            return None
        status, _mod, class_name = report.groups()
        if class_name not in self.test_classes:
            return None
        seconds = (0.0 if self.start_epoch_ms is None
                   else max(0.0, (detected_epoch_ms - self.start_epoch_ms) / 1000.0))
        if status == "START":
            step = StepMark(len(self.steps) + 1, class_name, self.title_for(class_name),
                            detected_epoch_ms, seconds)
            self.steps.append(step)
            self.events.append(DemoEvent("START", detected_epoch_ms, seconds,
                                         step_id=class_name, title=step.title))
            if self.start_epoch_ms is None:
                self.start_epoch_ms = detected_epoch_ms
                return "start"
            return "test-start"
        self.statuses[class_name] = status
        self.completed_statuses.append(status)
        self.events.append(DemoEvent(status, detected_epoch_ms, seconds,
                                     step_id=class_name, title=self.title_for(class_name)))
        return "test-result"

    def finish(self, exit_code, detected_epoch_ms):
        self.result_epoch_ms = detected_epoch_ms
        passed = self.completed_statuses.count("PASS")
        failed = self.completed_statuses.count("FAIL")
        if exit_code == 124:
            self.result = "TIMEOUT"
        elif exit_code == 0 and failed == 0 and passed == self.expected:
            self.result = "PASS"
        else:
            self.result = "FAIL"

    def status_for(self, step):
        return self.statuses.get(step.step_id, "RUNNING")

    def progress_before(self, index):
        previous = self.completed_statuses[:max(0, index - 1)]
        return previous.count("PASS"), previous.count("FAIL")

    def stats(self):
        return {
            "expected": self.expected,
            "started": len(self.steps),
            "completed": len(self.completed_statuses),
            "passed": self.completed_statuses.count("PASS"),
            "failed": self.completed_statuses.count("FAIL"),
        }


def load_suite_tests(repo_root, backend):
    relative = SUITE_METADATA.get(backend)
    if relative is None:
        raise DemoError(f"完整客户端录制不支持 backend {backend!r}")
    path = Path(repo_root) / relative
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
        entries = metadata["entrypoints"]["fabric-client-gametest"]
        classes = [entry["value"] for entry in entries]
    except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise DemoError(f"无法读取客户端测试清单 {relative}: {error}") from error
    if not classes or len(classes) != len(set(classes)):
        raise DemoError(f"客户端测试清单为空或含重复入口: {relative}")
    titles = {}
    for class_name in classes:
        short = class_name.rsplit(".", 1)[-1]
        titles[class_name] = SUITE_TEST_TITLES.get(short, short)
    return classes, titles


class LogTail:
    """Incremental UTF-8 reader that tolerates truncation and rotation."""

    def __init__(self, path):
        self.path = Path(path)
        self.offset = 0
        self.inode = None
        self.pending = b""

    def skip_existing(self):
        """Ignore a stale log left by an earlier run of the same directory."""
        try:
            stat = self.path.stat()
        except OSError:
            return
        self.offset = stat.st_size
        self.inode = stat.st_ino

    def read(self, final=False):
        try:
            stat = self.path.stat()
        except OSError:
            return []
        if self.inode is not None and stat.st_ino != self.inode:
            self.offset = 0
            self.pending = b""
        self.inode = stat.st_ino
        if stat.st_size < self.offset:
            self.offset = 0
            self.pending = b""
        if stat.st_size > self.offset:
            try:
                with self.path.open("rb") as stream:
                    stream.seek(self.offset)
                    data = stream.read()
                    self.offset = stream.tell()
            except OSError:
                return []
            data = self.pending + data
        elif final and self.pending:
            data = self.pending
        else:
            return []
        if final:
            self.pending = b""
            return [line.decode("utf-8", "replace").rstrip("\r")
                    for line in data.split(b"\n") if line]
        parts = data.split(b"\n")
        self.pending = parts.pop()
        return [line.decode("utf-8", "replace").rstrip("\r") for line in parts if line]


@dataclass
class Display:
    name: str
    proc: object = None
    width: int = 1920
    height: int = 1080
    reused: bool = False

    def stop(self):
        if self.proc is not None:
            # Xvfb shares this script's process group; never signal that whole group.
            _terminate_process(self.proc, group=False)


# ---------------------------------------------------------------------------
# Process helpers
# ---------------------------------------------------------------------------

def _signal_process(proc, sig, group=True):
    """Signal a process, optionally its session group, tolerating races."""
    pid = getattr(proc, "pid", None)
    if group and isinstance(pid, int) and pid > 0:
        try:
            os.killpg(os.getpgid(pid), sig)
            return
        except OSError:
            pass
    try:
        if sig == signal.SIGTERM:
            proc.terminate()
        elif sig == signal.SIGKILL:
            proc.kill()
        else:
            proc.send_signal(sig)
    except OSError:
        pass


def _terminate_process(proc, grace=10.0, group=False):
    if proc is None:
        return
    try:
        if proc.poll() is not None:
            return
    except OSError:
        return
    _signal_process(proc, signal.SIGTERM, group=group)
    try:
        proc.wait(timeout=grace)
        return
    except subprocess.TimeoutExpired:
        pass
    _signal_process(proc, signal.SIGKILL, group=group)
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def start_virtual_display(*, which, spawn, clock, sleep, environ):
    """Start Xvfb and return its display, or reuse an existing DISPLAY."""
    xvfb = which("Xvfb")
    if xvfb:
        for number in range(99, 0, -1):
            socket = Path(f"/tmp/.X11-unix/X{number}")
            if socket.exists():
                continue
            try:
                proc = spawn([xvfb, f":{number}", "-ac", "-screen", "0", "1920x1080x24",
                              "-nolisten", "tcp"],
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL)
            except OSError as error:
                raise DemoError(f"无法启动 Xvfb: {error}") from error
            deadline = clock() + 10.0
            while clock() < deadline:
                if socket.exists():
                    return Display(f":{number}", proc)
                try:
                    exited = proc.poll() is not None
                except OSError:
                    exited = True
                if exited:
                    _terminate_process(proc)
                    break
                sleep(0.1)
            else:
                _terminate_process(proc)
                raise DemoError(f"Xvfb :{number} 启动超时")
        raise DemoError("无法启动 Xvfb（没有可用 display 编号）")
    existing = environ.get("DISPLAY")
    if existing:
        return Display(existing, None, reused=True)
    raise DemoError("找不到 Xvfb，且未设置 DISPLAY，无法启动 Gradle")


# ---------------------------------------------------------------------------
# ffmpeg command builders (pure, unit-testable)
# ---------------------------------------------------------------------------

def build_capture_command(ffmpeg, display_name, geometry, display_size, output, max_seconds):
    if geometry is None:
        size = f"{display_size[0]}x{display_size[1]}"
        source = display_name
    else:
        x, y, width, height = geometry
        size = f"{width}x{height}"
        source = f"{display_name}+{x},{y}"
    return [
        ffmpeg, "-y", "-nostdin", "-loglevel", "warning",
        "-f", "x11grab", "-thread_queue_size", "512",
        "-framerate", str(CAPTURE_FPS), "-video_size", size, "-i", source,
        "-t", str(max(1, int(math.ceil(max_seconds)))),
        "-an", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
        "-pix_fmt", "yuv420p", "-f", "matroska", str(output),
    ]


def build_remux_command(ffmpeg, source, output):
    return [ffmpeg, "-y", "-nostdin", "-loglevel", "error", "-i", str(source),
            "-c", "copy", "-movflags", "+faststart", str(output)]


def build_copy_command(ffmpeg, source, output):
    return [ffmpeg, "-y", "-nostdin", "-loglevel", "error", "-i", str(source),
            "-c", "copy", "-movflags", "+faststart", str(output)]


def build_overlay_command(ffmpeg, source, output, filters):
    return [ffmpeg, "-y", "-nostdin", "-loglevel", "error", "-i", str(source),
            "-vf", ",".join(filters),
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", str(output)]


def build_poster_command(ffmpeg, source, output, at_seconds):
    return [ffmpeg, "-y", "-nostdin", "-loglevel", "error",
            "-ss", f"{max(0.0, at_seconds):.3f}", "-i", str(source),
            "-frames:v", "1", "-vf", "scale=1280:-2:flags=lanczos", str(output)]


def build_gif_commands(ffmpeg, source, palette, gif, start, duration,
                       width=GIF_WIDTH, fps=GIF_FPS, colors=256, pts_scale=GIF_TIME_SCALE):
    scale = f"scale={width}:-2:flags=lanczos"
    speed = f"setpts={pts_scale:.6f}*PTS"
    palette_filter = f"{speed},fps={fps},{scale},palettegen=max_colors={colors}:stats_mode=diff"
    gif_filter = (f"{speed},fps={fps},{scale}[x];"
                  f"[x][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle")
    common = ["-y", "-nostdin", "-loglevel", "error",
              "-ss", f"{max(0.0, start):.3f}", "-t", f"{duration:.3f}",
              "-i", str(source)]
    palette_cmd = [ffmpeg, *common, "-vf", palette_filter, "-frames:v", "1", str(palette)]
    gif_cmd = [ffmpeg, *common, "-i", str(palette), "-lavfi", gif_filter,
               "-loop", "0", str(gif)]
    return palette_cmd, gif_cmd


def preview_clip(duration, pts_scale=GIF_TIME_SCALE):
    if duration <= 0:
        return GIF_MIN_SECONDS / pts_scale
    return min(GIF_MAX_SECONDS / pts_scale, max(GIF_MIN_SECONDS / pts_scale, duration))


def _filter_quote(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def drawtext_filter(textfile, *, fontfile=None, fontsize=28, y="h-text_h-40",
                    fontcolor="white", boxborderw=12, enable=None, x="(w-text_w)/2"):
    options = []
    if fontfile:
        options.append(f"fontfile={_filter_quote(fontfile)}")
    else:
        options.append(f"font={_filter_quote(FONT_FAMILY)}")
    options.extend([
        f"textfile={_filter_quote(textfile)}",
        f"fontcolor={fontcolor}",
        f"fontsize={fontsize}",
        "box=1",
        "boxcolor=black@0.55",
        f"boxborderw={boxborderw}",
        "expansion=none",
        f"x={x}",
        f"y={y}",
    ])
    if enable is not None:
        options.append(f"enable={_filter_quote(enable)}")
    return "drawtext=" + ":".join(options)


# ---------------------------------------------------------------------------
# Window geometry
# ---------------------------------------------------------------------------

def parse_signed_number(value):
    sign = 1
    if value.startswith("+"):
        value = value[1:]
    if value.startswith("-"):
        sign = -1
        value = value[1:]
    return sign * int(value)


def window_candidates(text):
    """Retain bounded window metadata, never the raw process/game log."""
    candidates = []
    geometry = re.compile(r"(\d+x\d+[+-]-?\d+[+-]-?\d+)")
    for line in text.splitlines():
        if '"' not in line:
            continue
        match = geometry.search(line)
        if not match:
            continue
        title = line.split('"', 2)[1]
        candidates.append({"title": title[:120], "geometry": match.group(1)})
        if len(candidates) >= 32:
            break
    return candidates


def parse_xwininfo_tree(text):
    """Return ``(x, y, width, height)`` for the largest Minecraft window."""
    geometry = re.compile(r"(\d+)x(\d+)([+-]-?\d+)([+-]-?\d+)(?:\s+([+-]-?\d+)([+-]-?\d+))?")
    best = None
    for line in text.splitlines():
        if "minecraft" not in line.lower() or '"' not in line:
            continue
        tail = line.rsplit('"', 1)[-1]
        match = geometry.search(tail)
        if not match:
            continue
        width, height = int(match.group(1)), int(match.group(2))
        if width <= 0 or height <= 0:
            continue
        if match.group(5) is None or match.group(6) is None:
            # Without the absolute position a parent-relative rectangle is not
            # safe to grab; fall back to the whole display instead.
            continue
        x = parse_signed_number(match.group(5))
        y = parse_signed_number(match.group(6))
        if x < 0 or y < 0:
            continue
        if best is None or width * height > best[2] * best[3]:
            best = (x, y, width, height)
    return best


# ---------------------------------------------------------------------------
# Repository metadata
# ---------------------------------------------------------------------------

def read_mod_version(repo_root):
    try:
        text = (Path(repo_root) / "gradle.properties").read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("mod_version="):
            return stripped.split("=", 1)[1].strip() or None
    return None


def resolve_commit(repo_root, run, which):
    git = which("git")
    if not git:
        return "unknown"
    try:
        completed = run([git, "-C", str(repo_root), "rev-parse", "HEAD"],
                        capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired, UnicodeError):
        return "unknown"
    if completed.returncode == 0 and completed.stdout.strip():
        return completed.stdout.strip()
    return "unknown"


def resolve_font(which, run, environ):
    override = environ.get("SYNCMATERIAL_DEMO_FONT")
    if override:
        candidate = Path(override)
        return str(candidate.resolve()) if candidate.is_file() else override
    fc_match = which("fc-match")
    if fc_match:
        try:
            completed = run([fc_match, "-f", "%{file}", FONT_FAMILY],
                            capture_output=True, text=True, timeout=10)
        except (OSError, subprocess.TimeoutExpired, UnicodeError):
            completed = None
        if completed is not None and completed.returncode == 0:
            value = (completed.stdout or "").strip()
            if value:
                candidate = Path(value)
                if candidate.is_file():
                    return str(candidate.resolve())
    for candidate in FONT_CANDIDATES:
        if Path(candidate).is_file():
            return candidate
    return None


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def iso_timestamp(wall_seconds):
    return datetime.fromtimestamp(wall_seconds, timezone.utc).isoformat(timespec="seconds")


def tsv_cell(value):
    return str(value).replace("\t", " ").replace("\r", " ").replace("\n", " ")


# ---------------------------------------------------------------------------
# Recorder
# ---------------------------------------------------------------------------

class ClientDemoRecorder:
    """Runs Gradle under Xvfb and records the target GameTest window."""

    def __init__(self, *, run_dir, out, node, backend, scenario, title, gradle_args,
                 timeout_seconds=None, repo_root=REPO_ROOT, poll_interval=POLL_INTERVAL_SECONDS,
                 spawn=None, run=None, which=None, clock=None, wall_clock=None, sleep=None,
                 environ=None, display_factory=None, mode="scenario"):
        self.run_dir = Path(run_dir).resolve()
        self.out_dir = Path(out).resolve()
        self.node = node
        self.backend = backend
        self.scenario = scenario
        self.title = title
        self.mode = mode
        self.gradle_args = list(gradle_args)
        self.timeout_seconds = (DEFAULT_TIMEOUT_SECONDS if timeout_seconds is None
                                else float(timeout_seconds))
        self.repo_root = Path(repo_root).resolve()
        self.out_dir_safe = not (self.out_dir == self.repo_root
                                 or self.repo_root.is_relative_to(self.out_dir))
        self.poll_interval = poll_interval
        self.spawn = spawn or subprocess.Popen
        self.run_command = run or subprocess.run
        self.which = which or shutil.which
        self.clock = clock or time.monotonic
        self.wall_clock = wall_clock or time.time
        self.sleep = sleep or time.sleep
        self.environ = dict(os.environ if environ is None else environ)
        self.display_factory = display_factory or start_virtual_display

        self.ffmpeg = self.which("ffmpeg")
        self.log_path = self.run_dir / "logs" / "latest.log"
        self.log_tail = LogTail(self.log_path)
        if self.mode == "suite":
            classes, titles = load_suite_tests(self.repo_root, backend)
            self.timeline = SuiteTimeline(classes, titles)
        elif self.mode == "scenario":
            self.timeline = DemoTimeline(scenario.target_class, dict(scenario.step_titles))
        else:
            raise DemoError(f"未知录制模式 {self.mode!r}")
        self.warnings = []
        self.display = None
        self.gradle = None
        self.gradle_exit = None
        self.capture = None
        self.capture_attempted = False
        self.capture_allowed = True
        self.capture_started_epoch_ms = None
        self.capture_ended_epoch_ms = None
        self.demo_time_scale = 1.0
        self.geometry = None
        self.geometry_fallback = False
        self.content_valid = None
        self.window_candidates = []
        self.timed_out = False
        self.started_at = None
        self.gif_attempts = []

    # -- setup -------------------------------------------------------------

    def _warn(self, message):
        if message not in self.warnings:
            self.warnings.append(message)
        print(f"[record_client_demo] 警告: {message}", file=sys.stderr)

    def _prepare_outputs(self):
        self.out_dir.mkdir(parents=True, exist_ok=True)
        for name in ("raw.mkv", "raw.mp4", "demo.mp4", "preview.gif",
                     "preview-palette.png", "poster.png", "events.tsv", "manifest.json"):
            try:
                (self.out_dir / name).unlink()
            except FileNotFoundError:
                continue
            except OSError as error:
                self._warn(f"无法清理旧产物 {name}: {error}")

    def _start_display(self):
        self.display = self.display_factory(which=self.which, spawn=self.spawn,
                                            clock=self.clock, sleep=self.sleep,
                                            environ=self.environ)
        if self.display.reused:
            self._warn(f"未找到 Xvfb，使用现有 DISPLAY {self.display.name}")

    def _start_gradle(self):
        gradlew = self.repo_root / "gradlew"
        if gradlew.is_file():
            command = [str(gradlew), *self.gradle_args]
        else:
            gradle = self.which("gradle")
            if not gradle:
                raise DemoError(f"找不到可执行的 {gradlew} 或 gradle")
            command = [gradle, *self.gradle_args]
        env = dict(self.environ)
        env["DISPLAY"] = self.display.name
        env["SYNCMATERIAL_EXTERNAL_XVFB"] = "1"
        env.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
        env.setdefault("MESA_GL_VERSION_OVERRIDE", "4.6")
        env.setdefault("MESA_GLSL_VERSION_OVERRIDE", "460")
        self.gradle_env = env
        self.gradle_command = command
        self.started_at = iso_timestamp(self.wall_clock())
        try:
            self.gradle = self.spawn(command, cwd=str(self.repo_root), env=env,
                                     start_new_session=True)
        except OSError as error:
            raise DemoError(f"无法启动 Gradle: {error}") from error

    # -- run/monitor -------------------------------------------------------

    def run(self):
        if not self.out_dir_safe:
            self._warn("--out 不能是仓库根目录或其上级目录，拒绝运行以避免覆盖源码")
            return 1
        exit_code = None
        forced_result = None
        try:
            self._prepare_outputs()
            self.log_tail.skip_existing()
            self._start_display()
            self._start_gradle()
            self._monitor()
        except DemoError as error:
            self._warn(str(error))
            forced_result = "ERROR"
            exit_code = 1
        except Exception as error:  # keep the run reportable even on harness bugs
            self._warn(f"录制流程异常: {error}")
            forced_result = "ERROR"
            exit_code = 1
        finally:
            if self.gradle is not None:
                try:
                    if self.gradle.poll() is None:
                        _terminate_process(self.gradle, group=True)
                except OSError:
                    pass
            self._stop_capture()
            if self.display is not None:
                self.display.stop()
        if self.timed_out:
            exit_code = 124
        elif exit_code is None:
            exit_code = self.gradle_exit if self.gradle_exit is not None else 1
        try:
            self._finalize(exit_code, forced_result)
        except Exception as error:
            self._warn(f"生成演示产物失败: {error}")
        return int(exit_code)

    def _monitor(self):
        tail = self.log_tail
        deadline = self.clock() + self.timeout_seconds
        while True:
            for line in tail.read():
                self._handle_line(line)
            code = self.gradle.poll()
            if code is not None:
                self.gradle_exit = int(code)
                self.capture_allowed = False
                for line in tail.read(final=True):
                    self._handle_line(line)
                self._finish_timeline(code)
                self._stop_capture()
                return
            if self.clock() >= deadline:
                self.timed_out = True
                self.capture_allowed = False
                self._warn(f"运行超过 {self.timeout_seconds:g} 秒，强制停止 Gradle 与录制")
                self._stop_capture()
                _terminate_process(self.gradle, group=True)
                self.gradle_exit = 124
                for line in tail.read(final=True):
                    self._handle_line(line)
                self._finish_timeline(124)
                return
            self.sleep(self.poll_interval)

    def _finish_timeline(self, exit_code):
        finish = getattr(self.timeline, "finish", None)
        if finish is not None:
            finish(exit_code, int(self.wall_clock() * 1000))

    def _handle_line(self, line):
        action = self.timeline.consume(line, int(self.wall_clock() * 1000))
        if action == "start" and self.capture_allowed:
            self._start_capture()
        elif self.mode == "scenario" and action in ("pass", "fail"):
            self._stop_capture()

    # -- capture -----------------------------------------------------------

    def _run_tool(self, command, timeout=10):
        try:
            return self.run_command(command, capture_output=True, text=True, timeout=timeout,
                                    env=self.gradle_env if hasattr(self, "gradle_env") else None)
        except (OSError, subprocess.TimeoutExpired, UnicodeError) as error:
            self._warn(f"命令失败 {Path(command[0]).name}: {error}")
            return None

    def _start_capture(self):
        if self.capture_attempted:
            return
        self.capture_attempted = True
        if not self.ffmpeg:
            self._warn("未找到 ffmpeg，跳过屏幕录制")
            return
        geometry = None
        xwininfo = self.which("xwininfo")
        if xwininfo:
            completed = self._run_tool([xwininfo, "-root", "-tree"], timeout=10)
            if completed is not None and completed.returncode == 0:
                window_tree = completed.stdout or ""
                self.window_candidates = window_candidates(window_tree)
                geometry = parse_xwininfo_tree(window_tree)
            if geometry is None:
                self._warn("xwininfo 未定位到 Minecraft 窗口，回退整个 DISPLAY")
        else:
            self._warn("未找到 xwininfo，回退整个 DISPLAY")
        self.geometry = geometry
        self.geometry_fallback = geometry is None
        self.out_dir.mkdir(parents=True, exist_ok=True)
        capture_limit = min(MAX_CAPTURE_SECONDS, self.timeout_seconds)
        command = build_capture_command(self.ffmpeg, self.display.name, geometry,
                                        (self.display.width, self.display.height),
                                        self.out_dir / "raw.mkv", capture_limit)
        try:
            self.capture = self.spawn(command, cwd=str(self.out_dir),
                                      env=self.gradle_env, stdin=subprocess.DEVNULL)
        except OSError as error:
            self._warn(f"无法启动 ffmpeg x11grab: {error}")
            return
        self.capture_started_epoch_ms = int(self.wall_clock() * 1000)

    def _stop_capture(self):
        if self.capture_started_epoch_ms is not None and self.capture_ended_epoch_ms is None:
            self.capture_ended_epoch_ms = int(self.wall_clock() * 1000)
        proc = self.capture
        self.capture = None
        if proc is None:
            return
        try:
            alive = proc.poll() is None
        except OSError:
            alive = False
        if alive:
            try:
                proc.send_signal(signal.SIGINT)
            except OSError:
                pass
            try:
                proc.wait(timeout=CAPTURE_STOP_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                # ffmpeg also inherits this script's process group.
                _terminate_process(proc, grace=5, group=False)
        code = getattr(proc, "returncode", None)
        if code is not None and code not in (0, -signal.SIGINT, 255):
            self._warn(f"ffmpeg 录制异常退出，returncode={code}")

    def capture_duration(self):
        if self.capture_started_epoch_ms is None:
            return 0.0
        end = self.capture_ended_epoch_ms
        if end is None:
            end = int(self.wall_clock() * 1000)
        return max(0.0, (end - self.capture_started_epoch_ms) / 1000.0)

    # -- media -------------------------------------------------------------

    def _run_media(self, command, cwd=None):
        if not self.ffmpeg:
            self._warn("未找到 ffmpeg，跳过媒体命令")
            return False
        try:
            completed = self.run_command(command, cwd=str(cwd or self.out_dir),
                                 capture_output=True, text=True,
                                 timeout=MEDIA_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            self._warn(f"媒体命令超过 {MEDIA_TIMEOUT_SECONDS:g} 秒，已强制停止")
            return False
        except OSError as error:
            self._warn(f"媒体命令无法执行: {error}")
            return False
        if completed.returncode != 0:
            lines = (completed.stderr or "").strip().splitlines()
            detail = lines[-1] if lines else ""
            self._warn(f"媒体命令失败（exit {completed.returncode}）: {detail[:300]}")
            return False
        return True

    def video_origin_epoch_ms(self):
        return self.capture_started_epoch_ms or self.timeline.start_epoch_ms

    def video_seconds_for(self, epoch_ms):
        origin = self.video_origin_epoch_ms()
        if epoch_ms is None or origin is None:
            return 0.0
        return max(0.0, (epoch_ms - origin) / 1000.0)

    def demo_seconds_for(self, epoch_ms):
        return self.video_seconds_for(epoch_ms) * self.demo_time_scale

    def _result_video_seconds(self):
        if self.timeline.result_epoch_ms is None:
            return None
        return self.demo_seconds_for(self.timeline.result_epoch_ms)

    def _step_windows(self, duration):
        windows = []
        steps = self.timeline.steps
        for index, step in enumerate(steps):
            start = self.demo_seconds_for(step.epoch_ms)
            if index + 1 < len(steps):
                end = max(start, self.demo_seconds_for(steps[index + 1].epoch_ms))
            else:
                end = max(start + 2.0, duration)
            windows.append((step, start, end))
        return windows

    def _check_raw_content(self, source, duration):
        """Reject an entirely black capture without affecting the GameTest result."""
        command = [self.ffmpeg, "-hide_banner", "-nostdin", "-i", str(source),
                   "-vf", "blackdetect=d=0.1:pix_th=0.02:pic_th=0.98",
                   "-an", "-f", "null", "-"]
        completed = self._run_tool(command, timeout=60)
        if completed is None or completed.returncode != 0:
            self._warn("无法检查原始视频是否为全黑，停止发布该媒体")
            self.content_valid = False
            return False
        output = (completed.stdout or "") + "\n" + (completed.stderr or "")
        matches = re.findall(r"black_start:([0-9.]+)\s+black_end:([0-9.]+)", output)
        entirely_black = any(float(start) <= 0.1
                             and float(end) >= max(0.1, duration - 0.2)
                             for start, end in matches)
        self.content_valid = not entirely_black
        if entirely_black:
            self._warn("原始画面为全黑，拒绝生成可发布的演示媒体")
            return False
        return True

    def _render_suite_demo(self, source, output, duration, result):
        """Annotate the complete client suite without changing real-time footage."""
        self.demo_time_scale = 1.0
        font = resolve_font(self.which, self.run_command, self.environ)
        with tempfile.TemporaryDirectory(prefix=".demo-suite-overlay-", dir=str(self.out_dir)) as tmp:
            work = Path(tmp)
            filters = ["setpts=1*PTS"]

            def text(name, value, *, size, y, x="16", enable=None, color="white"):
                (work / name).write_text(value, encoding="utf-8")
                filters.append(drawtext_filter(name, fontfile=font, fontsize=size, y=y, x=x,
                                               fontcolor=color, boxborderw=8, enable=enable))

            text("suite-header.txt", "SyncMaterial CI · 客户端完整回归", size=22, y="14")
            mod_version = read_mod_version(self.repo_root) or "unknown"
            text("suite-node.txt", f"{self.node} · {self.backend} · mod {mod_version}", size=14, y="48")
            run_id = self.environ.get("GITHUB_RUN_ID", "local")
            commit = resolve_commit(self.repo_root, self.run_command, self.which)
            text("suite-run.txt", f"run {run_id} · commit {commit[:12]}",
                 size=14, y="14", x="w-text_w-16")
            text("suite-env.txt", "离线 Player0 · 真实客户端操作",
                 size=14, y="48", x="w-text_w-16")

            total = self.timeline.expected
            for index, (step, start, end) in enumerate(self._step_windows(duration), 1):
                enable = f"between(t,{start:.3f},{end:.3f})"
                text(f"suite-test-{index}.txt", f"{index:02d}/{total:02d} · {step.title}",
                     size=20, y="h-text_h-68", enable=enable)
                passed, failed = self.timeline.progress_before(index)
                text(f"suite-progress-{index}.txt",
                     f"已完成 {index - 1}/{total} · 通过 {passed} · 失败 {failed}",
                     size=16, y="h-text_h-34", enable=enable)
                elapsed = f"{int(start // 60):02d}:{int(start % 60):02d}"
                text(f"suite-time-{index}.txt", f"进行中 · {elapsed}",
                     size=16, y="h-text_h-34", x="w-text_w-16", enable=enable)

            if result in ("PASS", "FAIL", "TIMEOUT"):
                result_at = self._result_video_seconds()
                if result_at is None:
                    result_at = max(0.0, duration - 2.0)
                result_start = max(0.0, min(result_at, duration - 2.0))
                color = "0x3DDC84" if result == "PASS" else "0xE53935"
                text("suite-result.txt", result, size=30, y="82", x="w-text_w-16",
                     enable=f"between(t,{result_start:.3f},{max(duration, result_start + 2.0):.3f})",
                     color=color)
            command = build_overlay_command(self.ffmpeg, source, output, filters)
            if self._run_media(command, cwd=work):
                return True
            try:
                output.unlink()
            except OSError:
                pass
            return False

    def _render_demo(self, source, output, duration, result):
        if self.mode == "suite":
            return self._render_suite_demo(source, output, duration, result)
        self.demo_time_scale = DEMO_TIME_SCALE
        duration *= self.demo_time_scale
        font = resolve_font(self.which, self.run_command, self.environ)
        with tempfile.TemporaryDirectory(prefix=".demo-overlay-", dir=str(self.out_dir)) as tmp:
            work = Path(tmp)
            (work / "title.txt").write_text(self.title, encoding="utf-8")
            filters = [f"setpts={DEMO_TIME_SCALE:g}*PTS",
                       drawtext_filter("title.txt", fontfile=font, fontsize=34, y="28")]
            if self.scenario.explanation:
                (work / "explanation.txt").write_text(self.scenario.explanation, encoding="utf-8")
                filters.append(drawtext_filter("explanation.txt", fontfile=font, fontsize=20, y="72"))
            windows = self._step_windows(duration)
            for index, (step, start, end) in enumerate(windows, 1):
                name = f"step-{index}.txt"
                (work / name).write_text(f"步骤 {index}/{len(windows)}：{step.title}",
                                         encoding="utf-8")
                filters.append(drawtext_filter(
                    name, fontfile=font, fontsize=26, y="h-text_h-40",
                    enable=f"between(t,{start:.3f},{end:.3f})"))
            if result in ("PASS", "FAIL"):
                result_at = self._result_video_seconds()
                if result_at is None:
                    result_at = max(0.0, duration - 2.0)
                result_start = result_at
                if duration - result_start < 2.0:
                    result_start = max(0.0, duration - 2.0)
                result_end = max(duration, result_start + 2.0)
                (work / "result.txt").write_text(result, encoding="utf-8")
                color = "0x3DDC84" if result == "PASS" else "0xE53935"
                filters.append(drawtext_filter(
                    "result.txt", fontfile=font, fontsize=72, y="(h-text_h)/2",
                    fontcolor=color, boxborderw=24,
                    enable=f"between(t,{result_start:.3f},{result_end:.3f})"))
            command = build_overlay_command(self.ffmpeg, source, output, filters)
            if self._run_media(command, cwd=work):
                return True
            try:
                output.unlink()
            except OSError:
                pass
            return False

    def _write_poster(self, source, duration):
        at = max(0.0, duration / 2.0)
        if self.timeline.steps:
            at = max(0.0, self.demo_seconds_for(self.timeline.steps[0].epoch_ms) + 0.5)
        at = min(at, max(0.0, duration - 0.05))
        output = self.out_dir / "poster.png"
        if not self._run_media(build_poster_command(self.ffmpeg, source, output, at)):
            try:
                output.unlink()
            except OSError:
                pass

    def _write_gif(self, source, duration):
        pts_scale = GIF_PLAYBACK_SCALE / self.demo_time_scale
        clip = preview_clip(duration, pts_scale)
        start = max(0.0, duration - clip)
        palette = self.out_dir / "preview-palette.png"
        attempts = ((GIF_WIDTH, GIF_FPS, 256),
                    (GIF_WIDTH, 8, 160),
                    (640, 8, 96),
                    (480, 6, 64),
                    (400, 5, 32))
        try:
            for width, fps, colors in attempts:
                gif = self.out_dir / "preview.gif"
                palette_cmd, gif_cmd = build_gif_commands(
                    self.ffmpeg, source, palette, gif, start, clip,
                    width=width, fps=fps, colors=colors, pts_scale=pts_scale)
                if not self._run_media(palette_cmd):
                    break
                if not self._run_media(gif_cmd):
                    try:
                        gif.unlink()
                    except OSError:
                        pass
                    break
                size = gif.stat().st_size if gif.exists() else 0
                self.gif_attempts.append({"width": width, "fps": fps,
                                          "colors": colors, "bytes": size})
                if size and size <= GIF_MAX_BYTES:
                    return
                try:
                    gif.unlink()
                except OSError:
                    pass
            self._warn("preview.gif 生成失败或未能控制在 5 MiB 以内")
        finally:
            try:
                palette.unlink()
            except OSError:
                pass

    # -- reports -----------------------------------------------------------

    def _write_events(self):
        lines = ["type\tstep_id\ttitle\tepoch_ms\tvideo_seconds\traw_video_seconds"]
        for event in self.timeline.events:
            lines.append("\t".join([
                tsv_cell(event.kind), tsv_cell(event.step_id), tsv_cell(event.title),
                str(event.epoch_ms), f"{self.demo_seconds_for(event.epoch_ms):.3f}",
                f"{self.video_seconds_for(event.epoch_ms):.3f}",
            ]))
        (self.out_dir / "events.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def _write_manifest(self, exit_code, result, duration):
        try:
            self._write_events()
        except OSError as error:
            self._warn(f"无法写入 events.tsv: {error}")
        files = {}
        for name in ("raw.mkv", "raw.mp4", "demo.mp4", "preview.gif",
                     "poster.png", "events.tsv"):
            path = self.out_dir / name
            if path.is_file():
                try:
                    files[name] = {"bytes": path.stat().st_size,
                                   "sha256": sha256_file(path)}
                except OSError as error:
                    self._warn(f"无法计算 {name} 的 hash: {error}")
        if self.content_valid is False:
            media_status = "invalid"
        elif self.timeline.start_epoch_ms is None:
            media_status = "skipped"
        elif self.warnings:
            media_status = "warnings"
        else:
            media_status = "ok"
        commit = resolve_commit(self.repo_root, self.run_command, self.which)
        if commit == "unknown":
            commit = self.environ.get("GITHUB_SHA") or "unknown"
        manifest = {
            "schema": SCHEMA_VERSION,
            "commit": commit,
            "run": self.environ.get("GITHUB_RUN_ID") or "local",
            "runAttempt": self.environ.get("GITHUB_RUN_ATTEMPT"),
            "node": self.node,
            "backend": self.backend,
            "modVersion": read_mod_version(self.repo_root),
            "scenario": "client-regression" if self.mode == "suite" else self.scenario.key,
            "recordingMode": self.mode,
            "suite": self.timeline.stats() if self.mode == "suite" else None,
            "title": self.title,
            "targetClass": self.scenario.target_class,
            "result": result,
            "timedOut": self.timed_out,
            "gradleExitCode": exit_code,
            "startedAt": self.started_at or iso_timestamp(self.wall_clock()),
            "finishedAt": iso_timestamp(self.wall_clock()),
            "durationSeconds": round(duration * self.demo_time_scale, 3),
            "rawDurationSeconds": round(duration, 3),
            "playback": {
                "demoTimeScale": self.demo_time_scale,
                "gifTimeScale": GIF_PLAYBACK_SCALE / self.demo_time_scale,
            },
            "capture": {
                "started": self.capture_started_epoch_ms is not None,
                "geometry": list(self.geometry) if self.geometry else None,
                "fallbackDisplay": self.geometry_fallback,
                "contentCheck": ("passed" if self.content_valid is True else
                                 "failed" if self.content_valid is False else "unavailable"),
                "windowCandidates": self.window_candidates,
                "seconds": round(duration, 3),
            },
            "steps": [
                {"index": step.index, "id": step.step_id, "title": step.title,
                 "epochMs": step.epoch_ms,
                 "videoSeconds": round(self.demo_seconds_for(step.epoch_ms), 3),
                 "rawVideoSeconds": round(self.video_seconds_for(step.epoch_ms), 3),
                 **({"status": self.timeline.status_for(step)} if self.mode == "suite" else {})}
                for step in self.timeline.steps
            ],
            "files": files,
            "gifAttempts": self.gif_attempts,
            "media": {"status": media_status, "warnings": list(self.warnings)},
        }
        (self.out_dir / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")

    def _finalize(self, exit_code, forced_result=None):
        try:
            self.out_dir.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            self._warn(f"无法创建输出目录 {self.out_dir}: {error}")
        result = (forced_result or self.timeline.result
                  or ("TIMEOUT" if self.timed_out else "UNKNOWN"))
        duration = self.capture_duration()
        if self.timeline.start_epoch_ms is None and forced_result is None:
            self._warn("日志中未出现目标用例 START，本次未录制")
        raw_mkv = self.out_dir / "raw.mkv"
        raw_mp4 = self.out_dir / "raw.mp4"
        demo_mp4 = self.out_dir / "demo.mp4"
        if not raw_mkv.is_file() or raw_mkv.stat().st_size <= 0:
            if self.timeline.start_epoch_ms is not None:
                self._warn("未生成有效的 raw.mkv，跳过 remux/叠加/GIF")
            self._write_manifest(exit_code, result, duration)
            return
        if not self._run_media(build_remux_command(self.ffmpeg, raw_mkv, raw_mp4)):
            self._warn("raw.mkv remux 为 raw.mp4 失败")
            try:
                raw_mp4.unlink()
            except OSError:
                pass
            self._write_manifest(exit_code, result, duration)
            return
        if not raw_mp4.is_file() or raw_mp4.stat().st_size <= 0:
            self._warn("raw.mp4 为空，跳过叠加/GIF")
            try:
                raw_mp4.unlink()
            except OSError:
                pass
            self._write_manifest(exit_code, result, duration)
            return
        if not self._check_raw_content(raw_mp4, duration):
            self._write_manifest(exit_code, result, duration)
            return
        if not self._render_demo(raw_mp4, demo_mp4, duration, result):
            self.demo_time_scale = 1.0
            self._warn("demo.mp4 文本叠加失败，回退为原始画面")
            if not self._run_media(build_copy_command(self.ffmpeg, raw_mp4, demo_mp4)):
                self._warn("demo.mp4 回退复制失败")
                try:
                    demo_mp4.unlink()
                except OSError:
                    pass
        source = demo_mp4 if demo_mp4.is_file() and demo_mp4.stat().st_size > 0 else raw_mp4
        output_duration = duration * self.demo_time_scale
        self._write_poster(source, output_duration)
        self._write_gif(source, output_duration)
        self._write_manifest(exit_code, result, duration)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser():
    parser = argparse.ArgumentParser(
        prog="record_client_demo.py",
        description="在 Xvfb 中运行客户端 GameTest，并把目标场景录制为演示媒体。",
        epilog=(
            "示例:\n"
            "  .github/scripts/record_client_demo.sh \\\n"
            "    --run-dir .runs/1.21.8/legacy-client --out demo-out \\\n"
            "    --node 1.21.8 --backend legacy --scenario warehouse-ref-popup \\\n"
            "    --title \"仓库引用弹窗闭环\" --timeout-seconds 900 \\\n"
            "    --gradle-arg=:1.21.8:runClientGameTest --gradle-arg=--no-build-cache\n\n"
            "以 - 开头的 Gradle 参数可直接跟在 --gradle-arg 后，也可用 = 形式，或放在 -- 之后。\n"
            "媒体处理失败只输出警告；Gradle 的退出码始终原样返回。\n"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--run-dir", required=True,
                        help="客户端运行目录；日志读取 <run-dir>/logs/latest.log")
    parser.add_argument("--out", required=True, help="媒体与 manifest 输出目录")
    parser.add_argument("--node", required=True, help="Minecraft 节点，如 1.21.8")
    parser.add_argument("--backend", required=True,
                        help="后端标识；默认 Gradle 任务由它推导（legacy/foundation/...）")
    parser.add_argument("--mode", choices=("scenario", "suite"), default="scenario",
                        help="scenario只录目标场景；suite记录整个客户端测试套件")
    parser.add_argument("--scenario", default=DEFAULT_SCENARIO,
                        help=f"场景标识，默认 {DEFAULT_SCENARIO}")
    parser.add_argument("--title", default=None, help="演示标题；缺省使用场景默认标题")
    parser.add_argument("--timeout-seconds", type=float, default=None,
                        help=f"整体运行超时，默认 {DEFAULT_TIMEOUT_SECONDS:g} 秒；超时返回 124")
    parser.add_argument("--gradle-arg", action="append", default=[], metavar="ARG",
                        help="传给 ./gradlew 的参数，可重复；分离形式、= 形式均可")
    parser.add_argument("gradle_remainder", nargs=argparse.REMAINDER,
                        metavar="GRADLE_ARGS",
                        help="-- 之后的参数原样传给 ./gradlew")
    return parser


def load_scenario(key, title_override=None):
    data = SCENARIOS.get(key)
    if data is None:
        known = ", ".join(sorted(SCENARIOS))
        raise DemoError(f"未知场景 {key!r}；可用场景: {known}")
    return Scenario(key=key, target_class=data["target"],
                    title=title_override or data["title"],
                    step_titles=dict(data["steps"]),
                    explanation=data.get("explanation", ""))


def collect_gradle_args(args):
    values = list(args.gradle_arg)
    rest = list(args.gradle_remainder)
    if rest and rest[0] == "--":
        rest = rest[1:]
    values.extend(rest)
    if values:
        return values
    task = BACKEND_TASKS.get(args.backend)
    if task is None:
        return None
    return [f":{args.node}:{task}", "--no-build-cache", "--stacktrace"]


def normalize_argv(argv):
    """Allow ``--gradle-arg --no-build-cache`` in addition to the ``=`` form.

    ``argparse`` refuses a dash-prefixed value for ``--gradle-arg`` unless it is
    attached with ``=``; joining the pair keeps the convenient repeatable form.
    Tokens after a literal ``--`` are left untouched for the remainder form.
    """
    tokens = list(sys.argv[1:] if argv is None else argv)
    normalized = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token == "--":
            normalized.extend(tokens[index:])
            break
        if token == "--gradle-arg" and index + 1 < len(tokens):
            normalized.append("--gradle-arg=" + tokens[index + 1])
            index += 2
            continue
        normalized.append(token)
        index += 1
    return normalized


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(normalize_argv(argv))
    if (args.timeout_seconds is not None
            and (not math.isfinite(args.timeout_seconds) or args.timeout_seconds <= 0)):
        parser.error("--timeout-seconds 必须是有限正数")
    try:
        scenario = load_scenario(args.scenario, args.title)
    except DemoError as error:
        parser.error(str(error))
    gradle_args = collect_gradle_args(args)
    if gradle_args is None:
        parser.error(f"未知 backend {args.backend!r}，请显式提供 --gradle-arg")
    title = args.title or (f"CI 客户端完整回归 · {args.node} {args.backend}"
                           if args.mode == "suite" else scenario.title)
    recorder = ClientDemoRecorder(
        run_dir=args.run_dir, out=args.out, node=args.node, backend=args.backend,
        scenario=scenario, title=title, gradle_args=gradle_args,
        timeout_seconds=args.timeout_seconds, mode=args.mode)
    return recorder.run()


if __name__ == "__main__":
    sys.exit(main())
