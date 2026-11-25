#!/usr/bin/env python3
"""Regression tests for the client demo recorder (no Gradle or ffmpeg runs)."""

import importlib.util
import json
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "record_client_demo", ROOT / ".github/scripts/record_client_demo.py")
DEMO = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = DEMO
SPEC.loader.exec_module(DEMO)

SCENARIO = DEMO.load_scenario("warehouse-ref-popup")
TARGET = SCENARIO.target_class
WALL_BASE = 1_700_000_000.0
WALL_BASE_MS = int(WALL_BASE * 1000)
XWININFO_OUTPUT = (
    '0x1 "Desktop": ("desktop" "Desktop")  1920x1080+0+0  +0+0\n'
    '     0x2 "Minecraft 1.21.8": ("java" "Java")  1280x720+10+20  +10+20\n'
)


class Completed:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class FakeProcess:
    def __init__(self, polls=(0,), returncode=None):
        self.polls = list(polls)
        self.returncode = returncode
        self.signals = []
        self.waits = 0
        self.terminated = False
        self.killed = False

    def poll(self):
        if self.polls:
            value = self.polls.pop(0)
            if value is not None:
                self.returncode = value
            return value
        return self.returncode

    def send_signal(self, sig):
        self.signals.append(sig)
        if sig == signal.SIGINT:
            self.returncode = 0
            self.polls.clear()

    def wait(self, timeout=None):
        self.waits += 1
        return self.returncode

    def terminate(self):
        self.terminated = True
        self.returncode = -signal.SIGTERM
        self.polls.clear()

    def kill(self):
        self.killed = True
        self.returncode = -signal.SIGKILL
        self.polls.clear()


class FakeSpawn:
    def __init__(self, log_path, log_text, tmp_path, gradle_polls=(0,),
                 fail_gradle=False):
        self.log_path = log_path
        self.log_text = log_text
        self.tmp_path = Path(tmp_path)
        self.gradle_polls = tuple(gradle_polls)
        self.fail_gradle = fail_gradle
        self.calls = []
        self.gradle_process = None
        self.capture_process = None

    def __call__(self, command, **kwargs):
        command = [str(value) for value in command]
        self.calls.append({"command": command, "kwargs": kwargs})
        name = Path(command[0]).name
        if name in ("gradlew", "gradle"):
            if self.fail_gradle:
                raise FileNotFoundError("gradlew not found")
            self.log_path.parent.mkdir(parents=True, exist_ok=True)
            self.log_path.write_text(self.log_text, encoding="utf-8")
            self.gradle_process = FakeProcess(polls=self.gradle_polls)
            return self.gradle_process
        if name == "ffmpeg":
            raw = Path(kwargs.get("cwd", self.tmp_path)) / "raw.mkv"
            raw.write_bytes(b"raw-mkv")
            self.capture_process = FakeProcess(polls=(None,), returncode=None)
            return self.capture_process
        return FakeProcess(polls=(None,), returncode=0)


class FakeRun:
    def __init__(self, xwininfo_output=XWININFO_OUTPUT, fail_media=False,
                 timeout_media=False, commit="deadbeefcafebabe", black_media=False):
        self.xwininfo_output = xwininfo_output
        self.fail_media = fail_media
        self.timeout_media = timeout_media
        self.commit = commit
        self.black_media = black_media
        self.calls = []

    def __call__(self, command, **kwargs):
        command = [str(value) for value in command]
        self.calls.append({"command": command, "kwargs": kwargs})
        name = Path(command[0]).name
        if name == "xwininfo":
            return Completed(0, stdout=self.xwininfo_output)
        if name == "git":
            return Completed(0, stdout=self.commit + "\n")
        if name == "ffmpeg":
            if self.black_media and any("blackdetect" in argument for argument in command):
                return Completed(0, stderr="black_start:0 black_end:7.5 black_duration:7.5")
            if self.timeout_media:
                raise subprocess.TimeoutExpired(command, 300)
            if self.fail_media:
                return Completed(1, stderr="simulated ffmpeg failure")
            output = Path(command[-1])
            output.parent.mkdir(parents=True, exist_ok=True)
            if output.suffix == ".gif":
                output.write_bytes(b"gif")
            elif output.suffix == ".png":
                output.write_bytes(b"png")
            elif output.name == "raw.mp4":
                output.write_bytes(b"raw-mp4")
            elif output.suffix == ".mp4":
                output.write_bytes(b"demo-mp4")
            return Completed(0)
        return Completed(0)


class FakeClock:
    def __init__(self):
        self.monotonic_value = 1000.0
        self.wall_value = WALL_BASE
        self.sleeps = 0
        self.on_sleep = None

    def now(self):
        return self.monotonic_value

    def wall_now(self):
        return self.wall_value

    def sleep(self, seconds):
        self.monotonic_value += seconds
        self.wall_value += seconds
        self.sleeps += 1
        if self.on_sleep is not None:
            self.on_sleep(self.sleeps)


class FakeDisplay:
    def __init__(self, name=":99"):
        self.name = name
        self.width = 1920
        self.height = 1080
        self.reused = False
        self.stopped = False

    def stop(self):
        self.stopped = True


class Harness:
    def __init__(self, directory, log_text, gradle_polls=(0,), fail_gradle=False,
                 fail_media=False, timeout_media=False,
                 xwininfo_output=XWININFO_OUTPUT, timeout=30.0, black_media=False):
        self.tmp = Path(directory)
        self.run_dir = self.tmp / "run"
        self.out = self.tmp / "out"
        self.log_path = self.run_dir / "logs" / "latest.log"
        self.clock = FakeClock()
        self.display = FakeDisplay()
        self.spawn = FakeSpawn(self.log_path, log_text, self.tmp,
                               gradle_polls=gradle_polls, fail_gradle=fail_gradle)
        self.run = FakeRun(xwininfo_output=xwininfo_output, fail_media=fail_media,
                           timeout_media=timeout_media, black_media=black_media)
        self.recorder = DEMO.ClientDemoRecorder(
            run_dir=self.run_dir,
            out=self.out,
            node="1.21.8",
            backend="legacy",
            scenario=SCENARIO,
            title="测试标题",
            gradle_args=[":1.21.8:runClientGameTest"],
            timeout_seconds=timeout,
            repo_root=ROOT,
            spawn=self.spawn,
            run=self.run,
            which=lambda name: {"ffmpeg": "/usr/bin/ffmpeg",
                                "xwininfo": "/usr/bin/xwininfo",
                                "git": "/usr/bin/git"}.get(name),
            clock=self.clock.now,
            wall_clock=self.clock.wall_now,
            sleep=self.clock.sleep,
            environ={"GITHUB_RUN_ID": "12345"},
            display_factory=lambda **_: self.display,
        )

    def manifest(self):
        return json.loads((self.out / "manifest.json").read_text(encoding="utf-8"))


def tag(status, class_name=TARGET):
    return f"SYNCMATERIAL_CLIENT_TEST_{status} syncmaterial-test {class_name}"


def step(step_id, offset_ms):
    return f"SYNCMATERIAL_DEMO_STEP {step_id} {WALL_BASE_MS + offset_ms}"


def make_harness(test, log_text, **kwargs):
    directory = tempfile.TemporaryDirectory()
    test.addCleanup(directory.cleanup)
    return Harness(directory.name, log_text, **kwargs)


class CaptureLifecycleTest(unittest.TestCase):
    def test_display_cleanup_never_signals_the_recorders_process_group(self):
        process = FakeProcess(polls=(None, 0))
        with mock.patch.object(DEMO.os, "killpg",
                               side_effect=AssertionError("must not signal parent group")):
            DEMO.Display(":99", process).stop()
        self.assertTrue(process.terminated)

    def test_capture_timeout_cleanup_never_signals_the_recorders_process_group(self):
        process = FakeProcess(polls=(None,))
        process.wait = mock.Mock(side_effect=[subprocess.TimeoutExpired("ffmpeg", 20), -15])
        with mock.patch.object(DEMO.os, "killpg",
                               side_effect=AssertionError("must not signal parent group")):
            DEMO._terminate_process(process, grace=0.1, group=False)
        self.assertTrue(process.terminated)

    def test_start_after_gradle_launch_starts_ffmpeg(self):
        harness = make_harness(self, tag("START") + "\n")
        code = harness.recorder.run()
        self.assertEqual(0, code)
        names = [Path(call["command"][0]).name for call in harness.spawn.calls]
        self.assertIn("gradlew", names)
        self.assertIn("ffmpeg", names)
        self.assertLess(names.index("gradlew"), names.index("ffmpeg"))
        gradle_call = next(call for call in harness.spawn.calls
                           if Path(call["command"][0]).name == "gradlew")
        self.assertEqual("1", gradle_call["kwargs"]["env"]["SYNCMATERIAL_EXTERNAL_XVFB"])
        capture = next(call["command"] for call in harness.spawn.calls
                       if Path(call["command"][0]).name == "ffmpeg")
        self.assertIn("x11grab", capture)
        self.assertEqual("1280x720", capture[capture.index("-video_size") + 1])
        self.assertEqual(":99+10,20", capture[capture.index("-i") + 1])
        self.assertTrue((harness.out / "raw.mkv").is_file())

    def test_ffmpeg_does_not_start_before_start_tag(self):
        harness = make_harness(self, "noise\n", gradle_polls=(None, 0))
        before = []

        def after_sleep(count):
            if count == 1 and not before:
                before.append([Path(call["command"][0]).name
                               for call in harness.spawn.calls])
                with harness.log_path.open("a", encoding="utf-8") as stream:
                    stream.write(tag("START") + "\n")

        harness.clock.on_sleep = after_sleep
        self.assertEqual(0, harness.recorder.run())
        self.assertEqual(1, len(before))
        self.assertNotIn("ffmpeg", before[0])
        names = [Path(call["command"][0]).name for call in harness.spawn.calls]
        self.assertIn("ffmpeg", names)

    def test_pass_stops_capture_with_sigint(self):
        harness = make_harness(self, tag("START") + "\n" + tag("PASS") + "\n")
        code = harness.recorder.run()
        self.assertEqual(0, code)
        self.assertEqual([signal.SIGINT], harness.spawn.capture_process.signals)
        self.assertEqual("PASS", harness.manifest()["result"])

    def test_fail_keeps_recording_and_preserves_gradle_exit(self):
        harness = make_harness(self, tag("START") + "\n" + tag("FAIL") + "\n",
                               gradle_polls=(3,))
        code = harness.recorder.run()
        self.assertEqual(3, code)
        self.assertEqual([signal.SIGINT], harness.spawn.capture_process.signals)
        self.assertEqual("FAIL", harness.manifest()["result"])
        self.assertTrue((harness.out / "raw.mkv").is_file())
        self.assertTrue((harness.out / "demo.mp4").is_file())

    def test_timeout_hard_stops_gradle_and_capture(self):
        harness = make_harness(self, tag("START") + "\n", gradle_polls=(), timeout=1.0)
        code = harness.recorder.run()
        self.assertEqual(124, code)
        self.assertTrue(harness.spawn.gradle_process.terminated)
        self.assertEqual([signal.SIGINT], harness.spawn.capture_process.signals)
        manifest = harness.manifest()
        self.assertTrue(manifest["timedOut"])
        self.assertEqual("TIMEOUT", manifest["result"])
        self.assertEqual(124, manifest["gradleExitCode"])

    def test_missing_start_keeps_success_exit_without_capture(self):
        harness = make_harness(self, "unrelated log line\n")
        code = harness.recorder.run()
        self.assertEqual(0, code)
        names = [Path(call["command"][0]).name for call in harness.spawn.calls]
        self.assertNotIn("ffmpeg", names)
        manifest = harness.manifest()
        self.assertEqual("UNKNOWN", manifest["result"])
        self.assertEqual("skipped", manifest["media"]["status"])
        self.assertNotIn("raw.mkv", manifest["files"])

    def test_other_client_test_does_not_start_recording(self):
        log = (tag("START", "other.package.OtherClientGameTest") + "\n"
               + tag("PASS", "other.package.OtherClientGameTest") + "\n")
        harness = make_harness(self, log)
        self.assertEqual(0, harness.recorder.run())
        names = [Path(call["command"][0]).name for call in harness.spawn.calls]
        self.assertNotIn("ffmpeg", names)
        self.assertEqual("UNKNOWN", harness.manifest()["result"])

    def test_xwininfo_failure_falls_back_to_whole_display(self):
        harness = make_harness(self, tag("START") + "\n", xwininfo_output="")
        code = harness.recorder.run()
        self.assertEqual(0, code)
        capture = next(call["command"] for call in harness.spawn.calls
                       if Path(call["command"][0]).name == "ffmpeg")
        self.assertEqual("1920x1080", capture[capture.index("-video_size") + 1])
        self.assertEqual(":99", capture[capture.index("-i") + 1])
        self.assertTrue(harness.manifest()["capture"]["fallbackDisplay"])
        self.assertEqual("passed", harness.manifest()["capture"]["contentCheck"])

    def test_window_diagnostics_are_bounded_and_contain_no_raw_log(self):
        harness = make_harness(self, tag("START") + "\n")
        self.assertEqual(0, harness.recorder.run())
        candidates = harness.manifest()["capture"]["windowCandidates"]
        self.assertIn("Minecraft 1.21.8", [entry["title"] for entry in candidates])
        self.assertNotIn("latest.log", (harness.out / "manifest.json").read_text())


class ManifestAndMediaTest(unittest.TestCase):
    def test_manifest_step_order_and_titles(self):
        log = "\n".join([
            tag("START"),
            step("open-popup", 100),
            step("select-warehouse", 200),
            step("reference-added", 300),
            step("remove-reference", 400),
            step("reference-removed", 500),
            step("demo-complete", 600),
            step("unknown-step", 700),
            tag("PASS"),
        ]) + "\n"
        harness = make_harness(self, log)
        self.assertEqual(0, harness.recorder.run())
        manifest = harness.manifest()
        self.assertEqual(["open-popup", "select-warehouse", "reference-added",
                          "remove-reference", "reference-removed", "demo-complete",
                          "unknown-step"],
                         [entry["id"] for entry in manifest["steps"]])
        self.assertEqual(["打开仓库引用弹窗", "选择仓库并保存项目关联",
                          "仓库关联已保存并经服务端校验", "移除项目仓库引用",
                          "项目仓库引用已移除", "仓库引用弹窗闭环完成",
                          "unknown-step"],
                         [entry["title"] for entry in manifest["steps"]])
        events = (harness.out / "events.tsv").read_text(encoding="utf-8").splitlines()
        kinds = [line.split("\t")[0] for line in events[1:]]
        self.assertEqual(["START"] + ["STEP"] * 7 + ["PASS"], kinds)
        expected = {"commit", "run", "node", "backend", "modVersion", "scenario",
                    "result", "files"}
        self.assertTrue(expected.issubset(manifest))
        self.assertEqual("deadbeefcafebabe", manifest["commit"])
        self.assertEqual("12345", manifest["run"])
        self.assertEqual("1.21.8", manifest["node"])
        self.assertEqual("legacy", manifest["backend"])
        self.assertEqual("warehouse-ref-popup", manifest["scenario"])
        self.assertEqual("测试标题", manifest["title"])
        self.assertTrue(manifest["modVersion"])
        for entry in manifest["files"].values():
            self.assertGreater(entry["bytes"], 0)
            self.assertEqual(64, len(entry["sha256"]))

    def test_black_capture_is_kept_for_diagnosis_but_not_publishable(self):
        harness = make_harness(self, tag("START") + "\n" + tag("PASS") + "\n",
                               timeout=7.5, black_media=True)
        self.assertEqual(0, harness.recorder.run())
        manifest = harness.manifest()
        self.assertEqual("invalid", manifest["media"]["status"])
        self.assertEqual("failed", manifest["capture"]["contentCheck"])
        self.assertTrue((harness.out / "raw.mp4").is_file())
        self.assertFalse((harness.out / "demo.mp4").exists())
        self.assertFalse((harness.out / "preview.gif").exists())

    def test_raw_mp4_is_not_overwritten_by_overlay(self):
        harness = make_harness(self, tag("START") + "\n" + tag("PASS") + "\n")
        self.assertEqual(0, harness.recorder.run())
        self.assertEqual(b"raw-mp4", (harness.out / "raw.mp4").read_bytes())
        self.assertEqual(b"demo-mp4", (harness.out / "demo.mp4").read_bytes())
        self.assertTrue((harness.out / "raw.mkv").is_file())
        overlay = next(call["command"] for call in harness.run.calls
                       if "-i" in call["command"]
                       and call["command"][call["command"].index("-i") + 1].endswith("raw.mp4")
                       and call["command"][-1].endswith("demo.mp4"))
        source = overlay[overlay.index("-i") + 1]
        self.assertTrue(source.endswith("raw.mp4"))
        self.assertTrue(overlay[-1].endswith("demo.mp4"))

    def test_gif_commands_match_duration_size_and_rate(self):
        palette_cmd, gif_cmd = DEMO.build_gif_commands(
            "/usr/bin/ffmpeg", "demo.mp4", "palette.png", "preview.gif", 12.0, 15.0)
        self.assertIn("-t", palette_cmd)
        self.assertEqual("15.000", palette_cmd[palette_cmd.index("-t") + 1])
        palette_filter = palette_cmd[palette_cmd.index("-vf") + 1]
        self.assertIn("fps=10", palette_filter)
        self.assertIn("scale=800:-2", palette_filter)
        self.assertIn("palettegen", palette_filter)
        self.assertEqual("-loop", gif_cmd[-3])
        self.assertEqual("0", gif_cmd[-2])
        self.assertIn("paletteuse", gif_cmd[gif_cmd.index("-lavfi") + 1])
        self.assertEqual(20.0, DEMO.preview_clip(60.0))
        self.assertEqual(8.0, DEMO.preview_clip(2.0))

    def test_media_failure_only_warns_and_keeps_zero_exit(self):
        harness = make_harness(self, tag("START") + "\n" + tag("PASS") + "\n",
                               fail_media=True)
        code = harness.recorder.run()
        self.assertEqual(0, code)
        manifest = harness.manifest()
        self.assertEqual("PASS", manifest["result"])
        self.assertTrue(manifest["media"]["warnings"])
        self.assertFalse((harness.out / "demo.mp4").exists())
        self.assertNotIn("latest.log", [path.name for path in harness.out.iterdir()])

    def test_media_timeout_is_warned_and_does_not_fail_passing_test(self):
        harness = make_harness(self, tag("START") + "\n" + tag("PASS") + "\n",
                               timeout_media=True)
        code = harness.recorder.run()
        self.assertEqual(0, code)
        manifest = harness.manifest()
        self.assertEqual("PASS", manifest["result"])
        self.assertTrue(any("媒体命令超过" in warning
                            for warning in manifest["media"]["warnings"]))
        self.assertFalse((harness.out / "raw.mp4").exists())

    def test_gradle_nonzero_exit_is_preserved_when_media_succeeds(self):
        harness = make_harness(self, tag("START") + "\n" + tag("PASS") + "\n",
                               gradle_polls=(7,))
        self.assertEqual(7, harness.recorder.run())

    def test_unlaunchable_gradle_is_failure(self):
        harness = make_harness(self, "", fail_gradle=True)
        code = harness.recorder.run()
        self.assertNotEqual(0, code)
        manifest = harness.manifest()
        self.assertEqual("ERROR", manifest["result"])
        self.assertTrue(any("无法启动 Gradle" in warning
                            for warning in manifest["media"]["warnings"]))


class PureHelperTest(unittest.TestCase):
    def test_parse_xwininfo_tree_uses_absolute_minecraft_geometry(self):
        text = (
            '0x1 "Firefox": ("firefox" "Firefox")  800x600+0+0  +0+0\n'
            '0x2 "Minecraft 1.21.8": ("java" "Java")  1280x720+10+20  +10+20\n'
            '0x3 "Minecraft 1.21.8": ("java" "Java")  640x480+500+500  +500+500\n'
        )
        self.assertEqual((10, 20, 1280, 720), DEMO.parse_xwininfo_tree(text))

    def test_parse_xwininfo_without_absolute_position_falls_back(self):
        text = '0x2 "Minecraft 1.21.8": ("java" "Java")  1280x720+10+20\n'
        self.assertIsNone(DEMO.parse_xwininfo_tree(text))

    def test_drawtext_filter_uses_textfile_and_quoted_enable_window(self):
        value = DEMO.drawtext_filter(
            "step-1.txt", fontfile="/usr/share/fonts/noto/NotoSansCJK-Regular.ttc",
            fontsize=26, enable="between(t,1.000,2.500)")
        self.assertIn("fontfile='/usr/share/fonts/noto/NotoSansCJK-Regular.ttc'", value)
        self.assertIn("textfile='step-1.txt'", value)
        self.assertIn("fontsize=26", value)
        self.assertIn("expansion=none", value)
        self.assertIn("enable='between(t,1.000,2.500)'", value)

    def test_cli_accepts_repeatable_and_remainder_gradle_args(self):
        parser = DEMO.build_parser()
        base = ["--run-dir", "runs", "--out", "demo", "--node", "1.21.8",
                "--backend", "legacy"]
        args = parser.parse_args(base + ["--gradle-arg=:1.21.8:runClientGameTest",
                                         "--gradle-arg=--no-build-cache"])
        self.assertEqual([":1.21.8:runClientGameTest", "--no-build-cache"],
                         DEMO.collect_gradle_args(args))
        args = parser.parse_args(DEMO.normalize_argv(
            base + ["--gradle-arg", ":1.21.8:runClientGameTest",
                    "--gradle-arg", "--no-build-cache"]))
        self.assertEqual([":1.21.8:runClientGameTest", "--no-build-cache"],
                         DEMO.collect_gradle_args(args))
        args = parser.parse_args(base + ["--", "--no-build-cache", "--stacktrace"])
        self.assertEqual(["--no-build-cache", "--stacktrace"],
                         DEMO.collect_gradle_args(args))
        args = parser.parse_args(DEMO.normalize_argv(
            base + ["--", "--gradle-arg", "--no-build-cache"]))
        self.assertEqual(["--gradle-arg", "--no-build-cache"],
                         DEMO.collect_gradle_args(args))
        args = parser.parse_args(base)
        self.assertEqual([":1.21.8:runClientGameTest", "--no-build-cache", "--stacktrace"],
                         DEMO.collect_gradle_args(args))

    def test_unknown_scenario_is_rejected(self):
        with self.assertRaises(DEMO.DemoError):
            DEMO.load_scenario("does-not-exist")

    def test_log_tail_skips_stale_lines_and_recovers_from_truncation(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "latest.log"
        path.write_text("stale START\n", encoding="utf-8")
        tail = DEMO.LogTail(path)
        tail.skip_existing()
        with path.open("a", encoding="utf-8") as stream:
            stream.write("fresh PASS\n")
        self.assertEqual(["fresh PASS"], tail.read())
        path.write_text("truncated\n", encoding="utf-8")
        self.assertEqual(["truncated"], tail.read())


if __name__ == "__main__":
    unittest.main()
