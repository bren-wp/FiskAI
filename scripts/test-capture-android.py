"""Regression tests for exact, whitespace-normalized UI capture selectors."""
import importlib.util
import pathlib
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("capture_android", pathlib.Path(__file__).with_name("capture-android.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)

class CaptureSelectorsTest(unittest.TestCase):
    def node(self, **attributes):
        root = ET.Element("hierarchy")
        ET.SubElement(root, "node", attributes)
        return root

    def test_catalog_button_whitespace(self):
        for text in ("+ Dodaj proizvod ili uslugu", "+  Dodaj proizvod ili uslugu", "+\nDodaj proizvod ili uslugu"):
            root = self.node(text=text, bounds="[20,100][300,160]")
            self.assertEqual([20,100,300,160], capture.bounds_for(root, "+ Dodaj proizvod ili uslugu"))

    def test_content_description(self):
        root = self.node(**{"content-desc":"Filtri", "bounds":"[20,10][50,40]"})
        self.assertEqual([20,10,50,40], capture.bounds_for(root, "Filtri"))

    def test_no_partial_or_invisible_matches(self):
        for text, bounds in (("Drugi Filtri","[20,10][50,40]"), ("Filtri","[20,10][20,40]"), ("Filtri","[-10,0][50,40]"), ("Filtri","broken")):
            self.assertIsNone(capture.bounds_for(self.node(text=text,bounds=bounds), "Filtri"))

if __name__ == "__main__":
    unittest.main()
