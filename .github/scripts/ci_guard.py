#!/usr/bin/env python3
"""CI-only helpers for the SyncMaterial multi-version matrix.

This file intentionally does not re-implement architecture, package, unit,
server, client or SpotBugs gates. ``scripts/verify_multiversion.py`` is the
single authority for those; the commands here produce matrix values, source
hashes, job summaries, task-graph checks and release artifact-set checks.
Invoke with ``python3 -I`` from the repository root.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from collections import Counter
from pathlib import Path
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


def junit_totals(results: Path) -> tuple[int, int, int, int, int]:
    files = sorted(results.glob("*.xml"))
    total = failures = errors = skipped = 0
    for file in files:
        try:
            document = ElementTree.parse(file).getroot()
        except ElementTree.ParseError:
            continue
        suites = [document] if document.tag == "testsuite" else list(document.iter("testsuite"))
        for suite in suites:
            total += int(suite.get("tests", len(suite.findall("testcase"))))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", suite.get("disabled", 0)))
    return total, failures, errors, skipped, len(files)


def command_unit(args: argparse.Namespace) -> None:
    total, failures, errors, skipped, files = junit_totals(Path(args.results))
    label = args.suite + (f" {args.node}" if args.node else "")
    if not files:
        note(f"- 单元测试（{label}）: 未找到 JUnit 结果（实质判定由 verify_multiversion unit scope 负责）")
        return
    note(f"- 单元测试（{label}）: {total} 个，失败 {failures}，错误 {errors}，"
         f"跳过 {skipped}（{files} 个结果文件；精确基线由 verify_multiversion 负责）")


def command_server(args: argparse.Namespace) -> None:
    log = Path(args.run_dir) / "logs" / "latest.log"
    if not log.is_file():
        note(f"- 服务端 GameTest（{args.backend}）: 未找到日志（实质判定由 verify_multiversion server scope 负责）")
        return
    matches = re.findall(r"All (\d+) required tests passed", log.read_text(encoding="utf-8", errors="replace"))
    if not matches:
        note(f"- 服务端 GameTest（{args.backend}）: 未出现通过标记（实质判定由 verify_multiversion server scope 负责）")
        return
    note(f"- 服务端 GameTest（{args.backend}）: 最近一次报告 {matches[-1]} 个通过"
         f"（精确基线由 verify_multiversion 负责）")


def read_client_log(run_dir: Path) -> tuple[Path | None, str]:
    for name in ("latest.log", "debug.log"):
        candidate = run_dir / "logs" / name
        if candidate.is_file():
            text = candidate.read_text(encoding="utf-8", errors="replace")
            if "SYNCMATERIAL_CLIENT_TEST_" in text:
                return candidate, text
    return None, ""


def command_client(args: argparse.Namespace) -> None:
    metadata = load_json(Path(args.metadata))
    classes = {value for value, _ in entrypoints(metadata, "fabric-client-gametest")}
    log, text = read_client_log(Path(args.run_dir))
    starts: Counter[str] = Counter()
    passes: Counter[str] = Counter()
    failures = 0
    for kind, mod_id, class_name in CLIENT_REPORT.findall(text):
        if mod_id != args.mod:
            continue
        if kind == "START":
            starts[class_name] += 1
        elif kind == "PASS":
            passes[class_name] += 1
        else:
            failures += 1
    label = args.mod + (f" {args.node}" if args.node else "")
    if log is None:
        note(f"- 客户端测试（{label}）: 未找到真实上报标签"
             f"（实质判定由 verify_multiversion client scope 负责）")
        return
    missing = sorted(classes - set(passes))
    unexpected = sorted(set(passes) - classes)
    note(f"- 客户端测试（{label}）: metadata {len(classes)} 类，START {len(starts)}，"
         f"PASS {len(passes)}，FAIL {failures}，missing {len(missing)}，unexpected {len(unexpected)}"
         f"（精确逐类判定由 verify_multiversion client scope 负责，{log.name}）")


def command_spotbugs(args: argparse.Namespace) -> None:
    report = Path(args.report)
    if not report.is_file():
        note(f"- SpotBugs（{args.suite}{' ' + args.node if args.node else ''}）: 报告未生成"
             f"（实质判定由 verify_multiversion spotbugs scope 负责）")
        return
    try:
        document = ElementTree.parse(report).getroot()
    except ElementTree.ParseError:
        note(f"- SpotBugs（{args.suite}{' ' + args.node if args.node else ''}）: 报告无法解析")
        return
    bugs = document.findall("BugInstance")
    priorities = Counter(bug.get("priority") for bug in bugs)
    names = {"1": "高", "2": "中", "3": "低"}
    parts = [f"{names.get(key, key)} {priorities[key]}" for key in ("1", "2", "3") if priorities[key]]
    note(f"- SpotBugs（{args.suite}{' ' + args.node if args.node else ''}）: 共 {len(bugs)} 条"
         f"（{'，'.join(parts) if parts else '无'}；基线由 verify_multiversion 负责）")


def command_jacoco(args: argparse.Namespace) -> None:
    report = Path(args.report)
    if not report.is_file():
        note(f"- 覆盖率（{args.suite}{' ' + args.node if args.node else ''}）: 报告未生成")
        return
    try:
        document = ElementTree.parse(report).getroot()
    except ElementTree.ParseError:
        note("- 覆盖率: 报告无法解析")
        return
    instruction = next((item for item in document.findall("counter") if item.get("type") == "INSTRUCTION"), None)
    if instruction is None:
        note("- 覆盖率: 报告缺少 INSTRUCTION counter")
        return
    missed = int(instruction.get("missed", 0))
    covered = int(instruction.get("covered", 0))
    percent = covered * 100 // (missed + covered) if (missed + covered) else 0
    note(f"- 覆盖率（{args.suite}{' ' + args.node if args.node else ''}，JaCoCo 指令级）: "
         f"{percent}%（覆盖 {covered} / 未覆盖 {missed}）")


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
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
