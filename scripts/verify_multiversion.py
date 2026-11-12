#!/usr/bin/env python3
"""Read-only guard for the multi-version SyncMaterial tree.

It never runs Gradle/Minecraft and never writes to the repository.  It checks:
  * versions/versions.json vs settings/stonecutter registration (dynamic or literal);
  * per-node gradle.properties and absence of copied business sources;
  * shared module: Java 21, no MC/Fabric/MaLiLib references, no Stonecutter conditions;
  * production entrypoints/mixin classes exist;
  * every GameTest/client class is registered, entries are non-empty and server
    filters cannot silently select zero tests;
  * platform/shared JUnit XML and SpotBugs XML;
  * prod jars: Minecraft/Java declaration, class major, shared nested once,
    SQLite nested once, recursive closure without duplicate classes (multi-release
    entries compared by logical class path), no test/fixture classes;
  * server GameTest completion and client START/PASS/FAIL reporting tags
    (substring match, so logger prefixes are fine).

Exit codes: 0 pass, 1 failure, 2 usage/config error, 3 evidence not complete yet
(no failure).  --strict makes 3 -> 1, --allow-missing makes 3 -> 0.

CI matrix examples (one node per job is safe; other nodes are not required):
  python3 -I scripts/verify_multiversion.py --root . --scope static
  python3 -I scripts/verify_multiversion.py --root . --scope shared
  python3 -I scripts/verify_multiversion.py --root . --node 1.21.7 --scope unit
  python3 -I scripts/verify_multiversion.py --root . --node 1.21.7 --scope package
  python3 -I scripts/verify_multiversion.py --root . --node 1.21.7 --scope spotbugs
  python3 -I scripts/verify_multiversion.py --root . --node 1.21.7 --scope server
  python3 -I scripts/verify_multiversion.py --root . --node 1.21.7 --scope client
  python3 -I scripts/verify_multiversion.py --root . --node 1.21.7 --scope optional
  python3 -I scripts/verify_multiversion.py --root . --scope all
"""

from __future__ import annotations

import argparse
import fnmatch
import io
import json
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

SCOPES = ("static", "shared", "unit", "server", "client", "optional",
          "optional-server", "optional-client", "package", "spotbugs")
PASS, WARN, UNAVAILABLE, FAIL = "PASS", "WARN", "UNAVAILABLE", "FAIL"
RANK = {PASS: 0, WARN: 1, UNAVAILABLE: 2, FAIL: 3}
VER = r"\d+\.\d+(?:\.\d+)?"
FORBIDDEN_TEXT = ("net.minecraft", "net.fabricmc", "fi.dy.masa")
FORBIDDEN_BYTES = (b"net/minecraft", b"net/fabricmc", b"fi/dy/masa")
ARGS = None


class Guard:
    def __init__(self, root, baseline):
        self.root = root
        self.baseline = baseline
        self.results = {}
        self.catalog = None
        self.catalog_incomplete = []
        self.mod_version = prop(root / "gradle.properties", "mod_version")
        self.test_roots = [root / "src/gametest/java", root / "src/foundationtest/java",
                           root / "src/optionaltest/java", root / "src/test/java",
                           root / "shared/src/test/java"]
        self.main_roots = [root / "src/main/java", root / "shared/src/main/java"]
        self.manifest_problems = []
        self.versions = self.read_manifest()

    # ---------------------------------------------------------------- results
    def add(self, check, status, message):
        old = self.results.get(check)
        if old is None or RANK[status] > RANK[old[0]]:
            self.results[check] = (status, message)

    def finish(self):
        rows = sorted((k, v[0], v[1]) for k, v in self.results.items())
        counts = Counter(status for _, status, _ in rows)
        if counts[FAIL]:
            code = 1
        elif counts[UNAVAILABLE]:
            code = 1 if ARGS.strict else (0 if ARGS.allow_missing else 3)
        else:
            code = 0
        if ARGS.json:
            print(json.dumps({"root": str(self.root), "nodes": getattr(self, "nodes", []),
                              "scopes": ARGS.scopes, "summary": dict(counts, exit_code=code),
                              "results": [{"check": c, "status": s, "message": m}
                                          for c, s, m in rows]}, ensure_ascii=False, indent=2))
        else:
            for check, status, message in rows:
                print("[%s] %-52s %s" % (status, check, message))
            print("\nSummary: %d pass, %d warn, %d unavailable, %d fail -> exit %d"
                  % (counts[PASS], counts[WARN], counts[UNAVAILABLE], counts[FAIL], code))
            if counts[UNAVAILABLE]:
                print("UNAVAILABLE is not a pass: evidence is incomplete.")
        return code

    # --------------------------------------------------------------- manifest
    def read_manifest(self):
        path = self.root / "versions/versions.json"
        try:
            values = json.loads(path.read_text(encoding="utf-8"))["minecraft"]
        except Exception as exc:
            self.manifest_problems.append("cannot read versions/versions.json: %s" % exc)
            return list(self.baseline.get("expected_versions", []))
        expected = self.baseline.get("expected_versions", [])
        if not isinstance(values, list) or not values or not all(isinstance(v, str) for v in values):
            self.manifest_problems.append("minecraft must be a non-empty string list")
            return list(expected)
        if len(set(values)) != len(values):
            self.manifest_problems.append("duplicate versions: %s" % values)
        if any(not re.fullmatch(VER, v) for v in values):
            self.manifest_problems.append("invalid version strings: %s" % values)
        if expected and set(values) != set(expected):
            self.manifest_problems.append("manifest %s != baseline expected %s" % (values, expected))
        return values

    # ------------------------------------------------------------- static code
    def check_static(self):
        self.nodes = list(self.versions)
        problems = list(self.manifest_problems)
        settings = read(self.root / "settings.gradle")
        stone = read(self.root / "stonecutter.gradle")
        if not re.search(r"include\s*\(?\s*['\"]shared['\"]", settings):
            problems.append("settings.gradle does not include shared")
        if "dev.kikugie.stonecutter" not in settings:
            problems.append("settings.gradle does not apply Stonecutter")
        problems.extend(registration_problems(settings, "versions", self.versions))
        problems.extend(registration_problems(stone, "nodes", self.versions))
        loom = parse_props(self.root / "gradle.properties").get("loom_version")
        if not loom:
            problems.append("root gradle.properties has no loom_version")
        elif not all('version "${loom_version}"' in read(path)
                     for path in (self.root / "build.gradle", self.root / "shared/build.gradle")):
            problems.append("Loom plugin version is not read from root loom_version in both build files")
        for v in re.findall(r"vcsVersion\s*=\s*['\"](" + VER + r")['\"]", settings):
            if v not in self.versions:
                problems.append("vcsVersion %s is not in versions.json" % v)
        java_expected = self.baseline.get("java_versions", {})
        for node in self.versions:
            path = self.root / "versions" / node / "gradle.properties"
            props = parse_props(path)
            if not props:
                problems.append("missing/empty versions/%s/gradle.properties" % node)
                continue
            for key in ("minecraft_version", "minecraft_mapping", "minecraft_dependency",
                        "java_version", "fabric_api_version", "malilib_version",
                        "malilib_dependency", "litematica_version", "litematica_dependency",
                        "syncmatica_version", "syncmatica_dependency"):
                if not props.get(key):
                    problems.append("%s: missing %s" % (node, key))
            if props.get("minecraft_version") != node:
                problems.append("%s: minecraft_version=%r" % (node, props.get("minecraft_version")))
            if props.get("minecraft_dependency") != node:
                problems.append("%s: minecraft_dependency must be exactly %r" % (node, node))
            if node in java_expected and props.get("java_version") != str(java_expected[node]):
                problems.append("%s: java_version=%r, expected %s"
                                % (node, props.get("java_version"), java_expected[node]))
            problems.extend(node_copy_problems(self.root, node))
        known = set(self.versions)
        nodes_dir = self.root / "versions"
        if nodes_dir.is_dir():
            for child in nodes_dir.iterdir():
                if child.is_dir() and child.name not in known and (child / "gradle.properties").is_file():
                    problems.append("versions/%s is not registered in versions.json" % child.name)
        self.add("versions", FAIL if problems else PASS,
                 "; ".join(problems[:12]) if problems else "manifest, settings/stonecutter, node properties and source layout agree")

        shared_problems = shared_source_problems(self.root)
        self.add("shared.sources", FAIL if shared_problems else PASS,
                 "; ".join(shared_problems[:12]) if shared_problems else
                 "shared is Java 21 with no MC/Fabric/MaLiLib references or Stonecutter conditions")

        main_problems = main_metadata_problems(self.root, self.main_roots)
        self.add("main.entrypoints_mixins", FAIL if main_problems else PASS,
                 "; ".join(main_problems[:12]) if main_problems else
                 "production entrypoints and every mixin class exist and are registered")

        self.load_catalog("static")
        self.check_registration_and_filters()
        self.check_optional_config()

    # ------------------------------------------------------------- shared scope
    def check_shared(self):
        problems = shared_source_problems(self.root)
        self.add("shared.sources", FAIL if problems else PASS,
                 "; ".join(problems[:12]) if problems else "shared isolation rules hold")
        junit = junit_summary(self.root / "shared/build/test-results/test")
        if junit is None:
            self.add("shared.junit", UNAVAILABLE, "shared JUnit XML not available")
        else:
            issues = junit_issues(junit, self.baseline, "shared_min_tests")
            self.add("shared.junit", FAIL if issues else PASS,
                     "; ".join(issues) if issues else "%d tests, 0 skipped/failed/errors" % junit["tests"])
        count = spotbugs_count(self.root / "shared/build/reports/spotbugs/main.xml")
        limit = int(self.baseline.get("spotbugs", {}).get("shared_max", 0))
        if count is None:
            self.add("shared.spotbugs", UNAVAILABLE, "shared SpotBugs XML not available")
        else:
            self.add("shared.spotbugs", FAIL if count > limit else PASS,
                     "shared SpotBugs %d (max %d)" % (count, limit))
        rows = spotbugs_instances(self.root / "shared/build/reports/spotbugs/main.xml")
        if rows is not None:
            new_rows = new_spotbugs_findings(rows, self.baseline, "shared")
            self.add("shared.spotbugs.regressions", FAIL if new_rows else PASS,
                     "new findings: %s" % new_rows[:12] if new_rows else "no new shared SpotBugs findings")
        self.check_shared_jar()

    def check_shared_jar(self):
        candidates = [p for p in (self.root / "shared/build/libs").glob("shared-*.jar")
                      if not p.name.endswith("-sources.jar")]
        expected = "shared-%s.jar" % self.mod_version if self.mod_version else None
        if expected is None or not (self.root / "shared/build/libs" / expected).is_file():
            self.add("shared.jar", UNAVAILABLE,
                     "shared jar not built as %s (found %s)" % (expected, [p.name for p in candidates]))
            return
        try:
            zf = zipfile.ZipFile(str(self.root / "shared/build/libs" / expected))
        except Exception as exc:
            self.add("shared.jar", FAIL, "cannot open shared jar: %s" % exc)
            return
        classes = [n for n in zf.namelist() if n.endswith(".class")]
        problems = []
        for name in classes:
            data = zf.read(name)
            if any(marker in data for marker in FORBIDDEN_BYTES):
                problems.append("platform reference in %s" % name)
            if class_major(data) != 65:
                problems.append("non-Java-21 bytecode in %s" % name)
        leaked = sorted(set(classes) & expected_class_paths(self.root, shared_only=True))
        if leaked:
            problems.append("test classes leaked: %s" % leaked[:8])
        if any(not n.startswith("net/syncmaterial/") for n in classes):
            problems.append("shared jar contains non-business packages")
        self.add("shared.jar", FAIL if problems else PASS,
                 "; ".join(problems[:10]) if problems else
                 "%d shared classes, Java 21, no platform/test classes" % len(classes))

    # ------------------------------------------------------------------ test mods
    def load_catalog(self, scope):
        if self.catalog is not None:
            return self.catalog
        catalog = {}
        for mod_id, cfg in self.baseline.get("test_mods", {}).items():
            path = self.root / cfg.get("resources", "")
            check = "metadata.%s" % mod_id
            if not path.is_file():
                self.add(check, UNAVAILABLE, "test metadata not landed: %s" % cfg.get("resources"))
                self.catalog_incomplete.append(mod_id)
                continue
            try:
                data = json.loads(read(path))
            except Exception as exc:
                self.add(check, FAIL, "invalid fabric.mod.json: %s" % exc)
                self.catalog_incomplete.append(mod_id)
                continue
            problems, server, client, methods = validate_test_metadata(self.root, self.main_roots, self.test_roots, mod_id, data)
            min_server = int(cfg.get("server_methods_min", 1))
            min_client = int(cfg.get("client_entries_min", 1))
            expected_client = self.baseline.get("client_entries_expected", {}).get(mod_id)
            if sum(len(v) for v in methods.values()) < min_server:
                problems.append("server methods < baseline minimum %d" % min_server)
            if len(client) < min_client:
                problems.append("client entries < baseline minimum %d" % min_client)
            if expected_client is not None and len(client) != int(expected_client):
                problems.append("client entries %d != baseline expected %d" % (len(client), int(expected_client)))
            if problems:
                self.add(check, FAIL, "; ".join(problems[:12]))
                self.catalog_incomplete.append(mod_id)
            else:
                self.add(check, PASS, "%d server classes/%d methods, %d client classes, adapters=%s"
                         % (len(server), sum(len(v) for v in methods.values()), len(client),
                            sorted(data.get("languageAdapters", {}).keys())))
            catalog[mod_id] = {"data": data, "server": server, "client": client,
                               "methods": methods, "path": path}
        self.catalog = catalog
        return catalog

    def check_registration_and_filters(self):
        catalog = self.catalog or {}
        actual_server, actual_client = scan_actual_tests(self.root, self.test_roots)
        registered_server, registered_client, owner = set(), set(), defaultdict(list)
        for mod_id, info in catalog.items():
            for entry in info["server"]:
                registered_server.add(entry[0])
                owner[entry[0]].append(mod_id)
            for entry in info["client"]:
                registered_client.add(entry[0])
                owner[entry[0]].append(mod_id)
        problems = ["registered in multiple metadata files: %s" % sorted(k for k, v in owner.items() if len(v) > 1)]
        problems = [p for p in problems if not p.endswith("[]")]
        problems += ["unregistered server tests: %s" % sorted(set(actual_server) - registered_server)]
        problems += ["unregistered client tests: %s" % sorted(set(actual_client) - registered_client)]
        problems += ["registered server classes without @GameTest: %s" % sorted(registered_server - set(actual_server))]
        problems += ["registered client classes without FabricClientGameTest: %s" % sorted(registered_client - set(actual_client))]
        problems = [p for p in problems if not p.endswith("[]")]
        if self.catalog_incomplete:
            self.add("metadata.registration", UNAVAILABLE,
                     "cannot fully verify registration; missing metadata %s; %s"
                     % (self.catalog_incomplete, "; ".join(problems[:8])))
        else:
            self.add("metadata.registration", FAIL if problems else PASS,
                     "; ".join(problems[:10]) if problems else
                     "all %d server and %d client test classes registered exactly once"
                     % (len(actual_server), len(actual_client)))

        filter_problems = []
        build = read(self.root / "build.gradle")
        for mod_id, pattern in self.baseline.get("server_filters", {}).items():
            if pattern not in build:
                filter_problems.append("build.gradle lacks %r" % pattern)
                continue
            info = catalog.get(mod_id)
            if info is None:
                self.add("metadata.filters", UNAVAILABLE,
                         "cannot verify %s selection until metadata exists" % mod_id)
                return
            ids = [gametest_id(mod_id, fqcn, method)
                   for fqcn, methods in info["methods"].items() for method in methods]
            matches = [i for i in ids if fnmatch.fnmatchcase(i, pattern)]
            if not matches:
                filter_problems.append("%s matches 0/%d tests" % (pattern, len(ids)))
            elif len(matches) != len(ids):
                filter_problems.append("%s selects %d/%d tests" % (pattern, len(matches), len(ids)))
        self.add("metadata.filters", FAIL if filter_problems else PASS,
                 "; ".join(filter_problems) if filter_problems else "server filters select every registered @GameTest method")

    def check_optional_config(self):
        build = read(self.root / "build.gradle")
        stone = read(self.root / "stonecutter.gradle")
        problems = []
        for name in ("optionalGameTest", "optionalFoundationGameTest",
                     "optionalClientGameTest", "optionalFoundationClientGameTest"):
            run_name = "run" + name[0].upper() + name[1:]
            if name not in build:
                problems.append("optional run config missing: %s" % name)
            if run_name not in stone:
                problems.append("optional aggregate task missing: %s" % run_name)
        if "fabric.debug.disableModIds" not in build:
            problems.append("optional runs do not disable optional mods")
        for token in ("malilib", "litematica", "syncmatica"):
            if token not in build:
                problems.append("optional classpath exclusion lacks %s" % token)
        if "syncmaterial-optional-test" not in build:
            problems.append("optional test mod is not registered")
        if "spotbugsOptionaltest" not in build:
            problems.append("spotbugsOptionaltest is not disabled")
        if "src/optionaltest/resources" not in build:
            problems.append("optionaltest resources sourceSet missing")
        self.add("metadata.optional.config", FAIL if problems else PASS,
                 "; ".join(problems) if problems else "four optional runs and classpath exclusions are configured")

    # ------------------------------------------------------------------- unit
    def check_unit(self, node):
        summary = junit_summary(self.root / "versions" / node / "build/test-results/test")
        if summary is None:
            self.add("unit.%s" % node, UNAVAILABLE, "JUnit XML not available")
            return
        issues = junit_issues(summary, self.baseline, "platform_min_tests")
        overlap = summary["classes"] & source_fqcns(self.root / "shared/src/test/java")
        if overlap:
            issues.append("platform results duplicate shared tests: %s" % sorted(overlap)[:8])
        self.add("unit.%s" % node, FAIL if issues else PASS,
                 "; ".join(issues) if issues else "%d tests, 0 skipped/failed/errors" % summary["tests"])

    # ---------------------------------------------------------------- server
    def check_server(self, node):
        catalog = self.load_catalog("server")
        self.server_run(node, "legacy", "syncmaterial-test",
                        self.root / ".runs" / node / "legacy-server/logs/latest.log", None, catalog)
        self.server_run(node, "foundation", "syncmaterial-foundation-test",
                        self.root / ".runs" / node / "foundation-server/logs/latest.log",
                        self.baseline.get("server_filters", {}).get("syncmaterial-foundation-test"), catalog)

    def server_run(self, node, label, mod_id, log, pattern, catalog, scope="server"):
        check = "%s.%s.%s" % (scope, node, label)
        if not log.is_file() or not read(log).strip():
            self.add(check, UNAVAILABLE, "server GameTest log not available/empty: %s"
                     % log.relative_to(self.root))
            return
        text = read(log)
        problems = []
        if "found no tests" in text.lower():
            problems.append("selection found no tests")
        if re.search(r"\d+\s+required tests failed\s*:\(", text):
            problems.append("required tests failed")
        if re.search(r"\d+\s+optional tests failed", text):
            problems.append("optional tests failed")
        complete = re.findall(r"=========\s*(\d+)\s+GAME TESTS COMPLETE IN\b", text)
        passed = re.findall(r"All\s+(\d+)\s+required tests passed\s*:\)", text)
        if not complete or not passed:
            self.add(check, FAIL if problems else UNAVAILABLE,
                     "; ".join(problems) if problems else "no completed GameTest summary yet")
            return
        count, ok_count = int(complete[-1]), int(passed[-1])
        batch = re.findall(r"Running test environment .*?\((\d+)\s+tests?\)", text)
        if count <= 0 or count != ok_count:
            problems.append("completed=%d passed=%d" % (count, ok_count))
        if batch and int(batch[-1]) != count:
            problems.append("batch=%s completed=%d" % (batch[-1], count))
        expected = self.baseline.get("server_run_test_counts", {}).get(label)
        if expected is not None and count != int(expected):
            problems.append("completed=%d != baseline %d" % (count, int(expected)))
        if pattern:
            selection = re.findall(r"Will run tests matching\s+(\S+)\s+\((\d+)\s+tests?\)", text)
            if not selection:
                problems.append("missing filtered selection line")
            else:
                seen, selected = selection[-1][0], int(selection[-1][1])
                info = catalog.get(mod_id)
                expected_count = (count_filter_matches(info, mod_id, pattern) if info else None)
                if seen != pattern:
                    problems.append("selection pattern %r != %r" % (seen, pattern))
                if selected <= 0:
                    problems.append("filtered selection is zero")
                if expected_count is not None and selected != expected_count:
                    problems.append("selected=%d metadata=%d" % (selected, expected_count))
                if expected_count is not None and count != expected_count:
                    problems.append("completed=%d metadata=%d" % (count, expected_count))
        self.add(check, FAIL if problems else PASS,
                 "; ".join(problems) if problems else "%d/%d required tests passed" % (ok_count, count))

    # ---------------------------------------------------------------- client
    def check_client(self, node):
        catalog = self.load_catalog("client")
        self.client_run(node, "legacy-client", "syncmaterial-test", catalog)
        self.client_run(node, "foundation-client", "syncmaterial-foundation-test", catalog)

    def client_run(self, node, label, mod_id, catalog, scope="client"):
        check = "%s.%s.%s" % (scope, node, label)
        log = self.root / ".runs" / node / label / "logs/latest.log"
        if not log.is_file() or not read(log).strip():
            self.add(check, UNAVAILABLE, "client GameTest log not available/empty: %s"
                     % log.relative_to(self.root))
            return
        info = catalog.get(mod_id)
        if info is None:
            self.add(check, UNAVAILABLE, "metadata for %s is not available" % mod_id)
            return
        tags = client_tags(read(log))
        problems = []
        if any(t[0] == "FAIL" for t in tags):
            problems.append("FAIL tags: %s" % ["%s/%s" % (t[1], t[2]) for t in tags if t[0] == "FAIL"][:8])
        validate_tags(problems, mod_id, [e[0] for e in info["client"]], tags)
        tagged_mods = sorted({t[1] for t in tags} - {mod_id})
        for other in tagged_mods:
            other_info = catalog.get(other)
            if other_info is None:
                continue
            validate_tags(problems, other, [e[0] for e in other_info["client"]], tags)
        if not tags:
            problems.append("no SYNCMATERIAL_CLIENT_TEST_START/PASS/FAIL tags; cannot prove execution")
        self.add(check, FAIL if problems else PASS,
                 "; ".join(problems[:10]) if problems else
                 "%d client tests START+PASS exactly once" % len(info["client"]))

    # -------------------------------------------------------------- optional
    def check_optional(self, node, environment="all"):
        catalog = self.load_catalog("optional")
        mod_id = "syncmaterial-optional-test"
        if mod_id not in catalog:
            self.add("optional.%s" % node, UNAVAILABLE,
                     "optional test metadata/classes not landed")
            return
        pattern = self.baseline.get("server_filters", {}).get(mod_id)
        if environment in ("all", "server"):
            self.server_run(node, "optional-legacy-server", mod_id,
                            self.root / ".runs" / node / "optional-legacy-server/logs/latest.log",
                            None, catalog, "optional")
            self.server_run(node, "optional-foundation-server", mod_id,
                            self.root / ".runs" / node / "optional-foundation-server/logs/latest.log",
                            pattern, catalog, "optional")
        if environment in ("all", "client"):
            self.client_run(node, "optional-legacy-client", mod_id, catalog, "optional")
            self.client_run(node, "optional-foundation-client", mod_id, catalog, "optional")

    # --------------------------------------------------------------- package
    def check_package(self, node):
        check = "package.%s" % node
        libs = self.root / "versions" / node / "build/libs"
        expected = libs / ("SyncMaterial-%s-%s.jar" % (node, self.mod_version))
        jars = sorted(p for p in libs.glob("SyncMaterial-*.jar")
                      if not p.name.endswith(("-sources.jar", "-javadoc.jar")))
        if not expected.is_file():
            if jars:
                self.add(check, FAIL, "expected %s, found %s" % (expected.name, [p.name for p in jars]))
            else:
                self.add(check, UNAVAILABLE, "prod jar not built: %s" % expected.name)
            return
        if [p.name for p in jars] != [expected.name]:
            self.add(check, FAIL, "unexpected extra production jars: %s" % [p.name for p in jars if p != expected])
            return
        try:
            outer = zipfile.ZipFile(str(expected))
            meta = json.loads(outer.read("fabric.mod.json").decode("utf-8"))
        except Exception as exc:
            self.add(check, FAIL, "cannot read prod jar metadata: %s" % exc)
            return
        problems, pending = [], False
        top = {n for n in outer.namelist() if n.endswith(".class")}
        if meta.get("id") != "syncmaterial":
            problems.append("id=%r" % meta.get("id"))
        if meta.get("version") != "%s-%s" % (node, self.mod_version):
            problems.append("version=%r" % meta.get("version"))
        if version_values(meta.get("depends", {}).get("minecraft")) != [node]:
            problems.append("depends.minecraft=%r, expected [%r]"
                            % (meta.get("depends", {}).get("minecraft"), node))
        java_version = self.baseline.get("java_versions", {}).get(node)
        java_dep = version_values(meta.get("depends", {}).get("java"))
        if java_dep:
            if java_dep != [">=%s" % java_version]:
                problems.append("depends.java=%r, expected ['>=%s']" % (java_dep, java_version))
        else:
            pending = True
        major_expected = int(java_version) + 44 if java_version is not None else None
        wrong_major = sorted({class_major(outer.read(n)) for n in top} - {major_expected})
        if wrong_major:
            problems.append("top-level class majors %s != expected %s" % (wrong_major, major_expected))

        declared = [j.get("file") if isinstance(j, dict) else j for j in meta.get("jars", []) or []]
        actual = sorted(n for n in outer.namelist()
                        if n.startswith("META-INF/jars/") and n.endswith(".jar"))
        if set(declared) != set(actual):
            problems.append("fabric.mod.json jars %s != actual %s" % (declared, actual))
        shared = [n for n in actual if re.search(r"(^|/)shared-[^/]*\.jar$", n)]
        sqlite = [n for n in actual if re.search(r"(^|/)sqlite-jdbc-[^/]*\.jar$", n)]
        if len(shared) != 1:
            problems.append("shared nested %d times: %s" % (len(shared), shared))
        if len(sqlite) != 1:
            problems.append("sqlite nested %d times: %s" % (len(sqlite), sqlite))

        closure = walk_jars(outer, expected.name)
        providers = defaultdict(set)
        raw_dupes, test_paths, versioned = [], expected_class_paths(self.root), 0
        all_classes = set()
        for label, names, _ in closure:
            dup = [n for n, c in Counter(names).items() if c > 1]
            if dup:
                raw_dupes.append("%s: %s" % (label, dup[:4]))
            for name in names:
                if not name.endswith(".class"):
                    continue
                logical = logical_class(name)
                if logical is None or logical == "module-info.class":
                    continue
                all_classes.add(logical)
                providers[logical].add(label)
                if name.startswith("META-INF/versions/"):
                    versioned += 1
        dupes = sorted((k, sorted(v)) for k, v in providers.items() if len(v) > 1)
        if raw_dupes:
            problems.append("duplicate zip entries: %s" % raw_dupes[:4])
        if dupes:
            problems.append("duplicate classes across jars: %s"
                            % ["%s <- %s" % (k, v) for k, v in dupes[:8]])
        leaked = sorted(p for p in all_classes
                        if p in test_paths or is_test_class(p))
        if leaked:
            problems.append("test/fixture classes in prod: %s" % leaked[:8])
        shared_label = next((l for l, _, _ in closure
                             if re.search(r"!META-INF/jars/shared-[^!]*\.jar$", l)), None)
        if shared_label:
            shared_classes = next(names for l, names, _ in closure if l == shared_label)
            shared_classes = set(n for n in shared_classes if n.endswith(".class"))
            if top & shared_classes:
                problems.append("top-level duplicates shared classes: %s" % sorted(top & shared_classes)[:8])
        else:
            problems.append("nested shared jar not found in closure")
        problems.extend(binary_entry_problems(outer, meta, top))
        if problems:
            self.add(check, FAIL, "; ".join(problems[:10]))
        elif pending:
            self.add(check, UNAVAILABLE,
                     "%s ok, but final build has no depends.java yet (source template declares >=${java_version})"
                     % expected.name)
        else:
            self.add(check, PASS, "%s: minecraft=%s java>=%s major=%s, shared/sqlite once, %d MR entries"
                     % (expected.name, node, java_version, major_expected, versioned))

    # -------------------------------------------------------------- spotbugs
    def check_spotbugs(self, node):
        count = spotbugs_count(self.root / "versions" / node / "build/reports/spotbugs/main.xml")
        limit = int(self.baseline.get("spotbugs", {}).get("platform_max", 0))
        if count is None:
            self.add("spotbugs.%s" % node, UNAVAILABLE, "SpotBugs XML not available")
        else:
            self.add("spotbugs.%s" % node, FAIL if count > limit else PASS,
                     "SpotBugs %d (max %d)" % (count, limit))
            rows = spotbugs_instances(self.root / "versions" / node / "build/reports/spotbugs/main.xml")
            new_rows = new_spotbugs_findings(rows, self.baseline, node)
            self.add("spotbugs.regressions.%s" % node, FAIL if new_rows else PASS,
                     "new findings: %s" % new_rows[:12] if new_rows else "no new platform SpotBugs findings")


# ------------------------------------------------------------------ helpers
def read(path):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except Exception:
        return ""


def parse_props(path):
    props = {}
    for line in read(path).splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def prop(path, key):
    return parse_props(path).get(key)


def registration_problems(text, keyword, versions):
    if not text:
        return ["missing %s" % keyword]
    if "versions.json" in text:
        return []
    literals = re.findall(r"['\"](" + VER + r")['\"]", text)
    match = re.search(keyword + r"\s*(?:\(([^)]*)\)|([^\n]+))", text)
    if match and "versions.json" in text[match.start():match.start() + 400]:
        return []
    if not literals:
        return ["cannot determine %s registration from versions.json or a literal list" % keyword]
    if set(literals) != set(versions):
        return ["%s literals %s != versions.json %s" % (keyword, literals, versions)]
    return []


def node_copy_problems(root, node):
    base = root / "versions" / node
    skip = {"build", "logs", "mods", "config", ".fabric", ".gradle", "run", "saves",
            "screenshots", "crash-reports", "world", "resourcepacks", "shaderpacks"}
    problems = []
    for dirpath, dirnames, filenames in os.walk(str(base)):
        rel = Path(dirpath).relative_to(base)
        if rel.parts and rel.parts[0] in skip:
            dirnames[:] = []
            continue
        dirnames[:] = [d for d in dirnames if d not in skip]
        if "src" in dirnames or "net" in dirnames:
            problems.append("business source directory under versions/%s/%s" % (node, rel))
        for name in filenames:
            if name.endswith((".java", ".kt", ".kts", ".groovy", ".scala")) or name == "build.gradle":
                problems.append("business source file versions/%s/%s" % (node, rel / name))
    return problems


def shared_source_problems(root):
    main = root / "shared/src/main/java"
    src = root / "shared/src"
    if not main.is_dir() or not list(main.rglob("*.java")):
        return ["shared/src/main/java is missing or empty"]
    problems = []
    for path in main.rglob("*.java"):
        text = read(path)
        for marker in FORBIDDEN_TEXT:
            if marker in text:
                problems.append("%s references %s" % (path.relative_to(root), marker))
                break
    for path in src.rglob("*.java"):
        text = read(path)
        for line in text.splitlines():
            if line.strip().startswith("import ") and re.search(
                    r"\b(net\.minecraft|net\.fabricmc|fi\.dy\.masa)\b", line):
                problems.append("%s imports a platform package" % path.relative_to(root))
        if "//?" in text or "/*?" in text or "stonecutter" in text.lower():
            problems.append("%s contains a Stonecutter condition" % path.relative_to(root))
    build = read(root / "shared/build.gradle")
    cleaned = "\n".join(line for line in build.splitlines()
                        if not line.strip().startswith("//") and "fabric-loom-companion" not in line)
    for token in ("net.fabricmc", "net.minecraft", "fi.dy.masa", "maven.modrinth",
                  "malilib", "litematica", "syncmatica", "minecraft"):
        if token in cleaned:
            problems.append("shared/build.gradle has forbidden token %s" % token)
    if not re.search(r"JavaLanguageVersion\.of\s*\(\s*21\s*\)", build) or "VERSION_21" not in build:
        problems.append("shared is not compiled for Java 21")
    for plugin in ("java-library", "jacoco", "com.github.spotbugs"):
        if plugin not in build:
            problems.append("shared/build.gradle lacks %s" % plugin)
    if "jacocoTestReport" not in build or "spotbugsMain" not in build or "xml.required = true" not in build:
        problems.append("shared JaCoCo/SpotBugs report is not enabled")
    return problems


def main_metadata_problems(root, main_roots):
    path = root / "src/main/resources/fabric.mod.json"
    if not path.is_file():
        return ["missing src/main/resources/fabric.mod.json"]
    try:
        meta = json.loads(read(path))
    except Exception as exc:
        return ["invalid fabric.mod.json: %s" % exc]
    problems = []
    if meta.get("id") != "syncmaterial":
        problems.append("id=%r, expected syncmaterial" % meta.get("id"))
    for key in ("main", "client"):
        values, err = entrypoints(meta, key)
        if err:
            problems.append(err)
        elif not values:
            problems.append("entrypoints.%s is empty" % key)
        for value, _ in values:
            if find_class(root, value, main_roots) is None:
                problems.append("entrypoint class missing: %s" % value)
    depends = meta.get("depends", {})
    if depends.get("java") != ">=${java_version}":
        problems.append("source fabric.mod.json depends.java=%r, expected '>=${java_version}'" % depends.get("java"))
    if "java_version" not in read(root / "build.gradle"):
        problems.append("root processResources does not expand java_version")
    configs = meta.get("mixins", [])
    if isinstance(configs, str):
        configs = [configs]
    if not configs:
        problems.append("no mixin config declared")
    registered = set()
    for config_name in configs:
        try:
            config = json.loads(read(root / "src/main/resources" / config_name))
        except Exception:
            problems.append("missing/invalid mixin config %s" % config_name)
            continue
        package = config.get("package", "")
        names = []
        for key in ("mixins", "client", "server"):
            values, err = string_list(config.get(key, []))
            if err:
                problems.append("%s.%s: %s" % (config_name, key, err))
            names.extend(values)
        for name in names:
            registered.add(name)
            rel = package.replace(".", "/") + "/" + name.replace(".", "/") + ".java"
            if not any((base / rel).is_file() for base in main_roots):
                problems.append("mixin class missing: %s.%s" % (package, name))
    mixin_dir = root / "src/main/java/net/syncmaterial/syncmaterial/mixin"
    if mixin_dir.is_dir():
        for source in mixin_dir.glob("*.java"):
            if re.search(r"@Mixin\b", read(source)) and source.stem not in registered:
                problems.append("mixin not registered: %s" % source.name)
    return problems


def entrypoints(meta, key):
    raw = meta.get("entrypoints", {}).get(key, [])
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, list):
        return [], "%s entrypoints must be a list" % key
    result = []
    for item in raw:
        if isinstance(item, str) and item.strip():
            result.append((item.strip(), None))
        elif isinstance(item, dict) and isinstance(item.get("value"), str) and item["value"].strip():
            result.append((item["value"].strip(), item.get("adapter")))
        else:
            return [], "invalid %s entrypoint: %r" % (key, item)
    return result, None


def string_list(value):
    if isinstance(value, str):
        return [value], None
    if isinstance(value, list) and all(isinstance(v, str) for v in value):
        return list(value), None
    return [], "expected string or string list"


def validate_test_metadata(root, main_roots, test_roots, mod_id, meta):
    problems = []
    if meta.get("id") != mod_id:
        problems.append("id=%r, expected %r" % (meta.get("id"), mod_id))
    adapters = meta.get("languageAdapters", {})
    if not isinstance(adapters, dict):
        problems.append("languageAdapters is not an object")
        adapters = {}
    server, client = [], []
    for key in ("fabric-gametest", "fabric-client-gametest"):
        values, err = entrypoints(meta, key)
        if err:
            problems.append(err)
            continue
        if key == "fabric-gametest":
            server = values
        else:
            client = values
        seen = set()
        for value, adapter in values:
            if value in seen:
                problems.append("duplicate entry %s" % value)
            seen.add(value)
            source = find_class(root, value, test_roots)
            if source is None:
                problems.append("class source missing: %s" % value)
                continue
            if find_class(root, value, main_roots) is not None:
                problems.append("test entry is in main sources: %s" % value)
            if adapter is not None:
                target = adapters.get(adapter)
                if not isinstance(target, str):
                    problems.append("%s uses unknown adapter key %r" % (value, adapter))
                elif find_class(root, target, test_roots) is None:
                    problems.append("adapter target missing: %s" % target)
                else:
                    text = read(find_class(root, target, test_roots))
                    if "LanguageAdapter" not in text or not all(
                            marker in text for marker in
                            ("SYNCMATERIAL_CLIENT_TEST_START", "SYNCMATERIAL_CLIENT_TEST_PASS",
                             "SYNCMATERIAL_CLIENT_TEST_FAIL")):
                        problems.append("adapter %s does not implement reporting LanguageAdapter" % target)
    methods = {}
    for value, _ in server:
        source = find_class(root, value, test_roots)
        if source:
            methods[value] = gametest_methods(read(source))
            if not methods[value]:
                problems.append("server entry has no @GameTest method: %s" % value)
    for value, _ in client:
        source = find_class(root, value, test_roots)
        if source:
            text = read(source)
            if not client_test(text):
                problems.append("client entry does not implement FabricClientGameTest: %s" % value)
            elif not method_body(text, "runTest"):
                problems.append("client entry has empty runTest: %s" % value)
    return problems, server, client, methods


def gametest_methods(text):
    pattern = re.compile(
        r"@\s*GameTest\b(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*"
        r"(?:@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*)*"
        r"public\s+(?:final\s+)?void\s+(\w+)\s*\(", re.S)
    return [m.group(1) for m in pattern.finditer(text)]


def client_test(text):
    match = re.search(r"\bclass\s+\w+\b([^{]*)\{", text)
    return bool(match and re.search(r"\b(?:implements|extends)\b[^{]*\bFabricClientGameTest\b",
                                    match.group(1)))


def method_body(text, name):
    match = re.search(r"\bvoid\s+" + name + r"\s*\([^)]*\)\s*(?:throws[^{]+)?\{", text)
    if not match:
        return False
    depth, index = 1, match.end()
    while index < len(text) and depth:
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
        elif not text[index].isspace():
            return True
        index += 1
    return False


def scan_actual_tests(root, test_roots):
    server, client = {}, {}
    for base in test_roots:
        if not base.is_dir():
            continue
        for path in base.rglob("*.java"):
            name = fqcn(path, base)
            if not name:
                continue
            text = read(path)
            methods = gametest_methods(text)
            if methods and not re.search(r"\babstract\s+class\b", text):
                server[name] = methods
            if client_test(text) and not re.search(r"\babstract\s+class\b", text):
                client[name] = methods
    return server, client


def fqcn(path, base):
    try:
        rel = path.relative_to(base)
    except ValueError:
        return None
    return ".".join(rel.with_suffix("").parts) if rel.suffix == ".java" else None


def source_fqcns(base):
    return {fqcn(p, base) for p in Path(base).rglob("*.java")} if Path(base).is_dir() else set()


def find_class(root, name, roots):
    rel = name.replace(".", "/") + ".java"
    for base in roots:
        if (base / rel).is_file():
            return base / rel
    return None


def gametest_id(mod_id, fqcn_name, method):
    simple = fqcn_name.rsplit(".", 1)[-1]
    return "%s:%s" % (mod_id, re.sub(r"([a-z])([A-Z])", r"\1_\2",
                                     simple + "_" + method).lower())


def count_filter_matches(info, mod_id, pattern):
    return sum(1 for fqcn_name, methods in info["methods"].items()
               for method in methods
               if fnmatch.fnmatchcase(gametest_id(mod_id, fqcn_name, method), pattern))


def client_tags(text):
    pattern = re.compile(r"SYNCMATERIAL_CLIENT_TEST_(START|PASS|FAIL)\s+(\S+)\s+(\S+)")
    return [(m.group(1), m.group(2), m.group(3))
            for line in text.splitlines() for m in pattern.finditer(line)]


def validate_tags(problems, mod_id, expected, tags):
    for cls in expected:
        starts = [t for t in tags if t[1] == mod_id and t[2] == cls and t[0] == "START"]
        passes = [t for t in tags if t[1] == mod_id and t[2] == cls and t[0] == "PASS"]
        if len(starts) != 1:
            problems.append("%s START=%d" % (cls, len(starts)))
        if len(passes) != 1:
            problems.append("%s PASS=%d" % (cls, len(passes)))


def junit_summary(directory):
    files = sorted(Path(directory).glob("TEST-*.xml")) if Path(directory).is_dir() else []
    if not files:
        return None
    tests = skipped = failures = errors = 0
    classes = set()
    class_tests = Counter()
    try:
        for path in files:
            root = ET.parse(str(path)).getroot()
            suites = list(root.iter("testsuite")) or ([root] if root.tag == "testsuite" else [])
            for suite in suites:
                tests += int(suite.get("tests", 0) or 0)
                skipped += int(suite.get("skipped", 0) or 0)
                failures += int(suite.get("failures", 0) or 0)
                errors += int(suite.get("errors", 0) or 0)
            for case in root.iter("testcase"):
                name = case.get("classname", "")
                classes.add(name)
                class_tests[name] += 1
                skipped += 1 if case.find("skipped") is not None else 0
                failures += 1 if case.find("failure") is not None else 0
                errors += 1 if case.find("error") is not None else 0
    except Exception:
        return None
    return {"tests": tests, "skipped": skipped, "failures": failures,
            "errors": errors, "classes": classes, "class_tests": class_tests}


def junit_issues(summary, baseline, key):
    issues = []
    if summary["tests"] < int(baseline.get("junit", {}).get(key, 1)):
        issues.append("tests=%d below baseline minimum" % summary["tests"])
    if summary["skipped"] or summary["failures"] or summary["errors"]:
        issues.append("skipped=%d failures=%d errors=%d"
                      % (summary["skipped"], summary["failures"], summary["errors"]))
    if summary["tests"] <= 0:
        issues.append("zero tests")
    suite = "shared" if key == "shared_min_tests" else "platform"
    required = set(baseline.get("junit", {}).get("required_classes", {}).get(suite, []))
    missing = required - summary["classes"]
    if missing:
        issues.append("required regression classes missing: %s" % sorted(missing))
    expected_counts = baseline.get("junit", {}).get("required_class_min_tests", {}).get(suite, {})
    incomplete = {name: summary.get("class_tests", {}).get(name, 0)
                  for name, count in expected_counts.items()
                  if summary.get("class_tests", {}).get(name, 0) < count}
    if incomplete:
        issues.append("required regression test counts below minimum: %s" % incomplete)
    return issues


def spotbugs_instances(path):
    try:
        root = ET.parse(str(path)).getroot()
        if root.tag != "BugCollection":
            return None
        return Counter((bug.get("type"), bug.find("Class").get("classname"))
                       for bug in root.findall("BugInstance"))
    except Exception:
        return None


def new_spotbugs_findings(rows, baseline, scope):
    expected = baseline.get("spotbugs", {}).get("accepted_findings", {}).get(scope)
    if rows is None or expected is None:
        return []
    accepted = Counter({(item["type"], item["class"]): int(item["count"]) for item in expected})
    return [{"type": kind, "class": name, "count": count}
            for (kind, name), count in sorted((rows - accepted).items())]


def spotbugs_count(path):
    try:
        root = ET.parse(str(path)).getroot()
        return len(root.findall("BugInstance")) if root.tag == "BugCollection" else None
    except Exception:
        return None


def class_major(data):
    return int.from_bytes(data[6:8], "big") if data[:4] == b"\xca\xfe\xba\xbe" else None


def walk_jars(outer, label, depth=0):
    result = [(label, outer.namelist(), outer)]
    if depth >= 5:
        return result
    for name in outer.namelist():
        if name.startswith("META-INF/jars/") and name.endswith(".jar"):
            try:
                nested = zipfile.ZipFile(io.BytesIO(outer.read(name)))
            except Exception:
                continue
            result.extend(walk_jars(nested, label + "!" + name, depth + 1))
    return result


def logical_class(name):
    if not name.endswith(".class") or not name.startswith("META-INF/versions/"):
        return name if name.endswith(".class") else None
    parts = name.split("/", 3)
    return parts[3] if len(parts) == 4 else None


def expected_class_paths(root, shared_only=False):
    roots = [root / "shared/src/test/java"] if shared_only else [
        root / "src/test/java", root / "src/gametest/java",
        root / "src/foundationtest/java", root / "src/optionaltest/java",
        root / "shared/src/test/java"]
    paths = set()
    for base in roots:
        if base.is_dir():
            for path in base.rglob("*.java"):
                name = fqcn(path, base)
                if name:
                    paths.add(name.replace(".", "/") + ".class")
    return paths


def is_test_class(name):
    if not name.startswith("net/syncmaterial/"):
        return False
    parts = name.split("/")
    return ("gametest" in parts[:-1] or "test" in parts[:-1]
            or re.search(r"(?:Test|MockBot|TestSupport|ClientTestReportingAdapter)(?:\$.*)?\.class$",
                         parts[-1]) is not None)


def binary_entry_problems(outer, meta, top):
    problems = []
    for key in ("main", "client"):
        values, err = entrypoints(meta, key)
        if err:
            problems.append(err)
        for value, _ in values:
            if value.replace(".", "/") + ".class" not in top:
                problems.append("entrypoint missing from jar: %s" % value)
    configs = meta.get("mixins", [])
    if isinstance(configs, str):
        configs = [configs]
    for config_name in configs:
        try:
            config = json.loads(outer.read(config_name).decode("utf-8"))
        except Exception:
            problems.append("mixin config missing from jar: %s" % config_name)
            continue
        package = config.get("package", "")
        for key in ("mixins", "client", "server"):
            values, _ = string_list(config.get(key, []))
            for value in values:
                if (package.replace(".", "/") + "/" + value.replace(".", "/") + ".class") not in top:
                    problems.append("mixin missing from jar: %s" % value)
    return problems


def version_values(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, list) and all(isinstance(v, str) for v in value):
        return list(value)
    return []


def collect_scopes(values):
    result = []
    for value in values:
        for part in value.split(","):
            part = part.strip()
            if part and part not in SCOPES and part != "all":
                raise ValueError("unknown scope %r (use %s or all)" % (part, ", ".join(SCOPES)))
            if part:
                result.append(part)
    if not result or "all" in result:
        return list(SCOPES)
    return list(dict.fromkeys(result))


def main(argv):
    global ARGS
    parser = argparse.ArgumentParser(description="Multi-version architecture/package/test guard")
    parser.add_argument("--root", default=os.getcwd())
    parser.add_argument("--node", action="append", default=[])
    parser.add_argument("--scope", action="append", default=[])
    parser.add_argument("--baseline", default=None)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--list-scopes", action="store_true")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--strict", action="store_true",
                       help="treat UNAVAILABLE as failure")
    group.add_argument("--allow-missing", action="store_true",
                       help="treat UNAVAILABLE as success (still reported)")
    args = parser.parse_args(argv)
    if args.list_scopes:
        print("Scopes: %s, all" % ", ".join(SCOPES))
        return 0
    ARGS = args
    try:
        args.scopes = collect_scopes(args.scope)
    except ValueError as exc:
        sys.stderr.write("error: %s\n" % exc)
        return 2
    root = Path(args.root).resolve()
    if not (root / "settings.gradle").is_file() or not (root / "versions/versions.json").is_file():
        sys.stderr.write("error: not a multi-version repository root: %s\n" % root)
        return 2
    baseline_path = Path(args.baseline) if args.baseline else Path(__file__).with_name("multiversion_baseline.json")
    try:
        baseline = json.loads(baseline_path.resolve().read_text(encoding="utf-8"))
    except Exception as exc:
        sys.stderr.write("error: cannot read baseline %s: %s\n" % (baseline_path, exc))
        return 2
    guard = Guard(root, baseline)
    nodes = guard.versions
    if args.node:
        unknown = [n for n in args.node if n not in nodes]
        if unknown:
            sys.stderr.write("error: node(s) not in versions.json: %s\n" % unknown)
            return 2
        nodes = list(dict.fromkeys(args.node))
    guard.nodes = nodes
    if "static" in args.scopes:
        guard.check_static()
    if "shared" in args.scopes:
        guard.check_shared()
    for node in nodes:
        if "unit" in args.scopes:
            guard.check_unit(node)
        if "spotbugs" in args.scopes:
            guard.check_spotbugs(node)
        if "package" in args.scopes:
            guard.check_package(node)
        if "server" in args.scopes:
            guard.check_server(node)
        if "client" in args.scopes:
            guard.check_client(node)
        if "optional" in args.scopes:
            guard.check_optional(node, "all")
        if "optional-server" in args.scopes:
            guard.check_optional(node, "server")
        if "optional-client" in args.scopes:
            guard.check_optional(node, "client")
    return guard.finish()


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as exc:
        sys.stderr.write("error: guard crashed: %s\n" % exc)
        sys.exit(2)
