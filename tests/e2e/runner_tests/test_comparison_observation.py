"""Comparison E2E preconditions and cleanup must remain truthful after failure."""

import importlib.util
from pathlib import Path
import unittest
from unittest import mock

E2E_DIR = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("comparison_contract_harness", E2E_DIR / "harness.py")
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)
SPEC = importlib.util.spec_from_file_location(
    "comparison_e2e_contract", E2E_DIR / "tools/test_compare_configurations.py")
COMPARISON = importlib.util.module_from_spec(SPEC)
with mock.patch.dict("sys.modules", {"harness": HARNESS}):
    SPEC.loader.exec_module(COMPARISON)


def snapshot(state, *, started=True, report=False):
    text = "| status | %s |\n" % state
    if started:
        text += "Comparison cmp-token-1 started.\n"
    if report:
        text += "| comparisonId | cmp-token-1 |\n"
    return HARNESS.Result({"result": {"content": [{"type": "text", "text": text}]}})


class ComparisonObservationTest(unittest.TestCase):
    def test_failed_job_history_does_not_prove_a_live_comparison(self):
        with mock.patch.object(COMPARISON, "call", return_value=snapshot("failed")):
            with self.assertRaisesRegex(AssertionError, "failed"):
                COMPARISON._await_slot_taken("first", rounds=1)

    def test_cancelled_job_is_a_failed_precondition_even_without_history(self):
        with mock.patch.object(COMPARISON, "call", return_value=snapshot("cancelled", started=False)):
            with self.assertRaisesRegex(AssertionError, "cancelled"):
                COMPARISON._await_slot_taken("first", rounds=1)

    def test_done_report_still_owns_the_comparison_slot(self):
        with mock.patch.object(COMPARISON, "call", return_value=snapshot("done", report=True)):
            self.assertEqual(COMPARISON._await_slot_taken("first", rounds=1), "cmp-token-1")

    def test_done_without_a_readable_report_is_a_failed_precondition(self):
        with mock.patch.object(COMPARISON, "call", return_value=snapshot("done")):
            with self.assertRaisesRegex(AssertionError, "without a readable report"):
                COMPARISON._await_slot_taken("first", rounds=1)

    def test_historical_start_is_not_a_substitute_for_successful_completion(self):
        with mock.patch.object(COMPARISON, "call", side_effect=[snapshot("running"), snapshot("failed")]):
            with self.assertRaisesRegex(AssertionError, "failed"):
                COMPARISON._await_slot_taken("first", rounds=2)

    def test_unexpected_second_acceptance_is_cleaned_before_assertion_escapes(self):
        def accepted(job):
            return HARNESS.Result({"result": {"content": [{"type": "text", "text": "| jobId | %s |\nPending" % job}]}})
        with mock.patch.object(COMPARISON, "_start", side_effect=[accepted("first"), accepted("second")]), \
                mock.patch.object(COMPARISON, "_await_slot_taken", return_value="cmp-token-1"), \
                mock.patch.object(COMPARISON, "_finish") as finish:
            with self.assertRaises(HARNESS.E2EAssertion):
                COMPARISON.test_a_second_launch_is_refused_naming_the_live_comparison()
            self.assertEqual([entry.args[0] for entry in finish.call_args_list], ["second", "first"])


if __name__ == "__main__":
    unittest.main()
