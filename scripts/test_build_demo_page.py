#!/usr/bin/env python3
"""Offline stdlib regression tests for the demo page generator."""

import contextlib
import html
import importlib.util
import io
import json
import os
import re
import sys
import tempfile
import unittest
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "build_demo_page", ROOT / ".github/scripts/build_demo_page.py"
)
PAGE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PAGE
SPEC.loader.exec_module(PAGE)

DEFAULT_MANIFEST = {
    "schema": 1,
    "commit": "abc123def456",
    "run": "4242",
    "node": "1.21.8",
    "backend": "legacy",
    "modVersion": "0.3.0-beta.63",
    "scenario": "warehouse-ref-popup",
    "title": "仓库引用弹窗闭环演示",
    "result": "PASS",
    "steps": [
        {"index": 1, "id": "editor-open", "title": "打开备货区编辑器",
         "epochMs": 1200, "videoSeconds": 0.2},
        {"index": 2, "id": "popup-select", "title": "选择仓库并保存项目关联",
         "epochMs": 2400, "videoSeconds": 1.4},
    ],
}

DEFAULT_EVENTS = """type\tstep_id\ttitle\tepoch_ms\tvideo_seconds
START\t\t\t1000\t0.000
STEP\teditor-open\t打开备货区编辑器\t1200\t0.200
PASS\t\t\t3000\t2.000
"""


def manifest_copy():
    return json.loads(json.dumps(DEFAULT_MANIFEST, ensure_ascii=False))


def write_bundle(root, manifest=None, events=DEFAULT_EVENTS, missing=()):
    root = Path(root)
    media = root / "media"
    out = root / "site"
    media.mkdir(parents=True, exist_ok=True)
    files = {
        "raw.mp4": b"raw-video-bytes",
        "demo.mp4": b"demo-video-bytes",
        "preview.gif": b"GIF89a-preview",
        "poster.png": b"\x89PNG\r\n\x1a\nposter",
    }
    for name, data in files.items():
        if name not in missing:
            (media / name).write_bytes(data)
    if "events.tsv" not in missing:
        (media / "events.tsv").write_text(events, encoding="utf-8")
    if "manifest.json" not in missing:
        value = manifest_copy() if manifest is None else manifest
        (media / "manifest.json").write_text(
            json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    return media, out


def invoke(media, out, run_id="4242", **kwargs):
    stdout = io.StringIO()
    stderr = io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        code = PAGE.main([
            "--media", str(media),
            "--out", str(out),
            "--run-id", run_id,
            *sum(([f"--{key.replace('_', '-')}", str(value)]
                  for key, value in kwargs.items()), []),
        ])
    return code, stdout.getvalue(), stderr.getvalue()


class ReferenceParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.references = []

    def handle_starttag(self, tag, attrs):
        for name, value in attrs:
            if name in ("href", "src", "poster") and value is not None:
                self.references.append(value)


class BuildDemoPageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.media, self.out = write_bundle(self.root)
        self.run_dir = self.out / "demos" / "4242"

    def build(self, **kwargs):
        return invoke(self.media, self.out, **kwargs)

    def read_page(self):
        return (self.run_dir / "index.html").read_text(encoding="utf-8")

    def read_summary(self):
        return (self.run_dir / "summary.md").read_text(encoding="utf-8")

    def assert_within(self, reference, base):
        path = (base / reference).resolve()
        try:
            path.relative_to(base.resolve())
        except ValueError:
            self.fail(f"reference escapes run directory: {reference}")

    def test_generates_page_media_and_confined_relative_links(self):
        code, stdout, stderr = self.build()
        self.assertEqual(0, code, stderr)
        self.assertEqual("", stderr)
        self.assertIn("已生成演示页", stdout)
        page = self.read_page()
        self.assertIn("<title>仓库引用操作演示</title>", page)
        self.assertIn("仓库引用操作演示", page)
        self.assertIn("abc123def456", page)
        self.assertIn("1.21.8", page)
        self.assertIn("legacy", page)
        self.assertIn("PASS", page)
        self.assertIn("打开备货区编辑器", page)
        self.assertNotIn("<script", page.lower())
        for name in PAGE.COPY_MEDIA:
            self.assertTrue((self.run_dir / name).is_file(), name)
        self.assertFalse((self.run_dir / "manifest.json").exists())
        self.assertFalse((self.run_dir / "events.tsv").exists())
        self.assertNotIn(str(self.media), page)
        self.assertNotIn(str(self.out), page)
        self.assertNotIn("file://", page)

        parser = ReferenceParser()
        parser.feed(page)
        self.assertTrue(parser.references)
        self.assertIn("demo.mp4", parser.references)
        self.assertIn("raw.mp4", parser.references)
        self.assertIn("preview.gif", parser.references)
        self.assertIn("poster.png", parser.references)
        for reference in parser.references:
            self.assertFalse(reference.startswith(("http://", "https://", "//", "/")), reference)
            self.assertNotIn("..", reference)
            self.assert_within(reference, self.run_dir)

    def test_video_contract(self):
        self.assertEqual(0, self.build()[0])
        page = self.read_page()
        match = re.search(r"<video\b([^>]*)>", page, re.DOTALL)
        self.assertIsNotNone(match)
        attributes = match.group(1)
        self.assertRegex(attributes, r"\bcontrols\b")
        self.assertRegex(attributes, r"\bplaysinline\b")
        self.assertRegex(attributes, r'\bpreload="metadata"')
        self.assertRegex(attributes, r'\bposter="poster\.png"')
        self.assertRegex(page, r'<source\s+src="demo\.mp4"\s+type="video/mp4">')

    def test_preview_gif_and_raw_download(self):
        self.assertEqual(0, self.build()[0])
        page = self.read_page()
        self.assertRegex(page, r'<img\b[^>]*src="preview\.gif"')
        self.assertRegex(page, r'<a\b[^>]*href="raw\.mp4"[^>]*\bdownload\b')

    def test_manifest_text_is_escaped_everywhere(self):
        manifest = manifest_copy()
        manifest["commit"] = '<script>alert("commit")</script>'
        manifest["node"] = "1.21.8 & <b>node</b>"
        manifest["backend"] = 'legacy"><img src=x onerror=alert(1)>'
        manifest["title"] = '<svg onload=alert("title")>'
        manifest["steps"][0]["title"] = "</li><script>bad()</script>"
        media, out = write_bundle(self.root / "escape", manifest=manifest)
        code, _, stderr = invoke(media, out)
        self.assertEqual(0, code, stderr)
        run_dir = out / "demos" / "4242"
        page = (run_dir / "index.html").read_text(encoding="utf-8")
        summary = (run_dir / "summary.md").read_text(encoding="utf-8")

        self.assertNotIn("<script", page.lower())
        self.assertNotIn("<svg", page.lower())
        self.assertNotIn("<img src=x", page.lower())
        self.assertNotIn("<script", summary.lower())
        self.assertIn(html.escape('<script>alert("commit")</script>', quote=True), page)
        self.assertIn("&lt;b&gt;node&lt;/b&gt;", page)
        self.assertIn("&lt;/li&gt;&lt;script&gt;bad()&lt;/script&gt;", page)
        self.assertIn("&lt;svg onload=alert(&quot;title&quot;)&gt;", page)

    def test_summary_contains_required_preview_and_download_links(self):
        self.assertEqual(0, self.build()[0])
        summary = self.read_summary()
        base = "https://is-wuyi.github.io/Syncmaterial"
        run_url = f"{base}/demos/4242/"
        first_line = f"[![仓库引用操作演示]({run_url}preview.gif?v=4242)]({run_url})"
        self.assertTrue(summary.startswith(first_line + "\n"))
        self.assertIn(f"[观看完整MP4]({run_url}demo.mp4)", summary)
        self.assertIn(f"[原始素材下载]({run_url}raw.mp4)", summary)
        self.assertIn("结果", summary)
        self.assertIn("PASS", summary)

    def test_custom_base_url_trims_trailing_slash(self):
        self.assertEqual(0, self.build(base_url="https://example.test/site/")[0])
        summary = self.read_summary()
        expected = (
            "[![仓库引用操作演示](https://example.test/site/demos/4242/preview.gif?v=4242)]"
            "(https://example.test/site/demos/4242/)"
        )
        self.assertTrue(summary.startswith(expected + "\n"))
        self.assertNotIn("site//demos", summary)

    def test_events_fallback_supplies_steps_and_result(self):
        manifest = manifest_copy()
        manifest.pop("steps")
        manifest.pop("result")
        events = (
            "type\tstep_id\ttitle\tepoch_ms\tvideo_seconds\n"
            "START\t\t\t1000\t0.000\n"
            "STEP\teditor-open\t从事件读取的步骤\t1200\t0.200\n"
            "PASS\t\t\t3000\t2.000\n"
        )
        media, out = write_bundle(self.root / "events", manifest=manifest, events=events)
        code, _, stderr = invoke(media, out)
        self.assertEqual(0, code, stderr)
        page = (out / "demos" / "4242" / "index.html").read_text(encoding="utf-8")
        self.assertIn("从事件读取的步骤", page)
        self.assertIn("PASS", page)

    def test_raw_log_details_and_unknown_manifest_keys_are_not_copied(self):
        manifest = manifest_copy()
        manifest["logs"] = ["SECRET_RAW_LOG_MANIFEST"]
        events = (
            "type\tstep_id\ttitle\tepoch_ms\tvideo_seconds\tdetail\n"
            "STEP\teditor-open\t打开备货区编辑器\t1200\t0.200\tSECRET_RAW_LOG_STEP\n"
            "PASS\t\t\t3000\t2.000\tSECRET_RAW_LOG_PASS\n"
        )
        media, out = write_bundle(self.root / "logs", manifest=manifest, events=events)
        code, _, stderr = invoke(media, out)
        self.assertEqual(0, code, stderr)
        run_dir = out / "demos" / "4242"
        page = (run_dir / "index.html").read_text(encoding="utf-8")
        summary = (run_dir / "summary.md").read_text(encoding="utf-8")
        self.assertNotIn("SECRET_RAW_LOG", page)
        self.assertNotIn("SECRET_RAW_LOG", summary)
        self.assertFalse((run_dir / "events.tsv").exists())

    def test_invalid_capture_media_is_not_published(self):
        manifest = manifest_copy()
        manifest["media"] = {"status": "invalid", "warnings": ["原始画面为全黑"]}
        media, out = write_bundle(self.root / "invalid", manifest=manifest)
        code, stdout, stderr = invoke(media, out)
        self.assertEqual(2, code)
        self.assertIn("拒绝发布", stderr)
        self.assertFalse(out.exists())

    def test_missing_media_file_errors(self):
        for name in PAGE.REQUIRED_MEDIA:
            with self.subTest(missing=name):
                with tempfile.TemporaryDirectory() as directory:
                    media, out = write_bundle(directory, missing=(name,))
                    code, _, stderr = invoke(media, out)
                    self.assertEqual(2, code, stderr)
                    self.assertIn(name, stderr)
                    self.assertFalse((out / "demos" / "4242" / "index.html").exists())

    def test_missing_manifest_field_errors(self):
        for field in ("commit", "node", "backend"):
            with self.subTest(field=field):
                with tempfile.TemporaryDirectory() as directory:
                    manifest = manifest_copy()
                    manifest.pop(field)
                    media, out = write_bundle(directory, manifest=manifest)
                    code, _, stderr = invoke(media, out)
                    self.assertEqual(2, code, stderr)
                    self.assertIn(field, stderr)
                    self.assertIn("缺少必填字段", stderr)
                    self.assertFalse((out / "demos" / "4242" / "index.html").exists())

    def test_missing_steps_and_result_errors_without_fabrication(self):
        manifest = manifest_copy()
        manifest["steps"] = []
        manifest.pop("result")
        events = "type\tstep_id\ttitle\tepoch_ms\tvideo_seconds\n"
        media, out = write_bundle(self.root / "empty-steps", manifest=manifest, events=events)
        code, _, stderr = invoke(media, out)
        self.assertEqual(2, code, stderr)
        self.assertIn("步骤", stderr)
        self.assertIn("result", stderr)
        self.assertFalse((out / "demos" / "4242" / "index.html").exists())

    def test_invalid_manifest_json_errors(self):
        (self.media / "manifest.json").write_text("{not json", encoding="utf-8")
        code, _, stderr = self.build()
        self.assertEqual(2, code)
        self.assertIn("manifest.json", stderr)
        self.assertIn("JSON", stderr)
        self.assertFalse((self.run_dir / "index.html").exists())

    def test_manifest_must_be_object(self):
        (self.media / "manifest.json").write_text("[]", encoding="utf-8")
        code, _, stderr = self.build()
        self.assertEqual(2, code)
        self.assertIn("顶层", stderr)

    def test_invalid_result_is_rejected(self):
        manifest = manifest_copy()
        manifest["result"] = "BANANA"
        media, out = write_bundle(self.root / "bad-result", manifest=manifest)
        code, _, stderr = invoke(media, out)
        self.assertEqual(2, code)
        self.assertIn("result", stderr)
        self.assertIn("无法识别", stderr)

    def test_result_is_derived_only_from_all_explicit_pass_steps(self):
        manifest = manifest_copy()
        manifest.pop("result")
        for step in manifest["steps"]:
            step["status"] = "PASS"
        events = "type\tstep_id\ttitle\tepoch_ms\tvideo_seconds\n"
        media, out = write_bundle(self.root / "derive", manifest=manifest, events=events)
        code, _, stderr = invoke(media, out)
        self.assertEqual(0, code, stderr)
        page = (out / "demos" / "4242" / "index.html").read_text(encoding="utf-8")
        self.assertIn("结果 PASS", page)
        self.assertIn('class="step-status pass">PASS<', page)

    def test_missing_media_directory_errors(self):
        code, _, stderr = invoke(self.root / "does-not-exist", self.out)
        self.assertEqual(2, code)
        self.assertIn("媒体目录不存在", stderr)

    def test_run_id_traversal_is_rejected(self):
        code, _, stderr = self.build(run_id="../escape")
        self.assertEqual(2, code)
        self.assertIn("run-id", stderr)
        self.assertFalse((self.out / "demos" / "escape" / "index.html").exists())
        self.assertFalse((self.root / "escape" / "index.html").exists())

    def test_run_directories_are_isolated(self):
        old = self.out / "demos" / "old-run"
        old.mkdir(parents=True)
        (old / "index.html").write_text("OLD RUN", encoding="utf-8")
        (old / "keep.bin").write_bytes(b"keep")
        self.assertEqual(0, self.build()[0])
        self.assertEqual("OLD RUN", (old / "index.html").read_text(encoding="utf-8"))
        self.assertEqual(b"keep", (old / "keep.bin").read_bytes())
        self.assertTrue((self.run_dir / "index.html").is_file())
        self.assertFalse((self.run_dir / "keep.bin").exists())

    def test_existing_files_inside_run_directory_are_not_deleted(self):
        self.run_dir.mkdir(parents=True)
        (self.run_dir / "keep.txt").write_text("keep", encoding="utf-8")
        self.assertEqual(0, self.build()[0])
        self.assertEqual("keep", (self.run_dir / "keep.txt").read_text(encoding="utf-8"))
        self.assertTrue((self.run_dir / "index.html").is_file())

    def test_invalid_base_url_is_rejected(self):
        for base_url in ("javascript:alert(1)", "https://example.test/site?x=1",
                         "https://example.test/site#frag", "https://user:pass@example.test"):
            with self.subTest(base_url=base_url):
                with tempfile.TemporaryDirectory() as directory:
                    media, out = write_bundle(directory)
                    code, _, stderr = invoke(media, out, base_url=base_url)
                    self.assertEqual(2, code, stderr)
                    self.assertIn("base-url", stderr)

    def test_parser_defaults_match_ci_contract(self):
        args = PAGE.build_parser().parse_args([
            "--media", "media", "--out", "site", "--run-id", "123",
        ])
        self.assertEqual("is-wuyi/Syncmaterial", args.repo)
        self.assertEqual("https://is-wuyi.github.io/Syncmaterial", args.base_url)


if __name__ == "__main__":
    unittest.main(verbosity=2)
