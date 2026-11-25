#!/usr/bin/env python3
"""Build the offline SyncMaterial client-demo page and step summary.

The generator consumes the media bundle produced by ``record_client_demo.py``:

    manifest.json events.tsv raw.mp4 demo.mp4 preview.gif poster.png

and writes only into ``<out>/demos/<run-id>/``:

    index.html summary.md raw.mp4 demo.mp4 preview.gif poster.png

``manifest.json`` is the authority for commit/node/backend/result/steps.  Both
the recorder's camelCase keys (``modVersion``, ``videoSeconds``) and common
snake_case aliases are accepted.  ``events.tsv`` is a fallback source for
steps/result only; its free-form columns are never copied into the page.  The
generated HTML has no scripts, no external resources and no absolute paths:
every link is a plain file name inside the run directory.  Manifest text is
HTML-escaped before it is written.
"""

from __future__ import annotations

import argparse
import csv
import html
import io
import json
import math
import os
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, urlsplit

TITLE = "仓库引用操作演示"
DEFAULT_REPO = "is-wuyi/Syncmaterial"
DEFAULT_BASE_URL = "https://is-wuyi.github.io/Syncmaterial"

REQUIRED_MEDIA = (
    "raw.mp4",
    "demo.mp4",
    "preview.gif",
    "poster.png",
    "events.tsv",
    "manifest.json",
)
COPY_MEDIA = ("raw.mp4", "demo.mp4", "preview.gif", "poster.png")

RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
MAX_TEXT = 2000

STATUS_ALIASES = {
    "PASS": "PASS",
    "PASSED": "PASS",
    "SUCCESS": "PASS",
    "SUCCEEDED": "PASS",
    "OK": "PASS",
    "TRUE": "PASS",
    "YES": "PASS",
    "通过": "PASS",
    "成功": "PASS",
    "FAIL": "FAIL",
    "FAILED": "FAIL",
    "FAILURE": "FAIL",
    "FALSE": "FAIL",
    "NO": "FAIL",
    "失败": "FAIL",
    "SKIP": "SKIP",
    "SKIPPED": "SKIP",
    "TIMEOUT": "TIMEOUT",
    "TIMED_OUT": "TIMEOUT",
    "ERROR": "ERROR",
    "UNKNOWN": "UNKNOWN",
}
TERMINAL_STATUSES = frozenset(("PASS", "FAIL", "SKIP", "TIMEOUT", "ERROR", "UNKNOWN"))

HEADER_FIELDS = {
    "type": ("type", "kind", "event", "event_type"),
    "step": ("step_id", "step", "id", "name", "label"),
    "title": ("title", "step_title", "caption"),
    "status": ("status", "result", "outcome", "verdict"),
    "seconds": ("video_seconds", "videoseconds", "seconds", "time", "t", "timestamp"),
}


class DemoPageError(RuntimeError):
    """A clear, user-facing input or output error."""


@dataclass(frozen=True)
class Step:
    title: str
    status: str | None = None
    seconds: float | None = None


@dataclass(frozen=True)
class DemoData:
    commit: str
    node: str
    backend: str
    result: str
    steps: tuple
    mod_version: str | None = None
    scenario: str | None = None
    title: str | None = None


def esc(value) -> str:
    return html.escape(str(value), quote=True)


def _normalize_text(value, label: str, *, optional: bool = False) -> str | None:
    if value is None:
        if optional:
            return None
        raise DemoPageError(f"{label} 不能为空")
    if isinstance(value, bool):
        raise DemoPageError(f"{label} 必须是文本，不能是布尔值")
    if not isinstance(value, (str, int, float)):
        raise DemoPageError(f"{label} 必须是文本")
    raw = str(value)
    raw = "".join(ch if ch in "\t\n\r" or ord(ch) >= 32 else " " for ch in raw)
    text = " ".join(raw.split())
    if not text:
        if optional:
            return None
        raise DemoPageError(f"{label} 不能为空")
    if len(text) > MAX_TEXT:
        raise DemoPageError(f"{label} 过长（最多 {MAX_TEXT} 个字符）")
    return text


def clean_text(value, label: str) -> str:
    text = _normalize_text(value, label)
    assert text is not None
    return text


def normalize_status(value) -> str | None:
    if isinstance(value, bool):
        return "PASS" if value else "FAIL"
    if not isinstance(value, str):
        return None
    key = re.sub(r"[\s-]+", "_", value.strip().upper())
    return STATUS_ALIASES.get(key)


def status_class(status: str | None) -> str:
    return {
        "PASS": "pass",
        "FAIL": "fail",
        "SKIP": "skip",
        "TIMEOUT": "warn",
        "ERROR": "fail",
        "UNKNOWN": "neutral",
    }.get((status or "").upper(), "neutral")


def _lookup(mapping: dict, names: tuple[str, ...]):
    for name in names:
        if name in mapping and mapping[name] is not None:
            return mapping[name]
    for nested_key in ("run", "environment", "ci"):
        nested = mapping.get(nested_key)
        if not isinstance(nested, dict):
            continue
        for name in names:
            if name in nested and nested[name] is not None:
                return nested[name]
    return None


def _optional_manifest_text(manifest: dict, names: tuple[str, ...], label: str) -> str | None:
    value = _lookup(manifest, names)
    if value is None:
        return None
    return _normalize_text(value, f"manifest.json 字段 {label}", optional=True)


def load_manifest(path: Path) -> dict:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as error:
        raise DemoPageError(f"manifest.json 不是 UTF-8 文本: {error}") from error
    except OSError as error:
        raise DemoPageError(f"无法读取 manifest.json: {error}") from error
    try:
        manifest = json.loads(text)
    except json.JSONDecodeError as error:
        raise DemoPageError(
            f"manifest.json 不是有效 JSON（第 {error.lineno} 行第 {error.colno} 列）: {error.msg}"
        ) from error
    if not isinstance(manifest, dict):
        raise DemoPageError("manifest.json 顶层必须是 JSON 对象")
    return manifest


def _seconds(value) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(seconds) or seconds < 0:
        return None
    return seconds


def _step_seconds(item: dict) -> float | None:
    for key in ("videoSeconds", "video_seconds", "seconds", "time"):
        if key in item:
            return _seconds(item[key])
    return None


def _parse_step(item, index: int) -> Step:
    label = f"manifest.json 字段 steps[{index}]"
    if isinstance(item, str):
        return Step(title=clean_text(item, f"{label}.title"))
    if not isinstance(item, dict):
        raise DemoPageError(f"{label} 必须是文本或对象")

    title_value = _lookup(item, ("title", "name", "label", "step"))
    step_id = _lookup(item, ("id", "step_id", "key"))
    if title_value is None:
        if step_id is None:
            raise DemoPageError(f"{label} 缺少 title 或 id")
        title_value = step_id
    title = clean_text(title_value, f"{label}.title")

    status = None
    status_value = _lookup(item, ("status", "result", "outcome", "state", "success", "pass"))
    if status_value is not None:
        status = normalize_status(status_value)
        if status is None:
            raise DemoPageError(
                f"{label}.status 无法识别: {clean_text(status_value, f'{label}.status')!r}"
            )
    elif "passed" in item and item["passed"] is not None:
        status = normalize_status(item["passed"])
        if status is None:
            raise DemoPageError(f"{label}.passed 只能是布尔值或 PASS/FAIL")
    elif "ok" in item and item["ok"] is not None:
        status = normalize_status(item["ok"])
        if status is None:
            raise DemoPageError(f"{label}.ok 只能是布尔值或 PASS/FAIL")

    return Step(title=title, status=status, seconds=_step_seconds(item))


def parse_manifest_steps(raw) -> list[Step]:
    if raw is None:
        return []
    steps: list[Step] = []
    if isinstance(raw, dict):
        for index, (key, value) in enumerate(raw.items()):
            label = f"manifest.json 字段 steps[{index}]"
            if isinstance(value, dict):
                item = dict(value)
                item.setdefault("id", str(key))
                steps.append(_parse_step(item, index))
            elif isinstance(value, str):
                status = normalize_status(value)
                if status is not None:
                    steps.append(Step(title=clean_text(key, f"{label}.title"), status=status))
                else:
                    steps.append(Step(title=clean_text(value, f"{label}.title")))
            else:
                raise DemoPageError(f"{label} 必须是文本或对象")
        return steps
    if not isinstance(raw, list):
        raise DemoPageError("manifest.json 字段 steps 必须是数组或对象")
    for index, item in enumerate(raw):
        steps.append(_parse_step(item, index))
    return steps


def _manifest_result(manifest: dict) -> str | None:
    value = _lookup(manifest, ("result", "outcome", "status", "verdict", "success", "passed", "ok"))
    if value is not None:
        result = normalize_status(value)
        if result is None:
            label = clean_text(value, "manifest.json 字段 result")
            raise DemoPageError(f"manifest.json 字段 result 无法识别: {label!r}")
        return result
    if manifest.get("timedOut") is True:
        return "TIMEOUT"
    return None


def _normalize_header(value: str) -> str:
    return re.sub(r"[\s-]+", "_", value.strip().lower())


def _header_mapping(header: list[str]) -> dict[str, int] | None:
    mapping: dict[str, int] = {}
    for index, raw in enumerate(header):
        key = _normalize_header(raw)
        for field, aliases in HEADER_FIELDS.items():
            if key in aliases and field not in mapping:
                mapping[field] = index
                break
    if "type" in mapping:
        return mapping
    if "status" in mapping and ("step" in mapping or "title" in mapping):
        return mapping
    return None


def _cell(row: list[str], mapping: dict[str, int], field: str) -> str:
    index = mapping.get(field)
    if index is None or index >= len(row):
        return ""
    return row[index].strip()


def _event_step(row: list[str], mapping: dict[str, int], status: str | None,
                row_number: int) -> Step | None:
    title = _cell(row, mapping, "title") or _cell(row, mapping, "step")
    if not title:
        return None
    return Step(
        title=clean_text(title, f"events.tsv 第 {row_number} 行步骤标题"),
        status=status,
        seconds=_seconds(_cell(row, mapping, "seconds")),
    )


def _parse_event_rows(rows: list[list[str]], mapping: dict[str, int]) -> tuple[list[Step], str | None]:
    steps: list[Step] = []
    result: str | None = None
    for row_number, row in enumerate(rows, start=2):
        kind = _cell(row, mapping, "type").strip().upper()
        status = normalize_status(_cell(row, mapping, "status"))
        if kind in TERMINAL_STATUSES:
            result = status or kind
            continue
        if kind in ("STEP", "ACTION", "CHECK"):
            step = _event_step(row, mapping, status, row_number)
            if step is not None:
                steps.append(step)
            continue
        if kind in ("START", "GRADLE_EXIT", "WARNING", "INFO", "END"):
            continue
        if kind in ("RESULT", "SUMMARY"):
            if status is not None:
                result = status
            continue
        if kind == "" and status is not None:
            step = _event_step(row, mapping, status, row_number)
            if step is not None:
                steps.append(step)
            else:
                result = status
            continue
        step = _event_step(row, mapping, status, row_number)
        if step is not None:
            steps.append(step)
        elif status is not None:
            result = status
    return steps, result


def _parse_positional_events(rows: list[list[str]]) -> tuple[list[Step], str | None]:
    steps: list[Step] = []
    result: str | None = None
    for row_number, raw in enumerate(rows, start=1):
        cells = [cell.strip() for cell in raw]
        if not cells:
            continue
        first = cells[0].upper()
        if first in TERMINAL_STATUSES and not any(cell for cell in cells[1:]):
            result = first
            continue
        if first in ("STEP", "ACTION", "CHECK"):
            values = [cell for cell in cells[1:] if cell]
            if values:
                title = values[-1]
                for candidate in reversed(values):
                    if _seconds(candidate) is None:
                        title = candidate
                        break
                steps.append(Step(
                    title=clean_text(title, f"events.tsv 第 {row_number} 行步骤标题"),
                ))
            continue
        if first in ("START", "GRADLE_EXIT", "WARNING", "INFO", "END"):
            continue
        statuses = [
            (index, normalize_status(cell))
            for index, cell in enumerate(cells)
            if cell and normalize_status(cell) is not None
        ]
        if not statuses:
            continue
        index, status = statuses[0]
        assert status is not None
        if index == 0:
            result = status
        elif index == 1 and cells[0]:
            steps.append(Step(
                title=clean_text(cells[0], f"events.tsv 第 {row_number} 行步骤标题"),
                status=status,
            ))
        elif index >= 2 and len(cells) > 1 and cells[1]:
            steps.append(Step(
                title=clean_text(cells[1], f"events.tsv 第 {row_number} 行步骤标题"),
                status=status,
                seconds=_seconds(cells[0]),
            ))
    return steps, result


def load_events(path: Path) -> tuple[list[Step], str | None]:
    """Read steps/result from events.tsv without retaining free-form log text."""
    try:
        text = path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as error:
        raise DemoPageError(f"events.tsv 不是 UTF-8 文本: {error}") from error
    except OSError as error:
        raise DemoPageError(f"无法读取 events.tsv: {error}") from error

    rows = []
    try:
        for row in csv.reader(io.StringIO(text), delimiter="\t"):
            if any(cell.strip() for cell in row):
                rows.append(row)
    except csv.Error as error:
        raise DemoPageError(f"events.tsv 不是有效 TSV: {error}") from error
    if not rows:
        return [], None

    mapping = _header_mapping(rows[0])
    if mapping is not None:
        return _parse_event_rows(rows[1:], mapping)

    steps, result = _parse_positional_events(rows)
    if not steps and result is None:
        raise DemoPageError(
            "events.tsv 缺少可识别的步骤/结果列；需要 type/step/status 表头"
        )
    return steps, result


def _derive_result(steps: list[Step]) -> str | None:
    if not steps or any(step.status is None for step in steps):
        return None
    statuses = [step.status for step in steps]
    if all(status == "PASS" for status in statuses):
        return "PASS"
    if any(status == "FAIL" for status in statuses):
        return "FAIL"
    if all(status == "SKIP" for status in statuses):
        return "SKIP"
    if all(status in ("TIMEOUT", "ERROR", "UNKNOWN") for status in statuses):
        return statuses[-1]
    return None


def validate_media_dir(media_dir: Path) -> None:
    if not media_dir.exists():
        raise DemoPageError(f"媒体目录不存在: {media_dir}")
    if not media_dir.is_dir():
        raise DemoPageError(f"媒体路径不是目录: {media_dir}")
    missing = [name for name in REQUIRED_MEDIA if not (media_dir / name).is_file()]
    if missing:
        raise DemoPageError(f"媒体目录缺少文件: {', '.join(missing)}")


def load_demo_data(media_dir: Path) -> DemoData:
    manifest = load_manifest(media_dir / "manifest.json")

    values: dict[str, str] = {}
    missing: list[str] = []
    for label, names in (
        ("commit", ("commit", "commit_sha", "commitSha", "sha", "git_commit", "gitCommit")),
        ("node", ("node", "minecraft", "minecraftVersion", "minecraft_version", "mc_version")),
        ("backend", ("backend", "serverBackend", "server_backend")),
    ):
        value = _lookup(manifest, names)
        if value is None:
            missing.append(label)
        else:
            values[label] = clean_text(value, f"manifest.json 字段 {label}")
    if missing:
        raise DemoPageError(f"manifest.json 缺少必填字段: {', '.join(missing)}")

    raw_steps = _lookup(manifest, ("steps", "checks"))
    steps = parse_manifest_steps(raw_steps)
    result = _manifest_result(manifest)

    # Prefer explicit manifest step statuses; only read events.tsv when the
    # manifest cannot supply the missing steps or overall result.
    if result is None and steps:
        result = _derive_result(steps)

    event_steps: list[Step] = []
    event_result: str | None = None
    if not steps or result is None:
        try:
            event_steps, event_result = load_events(media_dir / "events.tsv")
        except DemoPageError as error:
            raise DemoPageError(f"无法从 events.tsv 读取回退数据: {error}") from error
        if not steps:
            steps = event_steps
        if result is None:
            result = event_result

    if result is None:
        result = _derive_result(steps)

    problems = []
    if not steps:
        problems.append("缺少步骤（steps 为空，events.tsv 也没有 STEP 记录）")
    if result is None:
        problems.append("缺少结果（result 缺失，events.tsv 也没有 PASS/FAIL 记录）")
    if problems:
        raise DemoPageError("manifest.json " + "；".join(problems))

    return DemoData(
        commit=values["commit"],
        node=values["node"],
        backend=values["backend"],
        result=result,
        steps=tuple(steps),
        mod_version=_optional_manifest_text(
            manifest, ("modVersion", "mod_version", "mod-version"), "modVersion"
        ),
        scenario=_optional_manifest_text(
            manifest, ("scenario", "scenario_id", "scenarioId"), "scenario"
        ),
        title=_optional_manifest_text(manifest, ("title",), "title"),
    )


def validate_run_id(run_id: str) -> str:
    value = (run_id or "").strip()
    if not RUN_ID_RE.fullmatch(value):
        raise DemoPageError(
            "--run-id 只能包含字母、数字、点、下划线和连字符，"
            "必须以字母或数字开头，且不能包含路径分隔符"
        )
    return value


def validate_repo(repo: str) -> str:
    value = (repo or "").strip()
    if not REPO_RE.fullmatch(value):
        raise DemoPageError("--repo 必须形如 owner/name，例如 is-wuyi/Syncmaterial")
    return value


def normalize_base_url(base_url: str) -> str:
    value = (base_url or "").strip()
    if not value or any(character.isspace() for character in value):
        raise DemoPageError("--base-url 不能为空或包含空白字符")
    if any(character in value for character in '<>"'):
        raise DemoPageError("--base-url 不能包含引号或尖括号")
    parsed = urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise DemoPageError("--base-url 必须是 http(s) 绝对地址")
    if parsed.query or parsed.fragment:
        raise DemoPageError("--base-url 不能带查询参数或片段")
    if parsed.username or parsed.password:
        raise DemoPageError("--base-url 不能包含用户名或密码")
    return value.rstrip("/")


def _format_seconds(value: float | None) -> str | None:
    if value is None:
        return None
    total = max(0, int(round(value)))
    minutes, seconds = divmod(total, 60)
    return f"{minutes:02d}:{seconds:02d}"


def _render_steps(steps: tuple[Step, ...]) -> str:
    items = []
    for index, step in enumerate(steps, start=1):
        meta = []
        if step.status is not None:
            meta.append(
                f'<span class="step-status {status_class(step.status)}">{esc(step.status)}</span>'
            )
        seconds = _format_seconds(step.seconds)
        if seconds is not None:
            meta.append(f'<span class="step-time">{esc(seconds)}</span>')
        meta_html = (
            f'<div class="step-meta">{"".join(meta)}</div>' if meta else ""
        )
        items.append(
            f"""<li class="step">
  <span class="step-number">{index:02d}</span>
  <div class="step-body">
    <p class="step-title">{esc(step.title)}</p>
    {meta_html}
  </div>
</li>"""
        )
    return "\n".join(items)


def _render_facts(data: DemoData, run_id: str) -> str:
    facts = [
        ("commit", f"<code>{esc(data.commit)}</code>"),
        ("node", esc(data.node)),
        ("backend", esc(data.backend)),
    ]
    if data.mod_version:
        facts.append(("Mod 版本", f"<code>{esc(data.mod_version)}</code>"))
    if data.scenario:
        facts.append(("场景", f"<code>{esc(data.scenario)}</code>"))
    facts.append(("运行 ID", f"<code>{esc(run_id)}</code>"))
    return "\n".join(
        f'<div class="fact"><dt>{label}</dt><dd>{value}</dd></div>'
        for label, value in facts
    )


STYLE = """
:root {
  color-scheme: light;
  --bg: #f2f5fb;
  --card: #ffffff;
  --ink: #172033;
  --muted: #667085;
  --line: #e2e8f0;
  --brand: #2457d6;
  --brand-soft: #e9efff;
  --pass: #147a46;
  --pass-soft: #e5f7ed;
  --fail: #b42318;
  --fail-soft: #fdecea;
  --warn: #9a5b00;
  --warn-soft: #fff3dd;
  --shadow: 0 18px 50px rgba(23, 32, 51, .09);
}
* { box-sizing: border-box; }
html { background: var(--bg); }
body {
  margin: 0;
  color: var(--ink);
  background:
    radial-gradient(circle at 10% -8%, #dbe7ff 0, transparent 34rem),
    radial-gradient(circle at 100% 0, #e8f7ef 0, transparent 30rem),
    var(--bg);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans CJK SC",
    "Noto Sans SC", "Microsoft YaHei", sans-serif;
  line-height: 1.65;
}
.page { width: min(1080px, calc(100% - 40px)); margin: 0 auto; padding: 52px 0 64px; }
.hero { margin-bottom: 26px; }
.eyebrow {
  margin: 0 0 10px;
  color: var(--brand);
  font-size: .82rem;
  font-weight: 700;
  letter-spacing: .08em;
  text-transform: uppercase;
}
h1 {
  margin: 0;
  font-size: clamp(2.2rem, 5vw, 3.6rem);
  line-height: 1.12;
  letter-spacing: -.035em;
}
.lede { max-width: 760px; margin: 14px 0 0; color: var(--muted); font-size: 1.06rem; }
.pills { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }
.pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 13px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, .78);
  color: #344054;
  font-size: .88rem;
  font-weight: 650;
}
.pill::before {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #98a2b3;
  content: "";
}
.pill.pass { border-color: #b7e4c8; background: var(--pass-soft); color: var(--pass); }
.pill.pass::before { background: var(--pass); }
.pill.fail { border-color: #f3c3bd; background: var(--fail-soft); color: var(--fail); }
.pill.fail::before { background: var(--fail); }
.pill.warn { border-color: #f1d39b; background: var(--warn-soft); color: var(--warn); }
.pill.warn::before { background: var(--warn); }
.pill.skip, .pill.neutral { background: #f2f4f7; }
.card {
  padding: 24px;
  border: 1px solid rgba(226, 232, 240, .95);
  border-radius: 20px;
  background: rgba(255, 255, 255, .94);
  box-shadow: var(--shadow);
}
.video-card { padding: 20px; }
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
h2 { margin: 0; font-size: 1.18rem; letter-spacing: -.015em; }
.actions { display: flex; flex-wrap: wrap; gap: 10px; }
.button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
  padding: 8px 15px;
  border: 1px solid var(--brand);
  border-radius: 11px;
  background: var(--brand);
  color: #fff;
  font-size: .9rem;
  font-weight: 700;
  text-decoration: none;
}
.button.secondary { border-color: var(--line); background: #fff; color: var(--brand); }
.button:hover { filter: brightness(.96); }
.button:focus-visible, a:focus-visible {
  outline: 3px solid rgba(36, 87, 214, .35);
  outline-offset: 3px;
}
video {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: 14px;
  background: #0b1020;
}
.hint { margin: 12px 2px 0; color: var(--muted); font-size: .86rem; }
.hint a { color: var(--brand); }
.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(270px, .65fr);
  gap: 20px;
  margin-top: 20px;
}
.steps { display: grid; gap: 11px; margin: 0; padding: 0; list-style: none; }
.step {
  display: flex;
  gap: 13px;
  padding: 13px 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fbfcff;
}
.step-number {
  flex: 0 0 auto;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: .78rem;
  font-weight: 800;
}
.step-body { min-width: 0; }
.step-title { margin: 2px 0 0; font-weight: 650; overflow-wrap: anywhere; }
.step-meta { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 7px; }
.step-status, .step-time {
  display: inline-flex;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: .74rem;
  font-weight: 750;
}
.step-time { background: #f2f4f7; color: var(--muted); }
.step-status.pass { background: var(--pass-soft); color: var(--pass); }
.step-status.fail { background: var(--fail-soft); color: var(--fail); }
.step-status.warn { background: var(--warn-soft); color: var(--warn); }
.step-status.skip, .step-status.neutral { background: #f2f4f7; color: #475467; }
.facts { display: grid; gap: 0; margin: 0; }
.fact {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 10px;
  padding: 11px 0;
  border-bottom: 1px solid #edf0f5;
}
.fact:first-child { padding-top: 0; }
.fact:last-child { border-bottom: 0; padding-bottom: 0; }
.fact dt { color: var(--muted); font-size: .82rem; font-weight: 650; }
.fact dd { margin: 0; overflow-wrap: anywhere; font-size: .9rem; }
code {
  padding: 1px 5px;
  border-radius: 5px;
  background: #f3f5f9;
  color: #344054;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: .86em;
}
.preview-card { margin-top: 20px; }
figure { margin: 0; }
.preview-image {
  display: block;
  width: 100%;
  max-width: 800px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #f8fafc;
}
figcaption { margin-top: 9px; color: var(--muted); font-size: .84rem; }
footer { margin-top: 28px; color: var(--muted); font-size: .82rem; text-align: center; }
footer p { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .page { width: min(100% - 28px, 1080px); padding: 30px 0 46px; }
  .card, .video-card { padding: 17px; border-radius: 17px; }
  .content-grid { grid-template-columns: 1fr; }
  .card-head { align-items: flex-start; flex-direction: column; }
  .fact { grid-template-columns: 76px minmax(0, 1fr); }
}
"""


def render_index(data: DemoData, repo: str, run_id: str) -> str:
    lede = (
        data.title
        if data.title and data.title != TITLE
        else "仓库选择、项目引用保存与移除的客户端闭环演示。"
    )
    steps_html = _render_steps(data.steps)
    facts_html = _render_facts(data, run_id)
    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light">
<title>{TITLE}</title>
<style>
{STYLE}
</style>
</head>
<body>
<main class="page">
  <header class="hero">
    <p class="eyebrow">{esc(repo)} · CI 客户端演示</p>
    <h1>{TITLE}</h1>
    <p class="lede">{esc(lede)}</p>
    <div class="pills">
      <span class="pill {status_class(data.result)}">结果 {esc(data.result)}</span>
      <span class="pill">node {esc(data.node)}</span>
      <span class="pill">backend {esc(data.backend)}</span>
    </div>
  </header>

  <section class="card video-card" aria-labelledby="video-title">
    <div class="card-head">
      <h2 id="video-title">演示视频</h2>
      <div class="actions">
        <a href="raw.mp4" download class="button">原始素材下载</a>
        <a href="demo.mp4" download class="button secondary">下载演示 MP4</a>
      </div>
    </div>
    <video src="demo.mp4" controls playsinline preload="metadata" poster="poster.png"
           width="1280" height="720" aria-label="仓库引用操作演示视频">
      <source src="demo.mp4" type="video/mp4">
      您的浏览器不支持 HTML5 视频，请<a href="demo.mp4">下载演示 MP4</a>。
    </video>
    <p class="hint">原始素材为未叠加字幕的 <a href="raw.mp4" download>raw.mp4</a>。</p>
  </section>

  <div class="content-grid">
    <section class="card" aria-labelledby="steps-title">
      <h2 id="steps-title">操作步骤</h2>
      <ol class="steps">
{steps_html}
      </ol>
    </section>

    <aside class="card" aria-labelledby="facts-title">
      <h2 id="facts-title">运行信息</h2>
      <dl class="facts">
{facts_html}
      </dl>
    </aside>
  </div>

  <section class="card preview-card" aria-labelledby="preview-title">
    <h2 id="preview-title">GIF 预览</h2>
    <figure>
      <img src="preview.gif" alt="仓库引用操作演示预览" class="preview-image"
           width="800" decoding="async">
      <figcaption>GIF 预览 · 完整过程请观看上方视频。</figcaption>
    </figure>
  </section>

  <footer>
    <p>{esc(repo)} · 运行 {esc(run_id)} · 由 CI 离线生成</p>
  </footer>
</main>
</body>
</html>
"""


def _code_span(value: str) -> str:
    safe = esc(value)
    if "`" in safe:
        return f"`` {safe} ``"
    return f"`{safe}`"


def render_summary(data: DemoData, base_url: str, run_id: str) -> str:
    base = base_url.rstrip("/")
    run_url = f"{base}/demos/{quote(run_id, safe='')}/"
    preview_url = f"{run_url}preview.gif?v={quote(run_id, safe='')}"
    lines = [
        f"[![{TITLE}]({preview_url})]({run_url})",
        "",
        f"**{TITLE}**",
        "",
        (
            f"结果：{_code_span(data.result)} · "
            f"commit：{_code_span(data.commit)} · "
            f"node：{_code_span(data.node)} · "
            f"backend：{_code_span(data.backend)}"
        ),
    ]
    if data.mod_version:
        lines.append(f"mod 版本：{_code_span(data.mod_version)}")
    if data.scenario:
        lines.append(f"场景：{_code_span(data.scenario)}")
    lines.extend([
        "",
        f"[观看完整MP4]({run_url}demo.mp4) · [原始素材下载]({run_url}raw.mp4)",
        "",
    ])
    return "\n".join(lines)


def _write_text_atomic(path: Path, text: str) -> None:
    try:
        descriptor, temporary = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
        )
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
                stream.write(text)
            os.replace(temporary, path)
        except BaseException:
            try:
                os.unlink(temporary)
            except OSError:
                pass
            raise
    except OSError as error:
        raise DemoPageError(f"无法写入 {path.name}: {error}") from error


def _copy_media_atomic(source: Path, destination: Path) -> None:
    if source.resolve() == destination.resolve():
        return
    temporary = None
    try:
        descriptor, temporary = tempfile.mkstemp(
            prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent
        )
        os.close(descriptor)
        shutil.copyfile(source, temporary)
        os.replace(temporary, destination)
    except OSError as error:
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass
        raise DemoPageError(f"无法复制媒体文件 {source.name}: {error}") from error


def _prepare_run_dir(out_dir: Path, run_id: str) -> Path:
    if out_dir.exists() and not out_dir.is_dir():
        raise DemoPageError(f"--out 不是目录: {out_dir}")
    demos_dir = out_dir / "demos"
    if demos_dir.exists() and not demos_dir.is_dir():
        raise DemoPageError(f"站点目录中的 demos 不是目录: {demos_dir}")
    try:
        demos_dir.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise DemoPageError(f"无法创建站点目录 {demos_dir}: {error}") from error

    run_dir = demos_dir / run_id
    if run_dir.is_symlink():
        raise DemoPageError(f"运行目录不能是符号链接: {run_dir}")
    if run_dir.exists() and not run_dir.is_dir():
        raise DemoPageError(f"运行目录已被非目录文件占用: {run_dir}")
    try:
        run_dir.mkdir(exist_ok=True)
    except OSError as error:
        raise DemoPageError(f"无法创建运行目录 {run_dir}: {error}") from error
    if run_dir.resolve().parent != demos_dir.resolve():
        raise DemoPageError(f"运行目录越出 demos 目录: {run_dir}")
    return run_dir


def build_demo_page(media_dir, out_dir, run_id: str,
                    repo: str = DEFAULT_REPO,
                    base_url: str = DEFAULT_BASE_URL) -> tuple[Path, Path]:
    media = Path(media_dir).expanduser().resolve()
    out = Path(out_dir).expanduser().resolve()
    run_id = validate_run_id(run_id)
    repo = validate_repo(repo)
    base = normalize_base_url(base_url)

    validate_media_dir(media)
    data = load_demo_data(media)
    index_html = render_index(data, repo, run_id)
    summary_md = render_summary(data, base, run_id)

    run_dir = _prepare_run_dir(out, run_id)
    for name in COPY_MEDIA:
        _copy_media_atomic(media / name, run_dir / name)
    index_path = run_dir / "index.html"
    summary_path = run_dir / "summary.md"
    _write_text_atomic(index_path, index_html)
    _write_text_atomic(summary_path, summary_md)
    return index_path, summary_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="build_demo_page.py",
        description="离线生成 SyncMaterial 仓库引用演示页与 GitHub step summary。",
        epilog=(
            "示例:\n"
            "  python3 -I .github/scripts/build_demo_page.py \\\n"
            "    --media demo-out --out site --run-id 123456789 \\\n"
            "    --repo is-wuyi/Syncmaterial \\\n"
            "    --base-url https://is-wuyi.github.io/Syncmaterial\n\n"
            "只读取 manifest.json 与 events.tsv 的步骤/结果字段；不复制原始日志，\n"
            "不修改其他 run 目录。"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--media", required=True,
                        help="媒体目录（含 raw.mp4/demo.mp4/preview.gif/poster.png/events.tsv/manifest.json）")
    parser.add_argument("--out", required=True, help="站点输出目录")
    parser.add_argument("--run-id", required=True, help="GitHub 运行 ID，用作 demos/<run-id> 目录名")
    parser.add_argument("--repo", default=DEFAULT_REPO,
                        help=f"仓库标识，默认 {DEFAULT_REPO}")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help=f"站点根地址，默认 {DEFAULT_BASE_URL}")
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        index_path, summary_path = build_demo_page(
            media_dir=args.media,
            out_dir=args.out,
            run_id=args.run_id,
            repo=args.repo,
            base_url=args.base_url,
        )
    except DemoPageError as error:
        print(f"错误: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"错误: 文件操作失败: {error}", file=sys.stderr)
        return 2
    print(f"已生成演示页: {index_path}")
    print(f"已生成摘要: {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
