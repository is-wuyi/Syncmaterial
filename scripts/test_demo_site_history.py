#!/usr/bin/env python3
"""Contract tests for the generated Pages history helper (no network)."""

import importlib.util
import io
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "demo_site_history", ROOT / ".github/scripts/demo_site_history.py")
HISTORY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HISTORY)


def pages_archive(path, entries):
    tar_bytes = io.BytesIO()
    with tarfile.open(fileobj=tar_bytes, mode="w") as archive:
        for name, content in entries:
            data = content if isinstance(content, bytes) else content.encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    with zipfile.ZipFile(path, "w") as bundle:
        bundle.writestr("artifact.tar", tar_bytes.getvalue())


class DemoSiteHistoryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.site = self.root / "site"

    def test_restores_only_regular_files_and_preserves_existing_runs(self):
        archive = self.root / "pages.zip"
        pages_archive(archive, [("demos/101/index.html", "first"),
                                ("demos/101/raw.mp4", b"video")])
        (self.site / "demos/100").mkdir(parents=True)
        HISTORY.restore(archive, self.site)
        self.assertEqual("first", (self.site / "demos/101/index.html").read_text())
        self.assertEqual(b"video", (self.site / "demos/101/raw.mp4").read_bytes())
        self.assertTrue((self.site / "demos/100").is_dir())

    def test_rejects_missing_tar_and_unsafe_archive_members(self):
        archive = self.root / "pages.zip"
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr("other.txt", "not pages")
        with self.assertRaises(HISTORY.HistoryError):
            HISTORY.restore(archive, self.site)
        archive.unlink()
        pages_archive(archive, [("../escape", "bad")])
        with self.assertRaises(HISTORY.HistoryError):
            HISTORY.restore(archive, self.site)
        self.assertFalse((self.root / "escape").exists())

    def test_rejects_links_in_pages_archive(self):
        archive = self.root / "pages.zip"
        tar_bytes = io.BytesIO()
        with tarfile.open(fileobj=tar_bytes, mode="w") as tar:
            link = tarfile.TarInfo("demos/101/raw.mp4")
            link.type = tarfile.SYMTYPE
            link.linkname = "/etc/passwd"
            tar.addfile(link)
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr("artifact.tar", tar_bytes.getvalue())
        with self.assertRaises(HISTORY.HistoryError):
            HISTORY.restore(archive, self.site)

    def test_prunes_only_old_numeric_runs_and_writes_root_index(self):
        for run in (101, 102, 103):
            directory = self.site / "demos" / str(run)
            directory.mkdir(parents=True)
            (directory / "index.html").write_text(f"run {run}", encoding="utf-8")
        unexpected = self.site / "demos" / "draft"
        unexpected.mkdir()
        current = self.site / "demos/104"
        current.mkdir()
        runs = HISTORY.prune(self.site, keep=2, protect="104")
        self.assertEqual(["104", "103"], [entry.name for entry in runs])
        self.assertFalse((self.site / "demos/102").exists())
        self.assertFalse((self.site / "demos/101").exists())
        self.assertTrue(unexpected.is_dir())
        index = (self.site / "index.html").read_text(encoding="utf-8")
        self.assertIn('href="demos/104/"', index)
        self.assertIn('href="demos/103/"', index)
        self.assertNotIn("demos/102/", index)

    def test_protected_run_is_retained_even_with_smaller_numeric_id(self):
        for run in (200, 201, 100):
            (self.site / "demos" / str(run)).mkdir(parents=True)
        runs = HISTORY.prune(self.site, keep=2, protect="100")
        self.assertEqual(["100", "201"], [entry.name for entry in runs])

    def test_rejects_invalid_retention_and_run_id(self):
        with self.assertRaises(HISTORY.HistoryError):
            HISTORY.prune(self.site, keep=0, protect="100")
        with self.assertRaises(HISTORY.HistoryError):
            HISTORY.prune(self.site, keep=1, protect="../100")


if __name__ == "__main__":
    unittest.main()
