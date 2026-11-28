#!/usr/bin/env python3
"""Synthetic interval tests; do not introduce new statutory rules."""
import copy
import runpy
import unittest
from pathlib import Path

catalog = runpy.run_path(str(Path(__file__).with_name("check-legal-catalog.py")))
render = catalog["render"]


class IntervalTests(unittest.TestCase):
    def setUp(self):
        self.data = {
            "source": catalog["SOURCE"],
            "scope": "STANDARD_DOMESTIC_B2B",
            "rules": [
                {"from": "2027-01-01", "until": "2027-07-01", "version": "HR-F2-2027-v1",
                 "vatIssue": True, "nonVatIssue": True},
                {"from": "2027-07-01", "until": "2028-01-01", "version": "HR-F2-2027-v2",
                 "vatIssue": True, "nonVatIssue": False},
            ],
        }

    def test_multiple_revisions_same_year(self):
        for output in render(self.data).values():
            self.assertIn("HR-F2-2027-v1", output)
            self.assertIn("HR-F2-2027-v2", output)
            self.assertEqual(output.count("2027-07-01"), 2)

    def test_gap_is_not_filled(self):
        self.data["rules"][1]["from"] = "2027-08-01"
        for output in render(self.data).values():
            self.assertIn("2027-08-01", output)

    def test_invalid_intervals_and_versions(self):
        for field, value in [
            ("from", "2027-06-30"), ("from", "2027-02-29"),
            ("from", "2027-7-01"), ("until", "2027-07-01"),
            ("version", "HR-F2-2027-v1"), ("version", "HR-F2-2026-v2"),
            ("vatIssue", 1),
        ]:
            with self.subTest(field=field, value=value):
                data = copy.deepcopy(self.data)
                data["rules"][1][field] = value
                with self.assertRaises(ValueError):
                    render(data)

    def test_unordered_intervals(self):
        self.data["rules"].reverse()
        with self.assertRaises(ValueError):
            render(self.data)


if __name__ == "__main__":
    unittest.main()
