#!/usr/bin/env python3
"""CI-only helpers for the SyncMaterial multi-version matrix.

This file intentionally does not re-implement architecture, package, unit,
server, client or SpotBugs gates. ``scripts/verify_multiversion.py`` is the
single authority for those; the commands here produce matrix values, source
hashes, evidence snapshots, unified job summaries, task-graph checks and
release artifact-set checks. Invoke with ``python3 -I`` from the repository
root.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from collections import Counter
from pathlib import Path
from urllib.parse import quote
from xml.etree import ElementTree

ADAPTER = "syncmaterial-test-report"
GRADLE_VERSION = "9.8.2"
GRADLE_SHA256 = "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"
OPTIONAL_RUNS = (
    "optionalGameTest",
    "optionalFoundationGameTest",
    "optionalClientGameTest",
    "optionalFoundationClientGameTest",
)
CLIENT_REPORT = re.compile(r"SYNCMATERIAL_CLIENT_TEST_(START|PASS|FAIL)\s+(\S+)\s+(\S+)")

SNAPSHOT_SCHEMA = 1
SUITES = ("shared", "platform", "client")
SERVER_BACKENDS = ("legacy", "foundation", "optional-legacy", "optional-foundation")
CLIENT_MODS = {
    "legacy": ("src/gametest/resources/fabric.mod.json", "syncmaterial-test"),
    "foundation": ("src/foundationtest/resources/fabric.mod.json", "syncmaterial-foundation-test"),
    "optional-legacy": ("src/optionaltest/resources/fabric.mod.json", "syncmaterial-optional-test"),
    "optional-foundation": ("src/optionaltest/resources/fabric.mod.json", "syncmaterial-optional-test"),
}
# Step names mirror .github/workflows/ci.yml.  The compile steps are detected but
# never required, so an older workflow keeps producing a usable summary.
PREFLIGHT_REQUIRED_STEPS = (
    "Verify acceptance guard regressions",
    "Verify ci_guard regressions",
    "Multiversion static guard",
    "Build matrix from versions/versions.json",
)
PLATFORM_REQUIRED_STEPS = (
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
CLIENT_REQUIRED_STEPS = (
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
SHARED_REQUIRED_STEPS = (
    "Shared pure tests, jar, JaCoCo and SpotBugs",
    "Multiversion shared guard",
    "Shared unit test summary",
    "Shared SpotBugs summary",
    "Shared JaCoCo summary",
)
COMPILE_STEPS = {
    "platform": "Compile platform sources",
    "client": "Compile client test sources",
}
STATUS_ORDER = {"PASS": 0, "SKIP": 1, "UNKNOWN": 2, "FAIL": 3}


def fail(message: str) -> None:
    print(f"::error::{message}")
    raise SystemExit(1)


def note(message: str = "") -> None:
    print(message)


def repository_root(value: str) -> Path:
    return Path(value).resolve()


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def load_versions(root: Path) -> list[str]:
    manifest = root / "versions" / "versions.json"
    if not manifest.is_file():
        fail(f"缺少版本清单: {manifest}")
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"version 清单不是合法 JSON: {error}")
    nodes = data.get("minecraft") if isinstance(data, dict) else data
    if not isinstance(nodes, list) or not nodes:
        fail("versions/versions.json 必须提供非空的 minecraft 节点数组")
    if any(not isinstance(node, str) or not node.strip() for node in nodes):
        fail("versions/versions.json 存在空节点或非字符串节点")
    if len(set(nodes)) != len(nodes):
        fail("versions/versions.json 存在重复节点")
    for node in nodes:
        if "/" in node or ".." in node:
            fail(f"versions/versions.json 节点非法: {node}")
    return nodes


def load_json(path: Path) -> dict | None:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


def entrypoints(metadata: dict | None, name: str) -> list[tuple[str, str | None]]:
    if not metadata:
        return []
    entries = metadata.get("entrypoints", {}).get(name, [])
    if not isinstance(entries, list):
        return []
    normalized: list[tuple[str, str | None]] = []
    for entry in entries:
        if isinstance(entry, str):
            normalized.append((entry, None))
        elif isinstance(entry, dict) and isinstance(entry.get("value"), str):
            normalized.append((entry["value"], entry.get("adapter")))
    return normalized


def command_matrix(args: argparse.Namespace) -> None:
    root = repository_root(args.root)
    matrix = []
    for node in load_versions(root):
        properties = read_properties(root / "versions" / node / "gradle.properties")
        matrix.append({
            "node": node,
            "java": properties.get("java_version", "?"),
            "slug": node.replace(".", "_"),
        })
    print(json.dumps({"include": matrix}, separators=(",", ":")))


def command_info(args: argparse.Namespace) -> None:
    root = repository_root(args.root)
    nodes = load_versions(root)
    mod_version = read_properties(root / "gradle.properties").get("mod_version", "?")
    wrapper = read_properties(root / "gradle" / "wrapper" / "gradle-wrapper.properties")
    stonecutter = (root / "stonecutter.gradle").read_text(encoding="utf-8") \
        if (root / "stonecutter.gradle").is_file() else ""
    detached = bool(re.search(r"stonecutter\.active\s+null\b", stonecutter))
    note("## 预检（信息汇总，实质守卫由 scripts/verify_multiversion.py 执行）")
    rows = []
    for node in nodes:
        properties = read_properties(root / "versions" / node / "gradle.properties")
        rows.append(f"{node}（Java {properties.get('java_version', '?')}）")
    note(f"- 版本节点（versions/versions.json）: {', '.join(rows)}")
    note(f"- 唯一 mod_version: {mod_version}")
    note(f"- Gradle wrapper: {GRADLE_VERSION if wrapper.get('distributionUrl', '').endswith('gradle-' + GRADLE_VERSION + '-bin.zip') else wrapper.get('distributionUrl', '缺失')}")
    note(f"- wrapper SHA-256 固定: {wrapper.get('distributionSha256Sum') == GRADLE_SHA256}")
    note(f"- Stonecutter detached: {detached}")
    for label, path, mod_id in (
        ("legacy", root / "src" / "gametest" / "resources" / "fabric.mod.json", "syncmaterial-test"),
        ("foundation", root / "src" / "foundationtest" / "resources" / "fabric.mod.json",
         "syncmaterial-foundation-test"),
        ("optional", root / "src" / "optionaltest" / "resources" / "fabric.mod.json",
         "syncmaterial-optional-test"),
    ):
        metadata = load_json(path)
        clients = entrypoints(metadata, "fabric-client-gametest")
        servers = entrypoints(metadata, "fabric-gametest")
        adapted = sum(1 for _, adapter in clients if adapter)
        note(f"- {label} 入口: 客户端 {len(clients)}（经报告适配器 {adapted}）/ 服务端 {len(servers)}"
             f"{'' if metadata else '，metadata 尚未落盘'}")
    note("- 根聚合任务: buildAllVersions / checkAllVersions / testAllClients / testOptionalDependencies")


def source_hash(root: Path, path: str) -> str:
    base = root / path
    if not base.is_dir():
        fail(f"源码目录不存在: {base}")
    digest = hashlib.sha256()
    for file in sorted((item for item in base.rglob("*") if item.is_file()),
                       key=lambda item: item.relative_to(base).as_posix()):
        digest.update(file.relative_to(base).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(file.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def command_source_hash(args: argparse.Namespace) -> None:
    value = source_hash(repository_root(args.root), args.path)
    Path(args.out).write_text(value + "\n", encoding="utf-8")
    note(f"source hash ({args.path}): {value}")


def command_source_compare(args: argparse.Namespace) -> None:
    before = Path(args.before).read_text(encoding="utf-8").strip()
    current = source_hash(repository_root(args.root), args.path)
    if before != current:
        fail(f"根 {args.path} 在构建后发生变化: before={before} after={current}")
    note(f"- 根 {args.path} 哈希未改写: {current}")


def scan_junit(results: Path) -> tuple[int, int, int, int, int, int]:
    """Return totals plus the number of existing and parseable result files."""
    files = sorted(results.glob("*.xml"))
    total = failures = errors = skipped = parsed = 0
    for file in files:
        try:
            document = ElementTree.parse(file).getroot()
            suites = [document] if document.tag == "testsuite" else list(document.iter("testsuite"))
            file_total = file_failures = file_errors = file_skipped = 0
            for suite in suites:
                file_total += int(suite.get("tests", len(suite.findall("testcase"))))
                file_failures += int(suite.get("failures", 0))
                file_errors += int(suite.get("errors", 0))
                file_skipped += int(suite.get("skipped", suite.get("disabled", 0)))
        except (ElementTree.ParseError, OSError, ValueError):
            continue
        parsed += 1
        total += file_total
        failures += file_failures
        errors += file_errors
        skipped += file_skipped
    return total, failures, errors, skipped, len(files), parsed


def junit_totals(results: Path) -> tuple[int, int, int, int, int]:
    return scan_junit(results)[:5]


def unit_observation(results: Path) -> dict | None:
    total, failures, errors, skipped, files, parsed = scan_junit(results)
    if not files or not parsed:
        return None
    return {
        "tests": total,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "files": files,
    }


def command_unit(args: argparse.Namespace) -> None:
    results = Path(args.results)
    observation = unit_observation(results)
    label = args.suite + (f" {args.node}" if args.node else "")
    if observation is None:
        problem = "结果文件无法解析" if any(results.glob("*.xml")) else "未找到 JUnit 结果"
        note(f"- 单元测试（{label}）: {problem}（实质判定由 verify_multiversion unit scope 负责）")
        return
    note(f"- 单元测试（{label}）: {observation['tests']} 个，失败 {observation['failures']}，"
         f"错误 {observation['errors']}，跳过 {observation['skipped']}"
         f"（{observation['files']} 个结果文件；精确基线由 verify_multiversion 负责）")


SERVER_PASSED = re.compile(r"All (\d+) required tests passed")
SERVER_FAILED = re.compile(r"(\d+) required tests failed")


def server_observation(run_dir: Path) -> dict | None:
    log = run_dir / "logs" / "latest.log"
    if not log.is_file():
        return None
    text = log.read_text(encoding="utf-8", errors="replace")
    passed = SERVER_PASSED.findall(text)
    failed = SERVER_FAILED.findall(text)
    return {
        "passed": int(passed[-1]) if passed else None,
        "failed": int(failed[-1]) if failed else None,
        "log": "logs/latest.log",
    }


def command_server(args: argparse.Namespace) -> None:
    observation = server_observation(Path(args.run_dir))
    if observation is None:
        note(f"- 服务端 GameTest（{args.backend}）: 未找到日志（实质判定由 verify_multiversion server scope 负责）")
        return
    if observation["passed"] is None:
        note(f"- 服务端 GameTest（{args.backend}）: 未出现通过标记（实质判定由 verify_multiversion server scope 负责）")
        return
    note(f"- 服务端 GameTest（{args.backend}）: 最近一次报告 {observation['passed']} 个通过"
         f"（精确基线由 verify_multiversion 负责）")


def read_client_log(run_dir: Path) -> tuple[Path | None, str]:
    for name in ("latest.log", "debug.log"):
        candidate = run_dir / "logs" / name
        if candidate.is_file():
            text = candidate.read_text(encoding="utf-8", errors="replace")
            if "SYNCMATERIAL_CLIENT_TEST_" in text:
                return candidate, text
    return None, ""


def client_counts(text: str, mod: str) -> tuple[Counter[str], Counter[str], int]:
    starts: Counter[str] = Counter()
    passes: Counter[str] = Counter()
    failures = 0
    for kind, mod_id, class_name in CLIENT_REPORT.findall(text):
        if mod_id != mod:
            continue
        if kind == "START":
            starts[class_name] += 1
        elif kind == "PASS":
            passes[class_name] += 1
        else:
            failures += 1
    return starts, passes, failures


def client_observation(metadata_path: Path, run_dir: Path, mod: str) -> dict | None:
    metadata = load_json(metadata_path)
    classes = ({value for value, _ in entrypoints(metadata, "fabric-client-gametest")}
               if metadata is not None else None)
    log, text = read_client_log(run_dir)
    if log is None:
        return None
    starts, passes, failures = client_counts(text, mod)
    return {
        "expected": len(classes) if classes is not None else None,
        "started": len(starts),
        "passed": len(passes),
        "failed": failures,
        "missing": sorted(classes - set(passes)) if classes is not None else None,
        "unexpected": sorted(set(passes) - classes) if classes is not None else None,
        "log": log.name,
    }


def command_client(args: argparse.Namespace) -> None:
    observation = client_observation(Path(args.metadata), Path(args.run_dir), args.mod)
    label = args.mod + (f" {args.node}" if args.node else "")
    if observation is None:
        note(f"- 客户端测试（{label}）: 未找到真实上报标签"
             f"（实质判定由 verify_multiversion client scope 负责）")
        return
    expected = observation["expected"] or 0
    missing = observation["missing"] or []
    unexpected = observation["unexpected"] or []
    note(f"- 客户端测试（{label}）: metadata {expected} 类，START {observation['started']}，"
         f"PASS {observation['passed']}，FAIL {observation['failed']}，"
         f"missing {len(missing)}，unexpected {len(unexpected)}"
         f"（精确逐类判定由 verify_multiversion client scope 负责，{observation['log']}）")


def spotbugs_observation(report: Path) -> dict | None:
    if not report.is_file():
        return None
    try:
        document = ElementTree.parse(report).getroot()
    except (ElementTree.ParseError, OSError):
        return None
    bugs = document.findall("BugInstance")
    priorities = Counter(bug.get("priority") for bug in bugs)
    return {
        "total": len(bugs),
        "high": priorities.get("1", 0),
        "medium": priorities.get("2", 0),
        "low": priorities.get("3", 0),
    }


def command_spotbugs(args: argparse.Namespace) -> None:
    observation = spotbugs_observation(Path(args.report))
    label = args.suite + (f" {args.node}" if args.node else "")
    if observation is None:
        if not Path(args.report).is_file():
            note(f"- SpotBugs（{label}）: 报告未生成（实质判定由 verify_multiversion spotbugs scope 负责）")
        else:
            note(f"- SpotBugs（{label}）: 报告无法解析")
        return
    names = (("high", "高"), ("medium", "中"), ("low", "低"))
    parts = [f"{name} {observation[key]}" for key, name in names if observation[key]]
    note(f"- SpotBugs（{label}）: 共 {observation['total']} 条"
         f"（{'，'.join(parts) if parts else '无'}；基线由 verify_multiversion 负责）")


def jacoco_observation(report: Path) -> tuple[dict | None, str | None]:
    if not report.is_file():
        return None, "missing"
    try:
        document = ElementTree.parse(report).getroot()
    except (ElementTree.ParseError, OSError):
        return None, "unparseable"
    instruction = next((item for item in document.findall("counter")
                        if item.get("type") == "INSTRUCTION"), None)
    if instruction is None:
        return None, "no-counter"
    try:
        missed = int(instruction.get("missed", 0))
        covered = int(instruction.get("covered", 0))
    except (TypeError, ValueError):
        return None, "unparseable"
    total = missed + covered
    return {
        "covered": covered,
        "missed": missed,
        "percent": covered * 100 // total if total else None,
    }, None


def command_jacoco(args: argparse.Namespace) -> None:
    label = args.suite + (f" {args.node}" if args.node else "")
    observation, problem = jacoco_observation(Path(args.report))
    if problem == "missing":
        note(f"- 覆盖率（{label}）: 报告未生成")
        return
    if problem == "unparseable":
        note("- 覆盖率: 报告无法解析")
        return
    if problem == "no-counter" or observation is None:
        note("- 覆盖率: 报告缺少 INSTRUCTION counter")
        return
    percent = observation["percent"] if observation["percent"] is not None else 0
    note(f"- 覆盖率（{label}，JaCoCo 指令级）: "
         f"{percent}%（覆盖 {observation['covered']} / 未覆盖 {observation['missed']}）")


def command_artifact(args: argparse.Namespace) -> None:
    root = repository_root(args.root)
    mod_version = read_properties(root / "gradle.properties").get("mod_version")
    if not mod_version:
        fail("根 gradle.properties 缺少 mod_version")
    name = f"SyncMaterial-{args.node}-{mod_version}"
    jars = root / "versions" / args.node / "build" / "libs"
    jar = jars / f"{name}.jar"
    if not jar.is_file():
        available = sorted(path.name for path in jars.glob("*.jar")) if jars.is_dir() else []
        fail(f"发行 jar 不存在: {jar}（现有: {available}）")
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        if not any(entry.startswith("META-INF/jars/sqlite-jdbc") for entry in names):
            fail(f"{jar.name} 未嵌套 sqlite-jdbc 驱动")
        if "syncmaterial.mixins.json" not in names:
            fail(f"{jar.name} 缺少 mixins 清单")
        try:
            metadata = json.loads(archive.read("fabric.mod.json"))
        except (KeyError, json.JSONDecodeError) as error:
            fail(f"{jar.name} 的 fabric.mod.json 异常: {error}")
    if not metadata.get("entrypoints"):
        fail(f"{jar.name} 的 fabric.mod.json entrypoints 为空")
    expected_version = f"{args.node}-{mod_version}"
    if str(metadata.get("version")) != expected_version:
        fail(f"{jar.name} 版本应为 {expected_version}，实际为 {metadata.get('version')}")
    minecraft = str(metadata.get("depends", {}).get("minecraft", ""))
    if args.node not in minecraft:
        fail(f"{jar.name} 的 minecraft 依赖未包含 {args.node}: {minecraft}")
    note(f"- 产物（{args.node}）: {jar.name}，{jar.stat().st_size} 字节，"
         f"sha256 {hashlib.sha256(jar.read_bytes()).hexdigest()}")


def select_jar(directory: Path, exact: str | None, pattern: str) -> Path | None:
    if exact and (directory / exact).is_file():
        return directory / exact
    if not directory.is_dir():
        return None
    candidates = sorted(path for path in directory.glob(pattern)
                        if not path.name.endswith(("-sources.jar", "-javadoc.jar")))
    return candidates[0] if candidates else None


def jar_observation(jar: Path | None) -> dict | None:
    if jar is None or not jar.is_file():
        return None
    return {
        "name": jar.name,
        "size": jar.stat().st_size,
        "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
    }


def backend_value(section: object, backend: str) -> object | None:
    if not isinstance(section, dict):
        return None
    for key in (backend, backend.replace("-", "_")):
        if key in section:
            return section[key]
    return None


def build_snapshot(root: Path, suite: str, node: str | None) -> dict:
    snapshot = {
        "schema": SNAPSHOT_SCHEMA,
        "suite": suite,
        "node": node,
        "unit": None,
        "server": None,
        "client": None,
        "spotbugs": None,
        "jacoco": None,
        "jar": None,
    }
    mod_version = read_properties(root / "gradle.properties").get("mod_version")
    if suite == "shared":
        base = root / "shared" / "build"
        snapshot["unit"] = unit_observation(base / "test-results" / "test")
        snapshot["spotbugs"] = spotbugs_observation(base / "reports" / "spotbugs" / "main.xml")
        snapshot["jacoco"] = jacoco_observation(
            base / "reports" / "jacoco" / "test" / "jacocoTestReport.xml")[0]
        exact = f"shared-{mod_version}.jar" if mod_version else None
        snapshot["jar"] = jar_observation(select_jar(base / "libs", exact, "shared-*.jar"))
    elif suite == "platform":
        base = root / "versions" / str(node) / "build"
        snapshot["unit"] = unit_observation(base / "test-results" / "test")
        snapshot["server"] = {
            backend: server_observation(root / ".runs" / str(node) / f"{backend}-server")
            for backend in SERVER_BACKENDS
        }
        snapshot["spotbugs"] = spotbugs_observation(base / "reports" / "spotbugs" / "main.xml")
        snapshot["jacoco"] = jacoco_observation(
            base / "reports" / "jacoco" / "test" / "jacocoTestReport.xml")[0]
        exact = f"SyncMaterial-{node}-{mod_version}.jar" if mod_version else None
        snapshot["jar"] = jar_observation(
            select_jar(base / "libs", exact, f"SyncMaterial-{node}-*.jar"))
    elif suite == "client":
        snapshot["client"] = {}
        for backend, (metadata, mod) in CLIENT_MODS.items():
            snapshot["client"][backend] = client_observation(
                root / metadata, root / ".runs" / str(node) / f"{backend}-client", mod)
    return snapshot


def command_snapshot(args: argparse.Namespace) -> None:
    suite = args.suite
    node = args.node
    if suite in ("platform", "client") and not node:
        fail(f"snapshot --suite {suite} 需要 --node")
    if node and ("/" in node or "\\" in node or ".." in node):
        fail(f"snapshot 节点非法: {node}")
    snapshot = build_snapshot(repository_root(args.root), suite, node)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    label = suite + (f" {node}" if node else "")
    note(f"- 证据快照（{label}）: {out}")


def command_generated(args: argparse.Namespace) -> None:
    base = repository_root(args.root) / "versions" / args.node / "build" / "generated" / "stonecutter"
    main = list((base / "main" / "java").rglob("*.java")) if (base / "main" / "java").is_dir() else []
    test = list((base / "test" / "java").rglob("*.java")) if (base / "test" / "java").is_dir() else []
    note(f"- Stonecutter 生成源（{args.node}）: main/java {len(main)} 个，test/java {len(test)} 个"
         f"（缺失时由 verify_multiversion static/package scope 判定）")


def command_aggregates(args: argparse.Namespace) -> None:
    log = Path(args.log)
    if not log.is_file():
        fail(f"聚合任务 dry-run 输出不存在: {log}")
    text = log.read_text(encoding="utf-8", errors="replace")
    nodes = load_versions(repository_root(args.root))
    required = [":buildAllVersions", ":checkAllVersions", ":testAllClients", ":testOptionalDependencies",
                ":shared:build", ":shared:check", ":shared:test"]
    for node in nodes:
        required.extend(f":{node}:{task}" for task in (
            "build", "test", "spotbugsMain", "checkPlatform",
            "runGameTest", "runFoundationGameTest",
            "runClientGameTest", "runFoundationClientGameTest",
            "runOptionalGameTest", "runOptionalFoundationGameTest",
            "runOptionalClientGameTest", "runOptionalFoundationClientGameTest"))
    missing = [task for task in required
               if not re.search(rf"(?m)^\s*{re.escape(task)}(?:\s|$)", text)]
    if missing:
        fail(f"聚合任务图缺少: {', '.join(missing)}")
    note(f"- 聚合任务图: buildAllVersions / checkAllVersions / testAllClients / "
         f"testOptionalDependencies 覆盖 shared 与 {len(nodes)} 个节点")


def command_release_set(args: argparse.Namespace) -> None:
    root = repository_root(args.root)
    mod_version = read_properties(root / "gradle.properties").get("mod_version")
    if not mod_version:
        fail("根 gradle.properties 缺少 mod_version")
    expected = {f"SyncMaterial-{node}-{mod_version}.jar" for node in load_versions(root)}
    dist = Path(args.dist)
    actual = {path.name for path in dist.glob("*.jar")} if dist.is_dir() else set()
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    if missing or unexpected:
        fail(f"发布产物集合与 versions.json 不一致: missing={missing} unexpected={unexpected}")
    note(f"- 发布产物集合: {len(expected)} 个 jar 与 versions.json + mod_version 完全一致")


def md_cell(value: object) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = " ".join(line.strip() for line in text.split("\n") if line.strip())
    if not text:
        return "—"
    text = text.replace("\\", "\\\\")
    text = text.replace("|", "\\|")
    text = text.replace("<", "&lt;").replace(">", "&gt;")
    return text


def status_token(status: object, conclusion: object) -> str:
    conclusion = str(conclusion) if conclusion else None
    if conclusion == "success":
        return "PASS"
    if conclusion == "skipped":
        return "SKIP"
    if conclusion in ("failure", "cancelled", "timed_out", "action_required", "startup_failure"):
        return "FAIL"
    if status and status != "completed":
        return "UNKNOWN"
    return conclusion.upper() if conclusion else "UNKNOWN"


def job_token(job: dict | None) -> str:
    if not isinstance(job, dict):
        return "UNKNOWN"
    return status_token(job.get("status"), job.get("conclusion"))


def step_token(step: dict | None) -> str:
    if not isinstance(step, dict):
        return "UNKNOWN"
    return status_token(step.get("status"), step.get("conclusion"))


def flatten_api(data: object, key: str) -> list[dict] | None:
    """Accept ``gh api --paginate --slurp`` page arrays and single objects."""
    if isinstance(data, dict):
        if isinstance(data.get(key), list):
            return [item for item in data[key] if isinstance(item, dict)]
        if key == "jobs" and ("steps" in data or "name" in data):
            return [data]
        if key == "artifacts" and ("name" in data or "id" in data):
            return [data]
        return None
    if isinstance(data, list):
        if any(isinstance(item, dict) and isinstance(item.get(key), list) for item in data):
            entries: list[dict] = []
            for item in data:
                if isinstance(item, dict) and isinstance(item.get(key), list):
                    entries.extend(entry for entry in item[key] if isinstance(entry, dict))
            return entries
        return [item for item in data if isinstance(item, dict)]
    return None


def read_api(path_value: str, key: str) -> tuple[list[dict], bool, str | None]:
    path = Path(path_value)
    if not path.is_file():
        return [], False, f"文件不存在: {path}"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [], False, f"JSON 无效: {error}"
    entries = flatten_api(data, key)
    if entries is None:
        return [], False, "响应结构无法识别"
    return entries, True, None


def dedupe_jobs(entries: list[dict]) -> list[dict]:
    chosen: dict[object, dict] = {}
    for job in entries:
        identity = job.get("id")
        if identity is None:
            identity = (job.get("name"), job.get("started_at"), job.get("completed_at"))
        chosen[identity] = job
    return list(chosen.values())


def job_group(name: str) -> str | None:
    for group in ("preflight", "platform", "client", "shared"):
        if re.match(rf"^{re.escape(group)}(?:\s|$|\()", name):
            return group
    return None


def node_token_match(name: str, node: str) -> bool:
    return re.search(rf"(?<![0-9A-Za-z.]){re.escape(node)}(?![0-9A-Za-z.])", name) is not None


def select_job(jobs: list[dict], group: str, node: str | None = None) -> dict | None:
    candidates = []
    for job in jobs:
        name = str(job.get("name") or "").strip()
        if job_group(name) != group:
            continue
        if group in ("platform", "client") and (not node or not node_token_match(name, node)):
            continue
        candidates.append(job)
    if not candidates:
        return None

    def rank(job: dict) -> tuple:
        try:
            identity = int(job.get("id") or 0)
        except (TypeError, ValueError):
            identity = 0
        return (1 if job.get("status") == "completed" else 0,
                1 if job.get("conclusion") else 0,
                str(job.get("started_at") or ""), identity)

    return max(candidates, key=rank)


def step_lookup(job: dict | None, name: str | None) -> dict | None:
    if not isinstance(job, dict) or not name:
        return None
    steps = job.get("steps")
    if not isinstance(steps, list):
        return None
    for step in reversed(steps):
        if isinstance(step, dict) and str(step.get("name") or "").strip() == name:
            return step
    return None


def required_steps(group: str) -> tuple[str, ...]:
    return {
        "preflight": PREFLIGHT_REQUIRED_STEPS,
        "platform": PLATFORM_REQUIRED_STEPS,
        "client": CLIENT_REQUIRED_STEPS,
        "shared": SHARED_REQUIRED_STEPS,
    }.get(group, ())


def missing_required_steps(job: dict | None, group: str) -> list[str]:
    if job is None:
        return list(required_steps(group))
    return [name for name in required_steps(group) if step_lookup(job, name) is None]


def skipped_required_steps(job: dict | None, group: str) -> list[str]:
    if job is None:
        return []
    skipped = []
    for name in required_steps(group):
        step = step_lookup(job, name)
        if step is not None and step_token(step) == "SKIP":
            skipped.append(name)
    return skipped


def compile_succeeded(job: dict | None, group: str) -> bool:
    step = step_lookup(job, COMPILE_STEPS.get(group))
    return step is not None and step_token(step) == "PASS"


def snapshot_score(snapshot: dict) -> int:
    score = sum(1 for section in ("unit", "spotbugs", "jacoco", "jar")
                if snapshot.get(section) is not None)
    for section in ("server", "client"):
        value = snapshot.get(section)
        if isinstance(value, dict):
            score += sum(1 for item in value.values() if item is not None)
    return score


def load_snapshots(downloads: Path) -> dict[tuple[str, str | None], dict]:
    index: dict[tuple[str, str | None], dict] = {}
    if not downloads.is_dir():
        return index
    for path in sorted(downloads.rglob("*.json")):
        snapshot = load_json(path)
        if not isinstance(snapshot, dict) or snapshot.get("schema") != SNAPSHOT_SCHEMA:
            continue
        suite = snapshot.get("suite")
        if suite not in SUITES:
            continue
        node = snapshot.get("node")
        if suite == "shared":
            node = None
        elif not isinstance(node, str) or not node:
            continue
        key = (suite, node)
        current = index.get(key)
        if current is None or snapshot_score(snapshot) > snapshot_score(current):
            index[key] = snapshot
    return index


def artifact_index(entries: list[dict]) -> dict[str, dict]:
    chosen: dict[str, tuple[tuple, dict]] = {}
    for artifact in entries:
        name = artifact.get("name")
        if not isinstance(name, str) or not name:
            continue
        try:
            identity = int(artifact.get("id") or 0)
        except (TypeError, ValueError):
            identity = 0
        rank = (1 if not artifact.get("expired") else 0,
                str(artifact.get("created_at") or ""), identity)
        current = chosen.get(name)
        if current is None or rank > current[0]:
            chosen[name] = (rank, artifact)
    return {name: artifact for name, (_, artifact) in chosen.items()}


def artifact_url(repo: str, run_id: str, artifact: dict) -> str:
    return (f"https://github.com/{quote(str(repo), safe='/')}/actions/runs/"
            f"{quote(str(run_id), safe='')}/artifacts/{quote(str(artifact.get('id')), safe='')}")


def artifact_cell(artifacts: dict[str, dict], labels: list[tuple[str, str]],
                  repo: str, run_id: str) -> str:
    parts = []
    for label, name in labels:
        artifact = artifacts.get(name)
        if artifact is None:
            continue
        if artifact.get("expired"):
            parts.append(f"{md_cell(label)}（已过期）")
        else:
            parts.append(f"[{md_cell(label)}]({artifact_url(repo, run_id, artifact)})")
    return " · ".join(parts) if parts else "—"


def integer(value: object) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def integer_fields(observation: object, names: tuple[str, ...]) -> list[int] | None:
    if not isinstance(observation, dict):
        return None
    values = [integer(observation.get(name)) for name in names]
    return None if any(value is None for value in values) else values


def format_unit(observation: object) -> str | None:
    fields = integer_fields(observation, ("tests", "failures", "errors", "skipped"))
    if fields is None:
        return None
    tests, failures, errors, skipped = fields
    return f"{tests}（失败 {failures} / 错误 {errors} / 跳过 {skipped}）"


def format_server(observation: object) -> str | None:
    if not isinstance(observation, dict):
        return None
    passed = observation.get("passed")
    failed = observation.get("failed")
    if passed is None and failed is None:
        return None
    if failed:
        return f"{passed if passed is not None else '—'}/{failed}（失败）"
    return str(passed) if passed is not None else f"失败 {failed}"


def format_client(observation: object) -> str | None:
    if not isinstance(observation, dict):
        return None
    passed = observation.get("passed")
    expected = observation.get("expected")
    failed = observation.get("failed")
    if passed is None and expected is None and failed is None:
        return None
    if expected is not None:
        text = f"{passed if passed is not None else '—'}/{expected}"
    else:
        text = f"{passed if passed is not None else '—'} 类"
    if failed:
        text += f"（失败 {failed}）"
    return text


def format_spotbugs(observation: object) -> str | None:
    fields = integer_fields(observation, ("total", "high", "medium", "low"))
    if fields is None:
        return None
    total, high, medium, low = fields
    return f"{total}（高 {high} / 中 {medium} / 低 {low}）"


def format_jacoco(observation: object) -> str | None:
    fields = integer_fields(observation, ("covered", "missed"))
    if fields is None:
        return None
    covered, missed = fields
    percent = integer(observation.get("percent")) if isinstance(observation, dict) else None
    percent_text = f"{percent}%" if percent is not None else "—"
    return f"{percent_text}（覆盖 {covered} / 未覆盖 {missed}）"


def evidence_cell(token: str, value: str | None) -> str:
    if value is not None:
        return f"{token} {value}"
    if token == "PASS":
        return "MISSING"
    if token == "—":
        return "—"
    return f"{token} / MISSING"


def optional_token(tokens: list[str]) -> str:
    if not tokens:
        return "UNKNOWN"
    return max(tokens, key=lambda value: STATUS_ORDER.get(value, STATUS_ORDER["UNKNOWN"]))


def parse_upstreams(values: list[str] | None) -> tuple[list[tuple[str, str]], list[str]]:
    entries: list[tuple[str, str]] = []
    issues: list[str] = []
    for raw in values or []:
        text = str(raw)
        if "=" not in text:
            issues.append(f"上游参数非法（应为 name=result）：{text}")
            entries.append((text, "unknown"))
            continue
        name, result = text.split("=", 1)
        name = name.strip()
        result = result.strip()
        if not name:
            issues.append(f"上游参数缺少名称：{text}")
            entries.append(("—", result or "unknown"))
            continue
        if not result:
            issues.append(f"上游参数缺少结果：{text}")
            result = "unknown"
        entries.append((name, result))
    return entries, issues


def server_evidence(value: object) -> bool:
    return isinstance(value, dict) and value.get("passed") is not None


def client_evidence(value: object) -> bool:
    return isinstance(value, dict) and (value.get("passed") is not None
                                        or value.get("expected") is not None
                                        or value.get("failed") is not None)


def missing_snapshot_evidence(snapshot: object, suite: str) -> list[str]:
    if not isinstance(snapshot, dict):
        return ["snapshot"]
    missing: list[str] = []
    if suite in ("platform", "shared"):
        if integer_fields(snapshot.get("unit"),
                          ("tests", "failures", "errors", "skipped")) is None:
            missing.append("unit")
        if integer_fields(snapshot.get("spotbugs"),
                          ("total", "high", "medium", "low")) is None:
            missing.append("spotbugs")
        if integer_fields(snapshot.get("jacoco"), ("covered", "missed")) is None:
            missing.append("jacoco")
        jar = snapshot.get("jar")
        if not isinstance(jar, dict) or not jar.get("name"):
            missing.append("jar")
    if suite == "platform":
        server = snapshot.get("server")
        for backend in SERVER_BACKENDS:
            if not server_evidence(backend_value(server, backend)):
                missing.append(f"server.{backend}")
    if suite == "client":
        client = snapshot.get("client")
        for backend in SERVER_BACKENDS:
            if not client_evidence(backend_value(client, backend)):
                missing.append(f"client.{backend}")
    return missing


def snapshot_section(snapshot: dict | None, section: str) -> object:
    return snapshot.get(section) if isinstance(snapshot, dict) else None


def evaluate_job(job: dict | None, group: str, label: str, issues: list[str]) -> str:
    token = job_token(job)
    if job is None:
        issues.append(f"jobs API 缺少{label}")
        return token
    if token != "PASS":
        issues.append(f"{label} 结论为 {token}")
    missing = missing_required_steps(job, group)
    skipped = skipped_required_steps(job, group)
    if token == "PASS":
        if missing:
            issues.append(f"成功{label} 缺少必要步骤: {', '.join(missing)}")
        if skipped:
            issues.append(f"成功{label} 的必要步骤被跳过: {', '.join(skipped)}")
    elif compile_succeeded(job, group) and skipped:
        issues.append(f"编译成功后{label} 的必需步骤被跳过: {', '.join(skipped)}")
    return token


def evaluate_snapshot(snapshot: dict | None, suite: str, token: str,
                      label: str, issues: list[str]) -> None:
    if token != "PASS":
        return
    if snapshot is None:
        issues.append(f"成功{label} 缺少证据快照")
        return
    missing = missing_snapshot_evidence(snapshot, suite)
    if missing:
        issues.append(f"成功{label} 的证据快照缺少: {', '.join(missing)}")


def command_summary(args: argparse.Namespace) -> None:
    root = repository_root(args.root)
    nodes = load_versions(root)
    mod_version = read_properties(root / "gradle.properties").get("mod_version", "?")
    jobs, jobs_ok, jobs_error = read_api(args.jobs, "jobs")
    jobs = dedupe_jobs(jobs) if jobs_ok else []
    artifacts_raw, artifacts_ok, artifacts_error = read_api(args.artifacts, "artifacts")
    artifacts = artifact_index(artifacts_raw) if artifacts_ok else {}
    snapshots = load_snapshots(Path(args.downloads))
    upstreams, issues = parse_upstreams(args.upstream)
    if not jobs_ok:
        issues.append(f"jobs API 不可用：{jobs_error}")
    elif not jobs:
        issues.append("jobs API 未返回任何 job")
    if not artifacts_ok:
        issues.append(f"artifacts API 不可用：{artifacts_error}")
    for name, result in upstreams:
        if result != "success":
            issues.append(f"上游 {name} 结论为 {result}")

    preflight_job = select_job(jobs, "preflight") if jobs else None
    if jobs:
        evaluate_job(preflight_job, "preflight", "preflight 任务", issues)
    shared_job = select_job(jobs, "shared") if jobs else None
    shared_token = (evaluate_job(shared_job, "shared", "shared 任务", issues)
                    if jobs else "UNKNOWN")
    shared_snapshot = snapshots.get(("shared", None))
    evaluate_snapshot(shared_snapshot, "shared", shared_token, "shared 任务", issues)
    shared_build = step_token(step_lookup(shared_job, "Shared pure tests, jar, JaCoCo and SpotBugs"))
    shared_jar = snapshot_section(shared_snapshot, "jar")
    shared_jar_name = shared_jar.get("name") if isinstance(shared_jar, dict) else None
    shared_cells = [
        shared_build,
        evidence_cell(step_token(step_lookup(shared_job, "Shared unit test summary")),
                      format_unit(snapshot_section(shared_snapshot, "unit"))),
        evidence_cell(step_token(step_lookup(shared_job, "Shared SpotBugs summary")),
                      format_spotbugs(snapshot_section(shared_snapshot, "spotbugs"))),
        evidence_cell(step_token(step_lookup(shared_job, "Shared JaCoCo summary")),
                      format_jacoco(snapshot_section(shared_snapshot, "jacoco"))),
        evidence_cell(shared_build, shared_jar_name),
        artifact_cell(artifacts, [("reports", "shared-reports")], args.repo, args.run_id),
    ]

    rows = []
    compile_notes = []
    for node in nodes:
        platform_job = select_job(jobs, "platform", node) if jobs else None
        client_job = select_job(jobs, "client", node) if jobs else None
        platform_token = (evaluate_job(platform_job, "platform", f"平台任务 {node}", issues)
                          if jobs else "UNKNOWN")
        client_token = (evaluate_job(client_job, "client", f"客户端任务 {node}", issues)
                        if jobs else "UNKNOWN")
        platform_snapshot = snapshots.get(("platform", node))
        client_snapshot = snapshots.get(("client", node))
        evaluate_snapshot(platform_snapshot, "platform", platform_token, f"平台任务 {node}", issues)
        evaluate_snapshot(client_snapshot, "client", client_token, f"客户端任务 {node}", issues)

        build_token = step_token(step_lookup(platform_job, "Build, unit tests and SpotBugs"))
        unit_value = format_unit(snapshot_section(platform_snapshot, "unit"))
        server_values = {
            backend: format_server(backend_value(
                snapshot_section(platform_snapshot, "server"), backend))
            for backend in SERVER_BACKENDS
        }
        client_values = {
            backend: format_client(backend_value(
                snapshot_section(client_snapshot, "client"), backend))
            for backend in SERVER_BACKENDS
        }
        optional_tokens = [
            step_token(step_lookup(platform_job, "Optional dependency legacy server GameTest")),
            step_token(step_lookup(platform_job, "Optional dependency foundation server GameTest")),
            step_token(step_lookup(client_job, "Optional dependency legacy client GameTest")),
            step_token(step_lookup(client_job, "Optional dependency foundation client GameTest")),
        ]
        jar = snapshot_section(platform_snapshot, "jar")
        links = artifact_cell(artifacts, [
            ("jar", f"SyncMaterial-{node}"),
            ("reports", f"reports-{node}"),
            ("server logs", f"server-gametest-{node}"),
            ("client logs", f"client-gametest-{node}"),
        ], args.repo, args.run_id)
        if links == "—" and isinstance(jar, dict) and jar.get("name"):
            links = f"{md_cell(jar['name'])}（无链接）"

        platform_compile = step_lookup(platform_job, COMPILE_STEPS["platform"])
        client_compile = step_lookup(client_job, COMPILE_STEPS["client"])
        if platform_compile is not None or client_compile is not None:
            compile_notes.append(
                f"- {md_cell(node)}: 平台 "
                f"{step_token(platform_compile) if platform_compile is not None else '—'}"
                f" / 客户端 {step_token(client_compile) if client_compile is not None else '—'}")

        java = read_properties(root / "versions" / node / "gradle.properties").get("java_version", "?")
        rows.append("| " + " | ".join([
            f"{md_cell(node)}<br>Java {md_cell(java)}",
            build_token,
            evidence_cell(build_token, unit_value),
            evidence_cell(step_token(step_lookup(platform_job, "Legacy server GameTest")),
                          server_values["legacy"]),
            evidence_cell(step_token(step_lookup(platform_job, "Foundation server GameTest")),
                          server_values["foundation"]),
            evidence_cell(step_token(step_lookup(client_job, "Legacy client GameTest")),
                          client_values["legacy"]),
            evidence_cell(step_token(step_lookup(client_job, "Foundation client GameTest")),
                          client_values["foundation"]),
            optional_token(optional_tokens),
            evidence_cell(step_token(step_lookup(platform_job, "SpotBugs summary")),
                          format_spotbugs(snapshot_section(platform_snapshot, "spotbugs"))),
            links,
        ]) + " |")

    status = "❌ FAIL" if issues else "✅ PASS"
    api_status = f"jobs {'正常' if jobs_ok else '不可用'} · artifacts {'正常' if artifacts_ok else '不可用'}"
    lines = [
        "# SyncMaterial CI 汇总",
        "",
        "| 项目 | 值 |",
        "| --- | --- |",
        f"| 总状态 | **{status}** |",
        f"| mod_version | {md_cell(mod_version)} |",
        f"| 版本节点 | {' / '.join(md_cell(node) for node in nodes)} |",
        f"| Run | [#{md_cell(args.run_id)}](https://github.com/"
        f"{quote(str(args.repo), safe='/')}/actions/runs/{quote(str(args.run_id), safe='')}) |",
        f"| 证据 API | {api_status} |",
        "",
        "## 守卫 / 上游",
        "",
        "| 名称 | 结论 |",
        "| --- | --- |",
    ]
    if upstreams:
        for name, result in upstreams:
            lines.append(f"| {md_cell(name)} | "
                         f"{'PASS' if result == 'success' else md_cell(result.upper() or 'UNKNOWN')} |")
    else:
        lines.append("| — | — |")
    lines.extend([
        "",
        "## 共享模块",
        "",
        "| 构建 | 单测 | SpotBugs | JaCoCo | jar | 报告 |",
        "| --- | --- | --- | --- | --- | --- |",
        "| " + " | ".join(shared_cells) + " |",
        "",
        "## 平台节点",
        "",
        "| 节点 | 构建 | 单测 | Legacy 服务端 | Foundation 服务端 | Legacy 客户端 | "
        "Foundation 客户端 | 缺依赖 | SpotBugs | 产物 / 报告 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ])
    lines.extend(rows)
    if compile_notes:
        lines.extend(["", "### 编译步骤", "", *compile_notes])
    if issues:
        lines.extend(["", "## 问题", "", *[f"- {md_cell(issue)}" for issue in issues]])
    print("\n".join(lines))
    if issues:
        print(f"::error::CI 汇总未通过：{len(issues)} 个问题")
        raise SystemExit(1)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    matrix = sub.add_parser("matrix", help="从 versions.json 生成 GitHub Actions matrix")
    matrix.add_argument("--root", default=".")
    matrix.set_defaults(func=command_matrix)

    info = sub.add_parser("info", help="输出预检信息汇总（不承担实质守卫）")
    info.add_argument("--root", default=".")
    info.set_defaults(func=command_info)

    source = sub.add_parser("source-hash", help="计算根源码哈希")
    source.add_argument("--root", default=".")
    source.add_argument("--path", default="src")
    source.add_argument("--out", required=True)
    source.set_defaults(func=command_source_hash)

    compare = sub.add_parser("source-compare", help="比较构建前后根源码哈希")
    compare.add_argument("--root", default=".")
    compare.add_argument("--path", default="src")
    compare.add_argument("--before", required=True)
    compare.set_defaults(func=command_source_compare)

    unit = sub.add_parser("unit", help="汇总 JUnit 结果")
    unit.add_argument("--suite", required=True)
    unit.add_argument("--node")
    unit.add_argument("--results", required=True)
    unit.set_defaults(func=command_unit)

    server = sub.add_parser("server", help="汇总服务端 GameTest 通过数")
    server.add_argument("--backend", required=True)
    server.add_argument("--run-dir", required=True)
    server.set_defaults(func=command_server)

    client = sub.add_parser("client", help="汇总客户端逐类 START/PASS/FAIL")
    client.add_argument("--mod", required=True)
    client.add_argument("--metadata", required=True)
    client.add_argument("--run-dir", required=True)
    client.add_argument("--node")
    client.set_defaults(func=command_client)

    spotbugs = sub.add_parser("spotbugs", help="汇总 SpotBugs 条数")
    spotbugs.add_argument("--suite", required=True)
    spotbugs.add_argument("--node")
    spotbugs.add_argument("--report", required=True)
    spotbugs.set_defaults(func=command_spotbugs)

    jacoco = sub.add_parser("jacoco", help="输出 JaCoCo 指令覆盖率")
    jacoco.add_argument("--suite", required=True)
    jacoco.add_argument("--node")
    jacoco.add_argument("--report", required=True)
    jacoco.set_defaults(func=command_jacoco)

    artifact = sub.add_parser("artifact", help="核验发行 jar 名称与内容")
    artifact.add_argument("--root", default=".")
    artifact.add_argument("--node", required=True)
    artifact.set_defaults(func=command_artifact)

    generated = sub.add_parser("generated", help="汇总 Stonecutter 生成源")
    generated.add_argument("--root", default=".")
    generated.add_argument("--node", required=True)
    generated.set_defaults(func=command_generated)

    aggregates = sub.add_parser("aggregates", help="核验根聚合任务 dry-run 图")
    aggregates.add_argument("--root", default=".")
    aggregates.add_argument("--log", required=True)
    aggregates.set_defaults(func=command_aggregates)

    release = sub.add_parser("release-set", help="核验发布 jar 集合")
    release.add_argument("--root", default=".")
    release.add_argument("--dist", required=True)
    release.set_defaults(func=command_release_set)

    snapshot = sub.add_parser("snapshot", help="记录实际测试、静态检查与产物证据")
    snapshot.add_argument("--root", default=".")
    snapshot.add_argument("--suite", required=True, choices=SUITES)
    snapshot.add_argument("--node")
    snapshot.add_argument("--out", required=True)
    snapshot.set_defaults(func=command_snapshot)

    summary = sub.add_parser("summary", help="汇总 jobs/artifacts API 与下载的证据快照")
    summary.add_argument("--root", default=".")
    summary.add_argument("--jobs", required=True)
    summary.add_argument("--artifacts", required=True)
    summary.add_argument("--downloads", required=True)
    summary.add_argument("--repo", required=True)
    summary.add_argument("--run-id", required=True)
    summary.add_argument("--upstream", action="append", default=[])
    summary.set_defaults(func=command_summary)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
