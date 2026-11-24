#!/usr/bin/env python3
"""Regression tests for the CI-only helper script (no network or Gradle)."""

import argparse
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("ci_guard", ROOT / ".github/scripts/ci_guard.py")
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)

PLATFORM_STEPS = (
    "Build, unit tests and SpotBugs",
    "Legacy server GameTest",
    "Foundation server GameTest",
    "Optional dependency legacy server GameTest",
    "Optional dependency foundation server GameTest",
    "Node guard scopes",
    "Unit test summary",
    "Server GameTest summary",
    "SpotBugs summary",
    "JaCoCo summary",
    "Distributable jar guard",
)
CLIENT_STEPS = (
    "Legacy client GameTest",
    "Foundation client GameTest",
    "Optional dependency legacy client GameTest",
    "Optional dependency foundation client GameTest",
    "Legacy client test summary",
    "Foundation client test summary",
    "Optional legacy client test summary",
    "Optional foundation client test summary",
    "Client guard scopes",
)
PREFLIGHT_STEPS = (
    "Verify acceptance guard regressions",
    "Verify ci_guard regressions",
    "Multiversion static guard",
    "Build matrix from versions/versions.json",
)
SHARED_STEPS = (
    "Shared pure tests, jar, JaCoCo and SpotBugs",
    "Multiversion shared guard",
    "Shared unit test summary",
    "Shared SpotBugs summary",
    "Shared JaCoCo summary",
)


def write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def write_json(path, data):
    return write(path, json.dumps(data, ensure_ascii=False, indent=2))


def make_step(name, conclusion="success", status="completed"):
    return {"name": name, "status": status, "conclusion": conclusion}


def make_job(name, steps, conclusion="success", status="completed", job_id=1):
    return {"id": job_id, "name": name, "status": status, "conclusion": conclusion,
            "steps": steps}


def steps(names, overrides=None, extra=()):
    overrides = overrides or {}
    values = list(extra)
    values.extend(make_step(name, overrides.get(name, "success")) for name in names)
    return values


def make_jobs(repo, platform_overrides=None, client_overrides=None,
              skip_platform=(), skip_client=(), skip_shared=()):
    platform_names = [name for name in PLATFORM_STEPS if name not in skip_platform]
    client_names = [name for name in CLIENT_STEPS if name not in skip_client]
    shared_names = [name for name in SHARED_STEPS if name not in skip_shared]
    jobs = [make_job("preflight", steps(PREFLIGHT_STEPS), job_id=0),
            make_job("shared", steps(shared_names, overrides=None), job_id=1)]
    identity = 2
    for node in repo.nodes:
        jobs.append(make_job(
            f"platform ({node})",
            steps(platform_names, platform_overrides,
                  [make_step("Compile platform sources")]),
            job_id=identity))
        identity += 1
        jobs.append(make_job(
            f"client ({node})",
            steps(client_names, client_overrides,
                  [make_step("Compile client test sources")]),
            job_id=identity))
        identity += 1
    return jobs


def artifact(name, identity, expired=False, created="2026-01-01T00:00:00Z"):
    return {"id": identity, "name": name, "expired": expired, "created_at": created}


def default_artifacts(repo):
    values = [artifact("shared-reports", 100)]
    identity = 101
    for node in repo.nodes:
        values.append(artifact(f"SyncMaterial-{node}", identity))
        identity += 1
        values.append(artifact(f"reports-{node}", identity))
        identity += 1
    return values


class Repository:
    def __init__(self, root, nodes, mod_version="1.2.3"):
        self.root = Path(root)
        self.nodes = list(nodes)
        self.mod_version = mod_version
        self.write("versions/versions.json", json.dumps({"minecraft": self.nodes}))
        self.write("gradle.properties", f"mod_version={mod_version}\n")
        for node in self.nodes:
            self.write(f"versions/{node}/gradle.properties", "java_version=21\n")

    def write(self, relative, text):
        return write(self.root / relative, text)

    def write_json(self, relative, data):
        return write_json(self.root / relative, data)

    def write_junit(self, relative, tests=2, failures=0, errors=0, skipped=0):
        cases = "".join(f'<testcase classname="C{index}" name="t{index}" />'
                        for index in range(tests))
        self.write(relative,
                   f'<testsuite tests="{tests}" failures="{failures}" '
                   f'errors="{errors}" skipped="{skipped}">{cases}</testsuite>')

    def write_spotbugs(self, relative, priorities=(1, 2, 2)):
        bugs = "".join(f'<BugInstance priority="{priority}" type="T" />'
                       for priority in priorities)
        self.write(relative, f"<BugCollection>{bugs}</BugCollection>")

    def write_jacoco(self, relative, covered=80, missed=20):
        self.write(relative,
                   f'<report><counter type="INSTRUCTION" missed="{missed}" '
                   f'covered="{covered}" /></report>')

    def write_bytes(self, relative, data=b"jar"):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def write_metadata(self, relative, classes):
        data = {"entrypoints": {"fabric-client-gametest": [
            {"value": class_name, "adapter": "syncmaterial-test-report"}
            for class_name in classes
        ]}}
        self.write_json(relative, data)

    def prepare_shared(self):
        self.write_junit("shared/build/test-results/test/TEST-Core.xml", tests=3)
        self.write_spotbugs("shared/build/reports/spotbugs/main.xml", priorities=())
        self.write_jacoco("shared/build/reports/jacoco/test/jacocoTestReport.xml",
                          covered=90, missed=10)
        self.write_bytes(f"shared/build/libs/shared-{self.mod_version}.jar")

    def prepare_node(self, node):
        base = f"versions/{node}/build"
        self.write_junit(f"{base}/test-results/test/TEST-Node.xml", tests=5)
        self.write_spotbugs(f"{base}/reports/spotbugs/main.xml", priorities=(1, 2, 2))
        self.write_jacoco(f"{base}/reports/jacoco/test/jacocoTestReport.xml",
                          covered=75, missed=25)
        self.write_bytes(f"{base}/libs/SyncMaterial-{node}-{self.mod_version}.jar")
        for backend, passed in (("legacy", 19), ("foundation", 17),
                                ("optional-legacy", 3), ("optional-foundation", 2)):
            self.write(f".runs/{node}/{backend}-server/logs/latest.log",
                       f"All {passed} required tests passed :)\n")
        for backend, (metadata, mod) in GUARD.CLIENT_MODS.items():
            classes = ["Alpha", "Beta"]
            self.write_metadata(metadata, classes)
            tags = "".join(
                f"SYNCMATERIAL_CLIENT_TEST_START {mod} {class_name}\n"
                f"SYNCMATERIAL_CLIENT_TEST_PASS {mod} {class_name}\n"
                for class_name in classes)
            self.write(f".runs/{node}/{backend}-client/logs/latest.log", tags)

    def snapshot(self, downloads, suite, node=None, layout="flat"):
        data = GUARD.build_snapshot(self.root, suite, node)
        label = node or "shared"
        if layout == "flat":
            relative = downloads / f"{suite}-{label}-ci-summary.json"
        elif layout == "nested":
            relative = downloads / f"ci-snapshot-{suite}-{label}" / "ci-summary.json"
        else:
            relative = downloads / "deep" / f"run-{suite}" / "nested" / f"{label}.json"
        return write_json(relative, data)


class SummaryHarness(unittest.TestCase):
    def make_repo(self, nodes=("1.21.7",), mod_version="1.2.3"):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return Repository(directory.name, nodes, mod_version)

    def run_summary(self, repo, jobs, artifacts, upstreams=(), downloads=None):
        downloads = Path(downloads) if downloads is not None else repo.root / "downloads"
        jobs_path = write_json(repo.root / "jobs.json", jobs)
        artifacts_path = write_json(repo.root / "artifacts.json", artifacts)
        return self.run_summary_paths(repo, jobs_path, artifacts_path, upstreams, downloads)

    def run_summary_paths(self, repo, jobs_path, artifacts_path, upstreams=(), downloads=None):
        args = argparse.Namespace(
            root=str(repo.root), jobs=str(jobs_path), artifacts=str(artifacts_path),
            downloads=str(downloads or repo.root / "downloads"),
            repo="owner/repo", run_id="42", upstream=list(upstreams))
        stream = io.StringIO()
        code = 0
        with contextlib.redirect_stdout(stream):
            try:
                GUARD.command_summary(args)
            except SystemExit as error:
                code = int(error.code or 0)
        return code, stream.getvalue()

    def prepare_all(self, repo, layouts=None):
        layouts = layouts or {}
        downloads = repo.root / "downloads"
        repo.prepare_shared()
        repo.snapshot(downloads, "shared", layout=layouts.get("shared", "flat"))
        for node in repo.nodes:
            repo.prepare_node(node)
            repo.snapshot(downloads, "platform", node, layouts.get("platform", "flat"))
            repo.snapshot(downloads, "client", node, layouts.get("client", "flat"))
        return downloads


class SnapshotTest(SummaryHarness):
    def test_missing_evidence_is_null_not_zero(self):
        repo = self.make_repo()
        snapshot = GUARD.build_snapshot(repo.root, "platform", repo.nodes[0])
        self.assertEqual(1, snapshot["schema"])
        self.assertEqual("platform", snapshot["suite"])
        self.assertEqual(repo.nodes[0], snapshot["node"])
        self.assertIsNone(snapshot["unit"])
        self.assertIsNone(snapshot["spotbugs"])
        self.assertIsNone(snapshot["jacoco"])
        self.assertIsNone(snapshot["jar"])
        for backend in GUARD.SERVER_BACKENDS:
            self.assertIsNone(snapshot["server"][backend])
        self.assertIsNone(snapshot["client"])

    def test_snapshot_command_writes_schema_and_creates_parent(self):
        repo = self.make_repo()
        out = repo.root / "nested" / "ci-summary.json"
        args = argparse.Namespace(root=str(repo.root), suite="shared", node=None, out=str(out))
        with contextlib.redirect_stdout(io.StringIO()):
            GUARD.command_snapshot(args)
        data = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(1, data["schema"])
        self.assertEqual("shared", data["suite"])
        self.assertIsNone(data["node"])
        self.assertIsNone(data["unit"])

    def test_observes_junit_server_spotbugs_jacoco_and_jar(self):
        repo = self.make_repo()
        node = repo.nodes[0]
        repo.write_junit("versions/1.21.7/build/test-results/test/TEST-A.xml",
                         tests=4, failures=1)
        repo.write_spotbugs("versions/1.21.7/build/reports/spotbugs/main.xml",
                            priorities=(1, 2, 2))
        repo.write_jacoco("versions/1.21.7/build/reports/jacoco/test/jacocoTestReport.xml",
                          covered=75, missed=25)
        repo.write_bytes("versions/1.21.7/build/libs/SyncMaterial-1.21.7-1.2.3.jar",
                         b"platform-jar")
        repo.write(".runs/1.21.7/legacy-server/logs/latest.log",
                   "1 required tests failed :(\nAll 7 required tests passed :)\n")
        repo.write(".runs/1.21.7/foundation-server/logs/latest.log",
                   "All 5 required tests passed :)\n")
        snapshot = GUARD.build_snapshot(repo.root, "platform", node)
        self.assertEqual({"tests": 4, "failures": 1, "errors": 0, "skipped": 0, "files": 1},
                         snapshot["unit"])
        self.assertEqual({"passed": 7, "failed": 1, "log": "logs/latest.log"},
                         snapshot["server"]["legacy"])
        self.assertEqual(5, snapshot["server"]["foundation"]["passed"])
        self.assertIsNone(snapshot["server"]["optional-legacy"])
        self.assertEqual({"total": 3, "high": 1, "medium": 2, "low": 0}, snapshot["spotbugs"])
        self.assertEqual({"covered": 75, "missed": 25, "percent": 75}, snapshot["jacoco"])
        self.assertEqual("SyncMaterial-1.21.7-1.2.3.jar", snapshot["jar"]["name"])
        self.assertEqual(12, snapshot["jar"]["size"])
        self.assertEqual(64, len(snapshot["jar"]["sha256"]))
        self.assertIsNone(snapshot["client"])

    def test_client_snapshot_uses_metadata_and_real_tags(self):
        repo = self.make_repo()
        node = repo.nodes[0]
        metadata, mod = GUARD.CLIENT_MODS["legacy"]
        repo.write_metadata(metadata, ["A", "B"])
        repo.write(".runs/1.21.7/legacy-client/logs/latest.log",
                   f"SYNCMATERIAL_CLIENT_TEST_START {mod} A\n"
                   f"SYNCMATERIAL_CLIENT_TEST_PASS {mod} A\n"
                   f"SYNCMATERIAL_CLIENT_TEST_START {mod} B\n"
                   f"SYNCMATERIAL_CLIENT_TEST_FAIL {mod} B\n")
        snapshot = GUARD.build_snapshot(repo.root, "client", node)
        self.assertEqual({
            "expected": 2, "started": 2, "passed": 1, "failed": 1,
            "missing": ["B"], "unexpected": [], "log": "latest.log",
        }, snapshot["client"]["legacy"])
        self.assertIsNone(snapshot["unit"])
        self.assertIsNone(snapshot["server"])

    def test_missing_and_invalid_xml_are_not_zero(self):
        repo = self.make_repo()
        results = repo.root / "versions/1.21.7/build/test-results/test"
        self.assertIsNone(GUARD.unit_observation(results))
        results.mkdir(parents=True)
        (results / "TEST-Broken.xml").write_text("<testsuite", encoding="utf-8")
        self.assertIsNone(GUARD.unit_observation(results))
        self.assertIsNone(GUARD.format_unit({}))
        self.assertIsNone(GUARD.format_spotbugs({}))
        self.assertIsNone(GUARD.format_jacoco({}))


class SummaryTest(SummaryHarness):
    def test_full_success_with_dynamic_nodes(self):
        repo = self.make_repo(("9.9", "10.0"))
        downloads = self.prepare_all(repo)
        jobs = make_jobs(repo)
        artifacts = default_artifacts(repo)
        code, output = self.run_summary(
            repo, {"total_count": len(jobs), "jobs": jobs},
            {"total_count": len(artifacts), "artifacts": artifacts},
            upstreams=["preflight=success", "shared=success"], downloads=downloads)
        self.assertEqual(0, code, output)
        self.assertIn("**✅ PASS**", output)
        self.assertIn("9.9", output)
        self.assertIn("10.0", output)
        self.assertNotIn("1.21.7", output)
        self.assertIn("PASS 5（失败 0 / 错误 0 / 跳过 0）", output)
        self.assertIn("PASS 19", output)
        self.assertIn("PASS 17", output)
        self.assertIn("PASS 2/2", output)
        self.assertIn("PASS 3（高 1 / 中 2 / 低 0）", output)
        self.assertIn("[jar](", output)
        self.assertIn("actions/runs/42/artifacts/", output)
        self.assertNotIn("MISSING", output)

    def test_matrix_job_matches_exact_node_token(self):
        repo = self.make_repo(("1.21.7",))
        downloads = self.prepare_all(repo)
        jobs = make_jobs(repo)
        decoy = make_job(
            "platform (1.21.70)",
            steps(PLATFORM_STEPS, {name: "failure" for name in PLATFORM_STEPS}),
            conclusion="failure", job_id=999)
        jobs.append(decoy)
        code, output = self.run_summary(repo, jobs, default_artifacts(repo), downloads=downloads)
        self.assertEqual(0, code, output)
        self.assertIn("**✅ PASS**", output)
        self.assertNotIn("1.21.70", output)

    def test_partial_failure_shows_fail_and_missing_without_fabricating(self):
        repo = self.make_repo(("1.21.7",))
        downloads = repo.root / "downloads"
        repo.prepare_shared()
        repo.snapshot(downloads, "shared")
        repo.prepare_node("1.21.7")
        repo.snapshot(downloads, "client", "1.21.7")
        overrides = {name: "skipped" for name in PLATFORM_STEPS}
        overrides["Build, unit tests and SpotBugs"] = "success"
        overrides["Legacy server GameTest"] = "failure"
        skipped = steps(PLATFORM_STEPS, overrides,
                        [make_step("Compile platform sources")])
        platform = make_job("platform (1.21.7)", skipped, conclusion="failure", job_id=2)
        jobs = [job for job in make_jobs(repo) if not job["name"].startswith("platform")]
        jobs.append(platform)
        code, output = self.run_summary(
            repo, jobs, default_artifacts(repo),
            upstreams=["preflight|x=failure"], downloads=downloads)
        self.assertEqual(1, code, output)
        self.assertIn("**❌ FAIL**", output)
        self.assertIn("preflight\\|x", output)
        self.assertIn("FAIL / MISSING", output)
        self.assertIn("SKIP / MISSING", output)
        self.assertIn("编译成功后", output)

    def test_compile_failure_skipped_steps_do_not_trigger_skip_guard(self):
        repo = self.make_repo(("1.21.7",))
        downloads = self.prepare_all(repo)
        failed = steps(PLATFORM_STEPS, {name: "skipped" for name in PLATFORM_STEPS},
                       [make_step("Compile platform sources", "failure")])
        platform = make_job("platform (1.21.7)", failed, conclusion="failure", job_id=2)
        jobs = [job for job in make_jobs(repo) if not job["name"].startswith("platform")]
        jobs.append(platform)
        code, output = self.run_summary(repo, jobs, default_artifacts(repo), downloads=downloads)
        self.assertEqual(1, code, output)
        self.assertIn("SKIP", output)
        self.assertNotIn("编译成功后", output)

    def test_successful_job_without_snapshot_fails_with_missing(self):
        repo = self.make_repo(("1.21.7",))
        downloads = repo.root / "downloads"
        repo.prepare_shared()
        repo.snapshot(downloads, "shared")
        repo.prepare_node("1.21.7")
        repo.snapshot(downloads, "client", "1.21.7")
        code, output = self.run_summary(repo, make_jobs(repo), default_artifacts(repo),
                                        downloads=downloads)
        self.assertEqual(1, code, output)
        self.assertIn("缺少证据快照", output)
        self.assertIn("| 1.21.7<br>Java 21 | PASS | MISSING |", output)
        self.assertNotIn("PASS 0（失败", output)

    def test_missing_required_step_is_unknown_not_pass(self):
        repo = self.make_repo(("1.21.7",))
        downloads = self.prepare_all(repo)
        jobs = make_jobs(repo, skip_platform=("Foundation server GameTest",))
        code, output = self.run_summary(repo, jobs, default_artifacts(repo), downloads=downloads)
        self.assertEqual(1, code, output)
        self.assertIn("UNKNOWN", output)
        self.assertIn("缺少必要步骤", output)
        self.assertIn("Foundation server GameTest", output)

    def test_jobs_pagination_artifact_dedup_and_expiry(self):
        repo = self.make_repo(("1.21.7",))
        downloads = self.prepare_all(repo)
        jobs = make_jobs(repo)
        jobs_data = [
            {"total_count": 3, "jobs": jobs[:2]},
            {"total_count": 3, "jobs": jobs[2:]},
        ]
        artifacts_data = [
            {"total_count": 4, "artifacts": [
                artifact("SyncMaterial-1.21.7", 11, expired=True),
                artifact("SyncMaterial-1.21.7", 22, created="2026-01-02T00:00:00Z"),
                artifact("reports-1.21.7", 33),
            ]},
            {"total_count": 4, "artifacts": [
                artifact("shared-reports", 44, expired=True),
            ]},
        ]
        code, output = self.run_summary(repo, jobs_data, artifacts_data, downloads=downloads)
        self.assertEqual(0, code, output)
        self.assertIn("artifacts/22", output)
        self.assertNotIn("artifacts/11", output)
        self.assertIn("reports（已过期）", output)

    def test_download_layouts_are_discovered_recursively(self):
        repo = self.make_repo(("1.21.7",))
        downloads = self.prepare_all(repo, layouts={
            "shared": "flat", "platform": "nested", "client": "deep",
        })
        code, output = self.run_summary(repo, make_jobs(repo), default_artifacts(repo),
                                        downloads=downloads)
        self.assertEqual(0, code, output)
        self.assertIn("**✅ PASS**", output)
        self.assertIn("PASS 2/2", output)

    def test_workflow_artifact_directory_layout(self):
        repo = self.make_repo(("1.21.7",))
        downloads = repo.root / "ci-artifacts"
        repo.prepare_shared()
        write_json(downloads / "shared-reports" / "ci-summary.json",
                   GUARD.build_snapshot(repo.root, "shared", None))
        repo.prepare_node("1.21.7")
        write_json(downloads / "reports-1.21.7" / "ci-summary.json",
                   GUARD.build_snapshot(repo.root, "platform", "1.21.7"))
        write_json(downloads / "client-reports-1.21.7" / "ci-summary.json",
                   GUARD.build_snapshot(repo.root, "client", "1.21.7"))
        code, output = self.run_summary(repo, make_jobs(repo), default_artifacts(repo),
                                        downloads=downloads)
        self.assertEqual(0, code, output)
        self.assertIn("**✅ PASS**", output)
        self.assertIn("PASS 19", output)
        self.assertIn("PASS 2/2", output)

    def test_markdown_escaping(self):
        self.assertEqual("a\\|b c&lt;d&gt;", GUARD.md_cell("a|b\nc<d>"))
        self.assertEqual("—", GUARD.md_cell(None))

    def test_api_unavailable_prints_summary_then_fails(self):
        repo = self.make_repo(("1.21.7",))
        downloads = self.prepare_all(repo)
        missing = repo.root / "does-not-exist.json"
        artifacts_path = write_json(repo.root / "artifacts.json", default_artifacts(repo))
        code, output = self.run_summary_paths(repo, missing, artifacts_path,
                                              downloads=downloads)
        self.assertEqual(1, code, output)
        self.assertTrue(output.startswith("# SyncMaterial CI 汇总"))
        self.assertIn("jobs API 不可用", output)
        self.assertIn("**❌ FAIL**", output)


class LegacyReleaseTest(SummaryHarness):
    def write_release_jar(self, repo, node):
        jar = repo.root / f"versions/{node}/build/libs/SyncMaterial-{node}-{repo.mod_version}.jar"
        jar.parent.mkdir(parents=True, exist_ok=True)
        metadata = {
            "version": f"{node}-{repo.mod_version}",
            "entrypoints": {"main": ["example.Entry"]},
            "depends": {"minecraft": node},
        }
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/jars/sqlite-jdbc-3.45.1.0.jar", b"")
            archive.writestr("syncmaterial.mixins.json", "{}")
            archive.writestr("fabric.mod.json", json.dumps(metadata))
        return jar

    def run_cli(self, function, args):
        stream = io.StringIO()
        code = 0
        with contextlib.redirect_stdout(stream):
            try:
                function(args)
            except SystemExit as error:
                code = int(error.code or 0)
        return code, stream.getvalue()

    def test_matrix_artifact_and_release_set_remain_compatible(self):
        repo = self.make_repo(("1.21.7", "26.2"))
        dist = repo.root / "dist"
        dist.mkdir()
        for node in repo.nodes:
            jar = self.write_release_jar(repo, node)
            (dist / jar.name).write_bytes(jar.read_bytes())
        code, output = self.run_cli(GUARD.command_matrix,
                                    argparse.Namespace(root=str(repo.root)))
        self.assertEqual(0, code)
        matrix = json.loads(output)
        self.assertEqual([node for node in repo.nodes],
                         [entry["node"] for entry in matrix["include"]])
        code, _ = self.run_cli(GUARD.command_artifact,
                               argparse.Namespace(root=str(repo.root), node="1.21.7"))
        self.assertEqual(0, code)
        code, _ = self.run_cli(GUARD.command_release_set,
                               argparse.Namespace(root=str(repo.root), dist=str(dist)))
        self.assertEqual(0, code)

    def test_unit_command_missing_results_keeps_old_placeholder(self):
        repo = self.make_repo(("1.21.7",))
        code, output = self.run_cli(
            GUARD.command_unit,
            argparse.Namespace(suite="shared", node=None,
                               results=str(repo.root / "missing")))
        self.assertEqual(0, code)
        self.assertIn("未找到 JUnit 结果", output)
        self.assertNotIn("0 个", output)


class WorkflowContractTest(unittest.TestCase):
    def test_plain_run_never_uses_shell_continuation_and_required_steps_exist(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        for line in workflow.splitlines():
            stripped = line.strip()
            self.assertFalse(stripped.startswith("run:") and stripped.endswith("\\"),
                             f"plain run 不得使用反斜杠续行: {stripped}")
        required = GUARD.PREFLIGHT_REQUIRED_STEPS + GUARD.PLATFORM_REQUIRED_STEPS \
            + GUARD.CLIENT_REQUIRED_STEPS + GUARD.SHARED_REQUIRED_STEPS
        for name in required:
            self.assertIn(f"- name: {name}", workflow)
        self.assertIn("needs: [preflight, shared, platform, client]", workflow)

    def test_unit_command_invalid_xml_does_not_report_zero(self):
        harness = LegacyReleaseTest()
        self.addCleanup(harness.doCleanups)
        repo = harness.make_repo()
        results = repo.root / "invalid"
        write(results / "TEST-Broken.xml", "<testsuite")
        code, output = harness.run_cli(
            GUARD.command_unit,
            argparse.Namespace(suite="shared", node=None, results=str(results)))
        self.assertEqual(0, code)
        self.assertIn("无法解析", output)
        self.assertNotIn("0 个", output)


if __name__ == "__main__":
    unittest.main()
