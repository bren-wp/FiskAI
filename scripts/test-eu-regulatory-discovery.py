import datetime
import pathlib
import runpy
import unittest
d=runpy.run_path(str(pathlib.Path(__file__).with_name("eu-regulatory-discovery.py")))
class TestEU(unittest.TestCase):
    def test_decode(self):
        start=datetime.date(2026,11,15);end=datetime.date(2026,11,28)
        row={"act":{"type":"uri","value":"http://publications.europa.eu/resource/oj/L_202600001"},
             "published":{"type":"literal","value":"2026-11-20"}}
        self.assertEqual(d["decode"]({"results":{"bindings":[row,row]}},start,end)[0]["oj"],"L_202600001")
        row["act"]["value"]="https://example.test/resource/oj/L_202600001"
        with self.assertRaises(ValueError):d["decode"]({"results":{"bindings":[row]}},start,end)
        self.assertIn("official-journal-act_date_publication",d["query"](start,end))
if __name__=="__main__":unittest.main()
