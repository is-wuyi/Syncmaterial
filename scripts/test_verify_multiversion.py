#!/usr/bin/env python3
"""Regression tests for the multiversion acceptance evidence gate."""

import importlib.util
import tempfile
import unittest
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
