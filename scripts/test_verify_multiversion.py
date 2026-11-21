#!/usr/bin/env python3
"""Regression tests for the multiversion acceptance evidence gate."""

import gzip
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("verify_multiversion", ROOT / "scripts/verify_multiversion.py")
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class RegressionClassGateTest(unittest.TestCase):
    def setUp(self):
        self.baseline = {"junit": {
            "platform_min_tests": 1,
            "required_classes": {"platform": ["Required"]},
            "required_class_min_tests": {"platform": {"Required": 2}},
        }}
        self.summary = {"tests": 100, "skipped": 0, "failures": 0, "errors": 0,
                        "classes": set(), "class_tests": {}}

    def test_high_total_cannot_replace_missing_required_class(self):
        problems = GUARD.junit_issues(self.summary, self.baseline, "platform_min_tests")
        self.assertTrue(any("classes missing" in value for value in problems))

    def test_partial_required_class_cannot_pass(self):
        self.summary.update(classes={"Required"}, class_tests={"Required": 1})
        self.assertTrue(any("counts below minimum" in value
                            for value in GUARD.junit_issues(self.summary, self.baseline, "platform_min_tests")))

    def test_complete_class_passes(self):
        self.summary.update(classes={"Required"}, class_tests={"Required": 2})
        self.assertEqual([], GUARD.junit_issues(self.summary, self.baseline, "platform_min_tests"))

    def test_skipped_regressions_never_pass(self):
        self.summary.update(classes={"Required"}, class_tests={"Required": 2}, skipped=1)
        self.assertTrue(GUARD.junit_issues(self.summary, self.baseline, "platform_min_tests"))

    def test_xml_counts_actual_testcases_per_class(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "TEST-Required.xml").write_text(
                '<testsuite tests="2" failures="0" errors="0" skipped="0">'
                '<testcase classname="Required" name="one" />'
                '<testcase classname="Required" name="two" />'
                '</testsuite>', encoding="utf-8")
            summary = GUARD.junit_summary(Path(directory))
        self.assertEqual(2, summary["class_tests"]["Required"])
        self.assertEqual({"Required"}, summary["classes"])

    def test_zero_test_xml_does_not_claim_class_executed(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "TEST-Required.xml").write_text(
                '<testsuite name="Required" tests="0" failures="0" errors="0" skipped="0" />', encoding="utf-8")
            summary = GUARD.junit_summary(Path(directory))
        self.assertNotIn("Required", summary["classes"])
        self.assertTrue(GUARD.junit_issues(summary, self.baseline, "platform_min_tests"))


class RunLogGateTest(unittest.TestCase):
    MOD = "syncmaterial-optional-test"
    CLASS = "RequiredClient"
    LAUNCH = "[23:59:00] [main/INFO] (FabricLoader/GameProvider) Loading Minecraft 1.21.7 with Fabric Loader\n"

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.logs = Path(self.directory.name)
        self.latest = self.logs / "latest.log"

    def tag(self, status):
        return "SYNCMATERIAL_CLIENT_TEST_%s %s %s\n" % (status, self.MOD, self.CLASS)

    def rotate(self, name, text):
        (self.logs / name).write_bytes(gzip.compress(text.encode("utf-8")))

    def problems(self, text):
        problems = []
        GUARD.validate_tags(problems, self.MOD, [self.CLASS], GUARD.client_tags(text))
        return problems

    def test_midnight_rotation_preserves_start_and_pass(self):
        self.rotate("2026-11-13-1.log.gz", self.LAUNCH + self.tag("START"))
        self.latest.write_text(self.tag("PASS"), encoding="utf-8")
        self.assertEqual([], self.problems(GUARD.run_log_text(self.latest)))

    def test_rotation_indices_are_numeric_not_lexicographic(self):
        self.rotate("2026-11-13-2.log.gz", self.LAUNCH + self.tag("START"))
        self.rotate("2026-11-13-10.log.gz", self.LAUNCH + self.tag("START"))
        self.latest.write_text(self.tag("PASS"), encoding="utf-8")
        self.assertEqual([], self.problems(GUARD.run_log_text(self.latest)))

    def test_latest_launch_never_borrows_an_old_start(self):
        self.rotate("2026-11-13-1.log.gz", self.LAUNCH + self.tag("START") + self.tag("PASS"))
        self.latest.write_text(self.LAUNCH + self.tag("PASS"), encoding="utf-8")
        self.assertIn(self.CLASS + " START=0", self.problems(GUARD.run_log_text(self.latest)))

    def test_duplicate_start_in_one_launch_still_fails(self):
        self.rotate("2026-11-13-1.log.gz", self.LAUNCH + self.tag("START"))
        self.latest.write_text(self.tag("START") + self.tag("PASS"), encoding="utf-8")
        self.assertIn(self.CLASS + " START=2", self.problems(GUARD.run_log_text(self.latest)))

    def test_rotated_fail_tag_is_not_hidden_by_latest_pass(self):
        self.rotate("2026-11-13-1.log.gz", self.LAUNCH + self.tag("START") + self.tag("FAIL"))
        self.latest.write_text(self.tag("PASS"), encoding="utf-8")
        self.assertTrue(any(tag[0] == "FAIL" for tag in GUARD.client_tags(GUARD.run_log_text(self.latest))))

    def test_debug_log_never_supplies_missing_start(self):
        (self.logs / "debug.log").write_text(self.LAUNCH + self.tag("START"), encoding="utf-8")
        self.latest.write_text(self.tag("PASS"), encoding="utf-8")
        self.assertIn(self.CLASS + " START=0", self.problems(GUARD.run_log_text(self.latest)))

    def test_missing_latest_never_uses_old_archive_as_completed_run(self):
        self.rotate("2026-11-13-1.log.gz", self.LAUNCH + self.tag("START") + self.tag("PASS"))
        self.assertEqual("", GUARD.run_log_text(self.latest))

    def test_corrupt_rotation_is_not_silently_skipped(self):
        (self.logs / "2026-11-13-1.log.gz").write_bytes(b"not a gzip log")
        self.latest.write_text(self.LAUNCH + self.tag("START") + self.tag("PASS"), encoding="utf-8")
        with self.assertRaises(OSError):
            GUARD.run_log_text(self.latest)

    def test_expanded_rotation_limit_fails_instead_of_truncating_evidence(self):
        self.rotate("2026-11-13-1.log.gz", self.LAUNCH + self.tag("START"))
        self.latest.write_text(self.tag("PASS"), encoding="utf-8")
        with patch.object(GUARD, "MAX_RUN_LOG_BYTES", 32):
            with self.assertRaisesRegex(OSError, "evidence limit"):
                GUARD.run_log_text(self.latest)

    def test_server_summary_keeps_rotated_failure(self):
        self.rotate("2026-11-13-1.log.gz", self.LAUNCH + "1 required tests failed :(\n")
        self.latest.write_text("========= 19 GAME TESTS COMPLETE IN 2s\nAll 19 required tests passed :)\n",
                               encoding="utf-8")
        self.assertIn("1 required tests failed :(", GUARD.run_log_text(self.latest))


class StaticFindingGateTest(unittest.TestCase):
    def test_new_findings_fail_even_below_global_count_limit(self):
        baseline = {"spotbugs": {"accepted_findings": {"26.2": [
            {"type": "OLD", "class": "OldClass", "count": 2}
        ]}}}
        rows = GUARD.Counter({("OLD", "OldClass"): 1, ("NEW", "NewClass"): 1})
        self.assertEqual([{"type": "NEW", "class": "NewClass", "count": 1}],
                         GUARD.new_spotbugs_findings(rows, baseline, "26.2"))

    def test_additional_same_class_findings_are_regressions(self):
        baseline = {"spotbugs": {"accepted_findings": {"26.2": [
            {"type": "OLD", "class": "OldClass", "count": 1}
        ]}}}
        rows = GUARD.Counter({("OLD", "OldClass"): 2})
        self.assertEqual(1, GUARD.new_spotbugs_findings(rows, baseline, "26.2")[0]["count"])

    def test_removed_baseline_findings_do_not_fail(self):
        baseline = {"spotbugs": {"accepted_findings": {"shared": []}}}
        self.assertEqual([], GUARD.new_spotbugs_findings(GUARD.Counter(), baseline, "shared"))


class LanguageResourceGateTest(unittest.TestCase):
    EN = {
        "greeting": "Hello %s",
        "count": "%d items",
        "prose": "Progress 100% complete",
        "coords": "%1$s [%2$d,%3$d]",
    }

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.lang = self.root / "lang"
        self.lang.mkdir()
        self.locales = ["de_de", "en_us"]
        self.contract = {
            "directory": "lang",
            "reference": "en_us",
            "locales": list(self.locales),
            "expected_keys": sorted(self.EN),
            "placeholders": {
                "greeting": {"s": 1},
                "count": {"d": 1},
                "coords": {"s": 1, "d": 2},
            },
        }
        self.write("en_us", self.EN)
        self.write("de_de", self.EN)

    def write(self, locale, data):
        path = self.lang / (locale + ".json")
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    def write_raw(self, locale, raw):
        (self.lang / (locale + ".json")).write_bytes(raw)

    def problems(self):
        return GUARD.language_resource_problems(self.root, self.contract)

    def test_real_contract_covers_all_eight_locales_and_declared_keys(self):
        baseline = json.loads((ROOT / "scripts/multiversion_baseline.json").read_text(encoding="utf-8"))
        contract = baseline["language_resources"]
        self.assertEqual(8, len(contract["locales"]))
        reference, problems = GUARD.read_language_json(ROOT / contract["directory"] / "en_us.json")
        self.assertEqual([], problems)
        self.assertEqual(set(reference), set(contract["expected_keys"]))
        self.assertEqual([], GUARD.language_resource_problems(ROOT, contract))

    def test_clean_fixture_passes(self):
        self.assertEqual([], self.problems())

    def test_bom_is_rejected(self):
        self.write_raw("de_de", b"\xef\xbb\xbf" + json.dumps(self.EN).encode("utf-8"))
        self.assertTrue(any("byte-order mark" in problem for problem in self.problems()))

    def test_duplicate_json_key_is_rejected(self):
        raw = json.dumps(self.EN)
        self.write_raw("de_de", (raw[:-1] + ', "greeting": "Hello %s"}').encode("utf-8"))
        self.assertTrue(any("duplicate keys" in problem for problem in self.problems()))

    def test_invalid_json_is_rejected(self):
        self.write_raw("de_de", b"{not json")
        self.assertTrue(any("invalid JSON" in problem for problem in self.problems()))

    def test_missing_language_file_is_rejected(self):
        (self.lang / "de_de.json").unlink()
        self.assertTrue(any("missing language files" in problem for problem in self.problems()))

    def test_unexpected_language_file_is_rejected(self):
        self.write("fr_fr", self.EN)
        self.assertTrue(any("unexpected language files" in problem for problem in self.problems()))

    def test_missing_key_is_rejected(self):
        data = dict(self.EN)
        data.pop("greeting")
        self.write("de_de", data)
        self.assertTrue(any("missing keys" in problem for problem in self.problems()))

    def test_extra_key_is_rejected(self):
        data = dict(self.EN)
        data["surplus"] = "unexpected"
        self.write("de_de", data)
        self.assertTrue(any("unexpected keys" in problem for problem in self.problems()))

    def test_placeholder_type_mismatch_is_rejected(self):
        data = dict(self.EN)
        data["count"] = "%s items"
        self.write("de_de", data)
        self.assertTrue(any("placeholder mismatch for 'count'" in problem
                            for problem in self.problems()))

    def test_placeholder_count_mismatch_is_rejected(self):
        data = dict(self.EN)
        data["greeting"] = "Hello %s %s"
        self.write("de_de", data)
        self.assertTrue(any("placeholder mismatch for 'greeting'" in problem
                            for problem in self.problems()))

    def test_contract_catches_reference_and_locale_drift_together(self):
        for locale in self.locales:
            data = dict(self.EN)
            data["greeting"] = "Hello"
            self.write(locale, data)
        self.assertTrue(any("placeholder mismatch for 'greeting'" in problem
                            for problem in self.problems()))

    def test_prose_percent_is_not_a_placeholder(self):
        for text in ("100%", "50%% complete", "Progress 100% complete",
                     "100% sure", "50%off", "%n"):
            self.assertEqual({}, dict(GUARD.language_placeholder_counts(text)))
        self.assertEqual({"s": 1, "d": 2},
                         dict(GUARD.language_placeholder_counts("%1$s %2$d %02d")))


if __name__ == "__main__":
    unittest.main()
