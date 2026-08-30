from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_detekt_baseline as baseline_guard


class DetektBaselineGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir_context = tempfile.TemporaryDirectory()
        self.temp_dir = Path(self.temp_dir_context.name)
        self.base = self.temp_dir / "base.xml"
        self.head = self.temp_dir / "head.xml"

    def tearDown(self) -> None:
        self.temp_dir_context.cleanup()

    def write_baseline(
        self,
        path: Path,
        current_issue_ids: list[str],
        *,
        manually_suppressed_ids: list[str] | None = None,
    ) -> None:
        manual_ids = "".join(
            f"<ID>{issue_id}</ID>" for issue_id in (manually_suppressed_ids or [])
        )
        current_ids = "".join(f"<ID>{issue_id}</ID>" for issue_id in current_issue_ids)
        path.write_text(
            "<?xml version='1.0'?>"
            "<SmellBaseline>"
            f"<ManuallySuppressedIssues>{manual_ids}</ManuallySuppressedIssues>"
            f"<CurrentIssues>{current_ids}</CurrentIssues>"
            "</SmellBaseline>",
            encoding="utf-8",
        )

    def run_main(self) -> tuple[int, str, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = baseline_guard.main([str(self.base), str(self.head)])
        return result, stdout.getvalue(), stderr.getvalue()

    def test_identical_issue_sets_pass(self) -> None:
        self.write_baseline(self.base, ["LongMethod:Example.kt$one"])
        self.write_baseline(self.head, ["LongMethod:Example.kt$one"])

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 0)
        self.assertIn("did not grow", stdout)
        self.assertEqual(stderr, "")

    def test_removing_current_issues_passes(self) -> None:
        self.write_baseline(self.base, ["Old:A", "Keep:B"])
        self.write_baseline(self.head, ["Keep:B"])

        comparison = baseline_guard.compare_baselines(self.base, self.head)

        self.assertEqual(comparison.added_entries, frozenset())
        self.assertEqual(
            comparison.removed_entries,
            frozenset(
                {
                    baseline_guard.BaselineEntry("CurrentIssues", "Old:A"),
                }
            ),
        )

    def test_added_current_issue_is_rejected_and_reported(self) -> None:
        self.write_baseline(self.base, ["Keep:B"])
        self.write_baseline(self.head, ["Keep:B", "New:A"])

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 1)
        self.assertEqual(stdout, "")
        self.assertIn("grew by 1", stderr)
        self.assertIn('"New:A"', stderr)

    def test_missing_base_is_allowed_as_bootstrap(self) -> None:
        self.write_baseline(self.head, ["Initial:A"])

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 0)
        self.assertIn("bootstrap allowed", stdout)
        self.assertEqual(stderr, "")

    def test_missing_base_does_not_skip_head_validation(self) -> None:
        self.head.write_text("not XML", encoding="utf-8")

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("malformed baseline XML", stderr)

    def test_missing_head_fails_even_when_base_is_missing(self) -> None:
        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("baseline does not exist", stderr)

    def test_malformed_existing_base_fails_safely(self) -> None:
        self.base.write_text("<SmellBaseline>", encoding="utf-8")
        self.write_baseline(self.head, [])

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("malformed baseline XML", stderr)

    def test_duplicate_counts_cannot_mask_an_added_id(self) -> None:
        self.write_baseline(self.base, ["Existing:A", "Existing:A"])
        self.write_baseline(self.head, ["Existing:A", "Added:B"])

        comparison = baseline_guard.compare_baselines(self.base, self.head)

        self.assertEqual(
            comparison.added_entries,
            frozenset(
                {
                    baseline_guard.BaselineEntry("CurrentIssues", "Added:B"),
                }
            ),
        )

    def test_duplicate_head_ids_use_set_semantics(self) -> None:
        self.write_baseline(self.base, ["Existing:A"])
        self.write_baseline(self.head, ["Existing:A", "Existing:A"])

        comparison = baseline_guard.compare_baselines(self.base, self.head)

        self.assertEqual(comparison.added_entries, frozenset())
        self.assertEqual(len(comparison.head_entries), 1)

    def test_added_manually_suppressed_id_is_rejected(self) -> None:
        self.write_baseline(self.base, [], manually_suppressed_ids=["Manual:A"])
        self.write_baseline(
            self.head,
            [],
            manually_suppressed_ids=["Manual:A", "Manual:B"],
        )

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 1)
        self.assertEqual(stdout, "")
        self.assertIn("ManuallySuppressedIssues", stderr)
        self.assertIn('"Manual:B"', stderr)

    def test_moving_an_id_to_manual_suppression_is_rejected(self) -> None:
        self.write_baseline(self.base, ["Moved:A"])
        self.write_baseline(self.head, [], manually_suppressed_ids=["Moved:A"])

        comparison = baseline_guard.compare_baselines(self.base, self.head)

        self.assertEqual(
            comparison.added_entries,
            frozenset(
                {
                    baseline_guard.BaselineEntry(
                        "ManuallySuppressedIssues",
                        "Moved:A",
                    ),
                }
            ),
        )

    def test_missing_manual_suppression_section_fails(self) -> None:
        self.write_baseline(self.base, [])
        self.head.write_text(
            "<SmellBaseline><CurrentIssues/></SmellBaseline>",
            encoding="utf-8",
        )

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("exactly one ManuallySuppressedIssues", stderr)

    def test_missing_current_issues_section_fails(self) -> None:
        self.base.write_text(
            "<SmellBaseline><ManuallySuppressedIssues/></SmellBaseline>",
            encoding="utf-8",
        )
        self.write_baseline(self.head, [])

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("exactly one CurrentIssues", stderr)

    def test_empty_id_fails(self) -> None:
        self.write_baseline(self.base, [])
        self.head.write_text(
            "<SmellBaseline><ManuallySuppressedIssues/>"
            "<CurrentIssues><ID> </ID></CurrentIssues></SmellBaseline>",
            encoding="utf-8",
        )

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("empty ID", stderr)

    def test_dtd_is_rejected(self) -> None:
        self.write_baseline(self.base, [])
        self.head.write_text(
            "<!DOCTYPE SmellBaseline [<!ENTITY issue 'Injected:A'>]>"
            "<SmellBaseline><CurrentIssues><ID>&issue;</ID></CurrentIssues></SmellBaseline>",
            encoding="utf-8",
        )

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("not allowed", stderr)

    def test_unknown_xml_encoding_fails_without_a_traceback(self) -> None:
        self.write_baseline(self.base, [])
        self.head.write_bytes(
            b'<?xml version="1.0" encoding="not-a-real-encoding"?>'
            b"<SmellBaseline><CurrentIssues/></SmellBaseline>"
        )

        result, stdout, stderr = self.run_main()

        self.assertEqual(result, 2)
        self.assertEqual(stdout, "")
        self.assertIn("malformed baseline XML", stderr)


if __name__ == "__main__":
    unittest.main()
