#!/usr/bin/env python3
import pathlib
import runpy
import unittest

d=runpy.run_path(str(pathlib.Path(__file__).with_name("regulatory-discovery.py")))

class DiscoveryTests(unittest.TestCase):
    def sample(self,year,acts):
        def api(endpoint,payload):
            if endpoint=="editions":
                return [1]
            return acts if payload["year"]==year else ["1"]
        return d["collect"]([year-1,year],api)

    def test_new_and_missing(self):
        old=self.sample(2026,["10","11"])
        current=self.sample(2026,["11","12A"])
        report=d["changes"](current,old)
        self.assertEqual(report["status"],"REVIEW_REQUIRED")
        self.assertEqual(report["new"],[d["ELI"]+"2026/1/12A"])
        self.assertEqual(report["missing"],[d["ELI"]+"2026/1/10"])
        self.assertEqual(d["changes"](old,old)["status"],"NO_NEW_IDENTIFIERS")

    def test_initial_and_year_rollover(self):
        old=self.sample(2026,["1"])
        current=self.sample(2027,["3"])
        self.assertEqual(d["changes"](old)["status"],"INITIAL_REVIEW_REQUIRED")
        report=d["changes"](current,old)
        self.assertEqual(report["missing"],[])
        self.assertEqual(report["new"],[d["ELI"]+"2027/1/3"])
        with self.assertRaises(ValueError):
            d["changes"](old,current)

    def test_invalid_api(self):
        for value in [[1,1],[True],[-1],{}]:
            with self.assertRaises(ValueError):
                d["edition_list"](value)
        for value in [["../x"],["12","12"],[12],{}]:
            with self.assertRaises(ValueError):
                d["act_list"](value)
        with self.assertRaises(ValueError):
            d["NoRedirect"]().redirect_request(None,None,302,"",{},"https://example.test")

if __name__=="__main__":
    unittest.main()
