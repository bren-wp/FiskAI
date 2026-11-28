import pathlib
import runpy
import unittest
d=runpy.run_path(str(pathlib.Path(__file__).with_name("audit-runtime-dependencies.py")))
class AuditTest(unittest.TestCase):
    def test_pagination_and_order(self):
        p=["pkg:maven/g/a@1","pkg:maven/g/b@2"]
        calls=[]
        def query(queries):
            calls.append(queries)
            if len(calls)==1:return {"results":[{"vulns":[{"id":"CVE-2026-1234"}],"next_page_token":"next"},{}]}
            self.assertEqual(queries,[{"package":{"purl":p[0]},"page_token":"next"}])
            return {"results":[{"vulns":[{"id":"GHSA-abc"}]}]}
        result=d["scan"](p,query)
        self.assertEqual(result[0]["advisories"],["CVE-2026-1234","GHSA-abc"])
        self.assertEqual(result[1]["advisories"],[])
    def test_reject_incomplete(self):
        p=["pkg:maven/g/a@1"]
        for result in [{"results":[]},{"results":[{"next_page_token":"repeat"}]},{"results":[{"error":"failed"}]}]:
            with self.assertRaises(ValueError):d["scan"](p,lambda _:result)
        with self.assertRaises(ValueError):d["packages"]({"bomFormat":"CycloneDX","components":[]})
    def test_inventory(self):
        self.assertEqual(d["packages"]({"bomFormat":"CycloneDX","components":[{"purl":"pkg:maven/g/a@1"}]}),["pkg:maven/g/a@1"])
if __name__=="__main__":unittest.main()
