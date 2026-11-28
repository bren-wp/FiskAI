#!/usr/bin/env python3
import pathlib
import runpy
import unittest

watch = runpy.run_path(str(pathlib.Path(__file__).with_name("regulatory-watch.py")))


class WatchTests(unittest.TestCase):
    def test_equal_and_changed(self):
        compare = watch["compare"]
        before = {"a": {"sha256": "a"}, "b": {"sha256": "b"}}
        self.assertEqual(compare(before, before), [])
        self.assertEqual(compare({"a": {"sha256": "c"}, "c": {}}, before), ["a", "b", "c"])

    def test_redirect_is_rejected(self):
        with self.assertRaises(ValueError):
            watch["NoRedirect"]().redirect_request(None, None, 302, "", {}, "https://example.test")


if __name__ == "__main__":
    unittest.main()
