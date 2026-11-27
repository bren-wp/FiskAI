#!/usr/bin/env python3
"""Regression tests for the publication gate; fixtures are not real releases."""
import hashlib
import importlib.util
import pathlib
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("gate", pathlib.Path(__file__).with_name("check-release.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        for name in gate.REQUIRED:
            path = self.root / name
            if name.endswith((".zip", ".apk", ".aab")):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("payload", "fixture")
            else:
                path.write_text("fixture")
        self.branding()
        with zipfile.ZipFile(self.root / "FiskAI-screenshots.zip", "w") as archive:
            for platform in ("android", "web", "admin"):
                archive.writestr(platform + "/fixture.png", b"fixture")
        self.manifest()

    def branding(self, missing=None, mismatch=None, extra=False, duplicate=False):
        with zipfile.ZipFile(self.root / "FiskAI-brending.zip", "w") as archive:
            for package in ("01", "02"):
                prefix = "FiskAI/brending/originals/" + package + "/"
                archive.writestr(prefix + "ref.png", b"fixture")
                if extra and package == "01":
                    archive.writestr(prefix + "extra.png", b"extra")
                if missing == package:
                    continue
                checksum = hashlib.sha256(b"fixture").hexdigest()
                line = ("0" * 64 if mismatch == package else checksum) + "  ref.png\n"
                archive.writestr("FiskAI/brending/package-" + package + ".sha256",
                                 checksum + "  original ZIP\n" + line + (line if duplicate else ""))

    def manifest(self):
        (self.root / "SHA256SUMS.txt").write_text("".join(
            gate.digest(p) + "  " + p.name + "\n"
            for p in sorted(self.root.iterdir()) if p.name != "SHA256SUMS.txt"))

    def test_complete_fixture(self):
        gate.validate(self.root)

    def test_missing_upgrade(self):
        (self.root / "UPGRADE.md").unlink()
        self.manifest()
        with self.assertRaisesRegex(ValueError, "UPGRADE.md"):
            gate.validate(self.root)

    def test_missing_manifest(self):
        self.branding(missing="01")
        self.manifest()
        with self.assertRaises(KeyError):
            gate.validate(self.root)

    def test_modified_branding(self):
        self.branding(mismatch="02")
        self.manifest()
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            gate.validate(self.root)

    def test_unlisted_branding(self):
        self.branding(extra=True)
        self.manifest()
        with self.assertRaisesRegex(ValueError, "Incomplete branding"):
            gate.validate(self.root)

    def test_duplicate_branding_manifest(self):
        self.branding(duplicate=True)
        self.manifest()
        with self.assertRaisesRegex(ValueError, "duplicate branding"):
            gate.validate(self.root)

    def test_traversal(self):
        with zipfile.ZipFile(self.root / "FiskAI-source.zip", "a") as archive:
            archive.writestr("../outside", "fixture")
        self.manifest()
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            gate.validate(self.root)

    def test_duplicate_archive_entry(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.root / "FiskAI-source.zip", "a") as archive:
                archive.writestr("payload", "duplicate")
        self.manifest()
        with self.assertRaisesRegex(ValueError, "duplicate archive"):
            gate.validate(self.root)

    def test_symlink_entry(self):
        with zipfile.ZipFile(self.root / "FiskAI-source.zip", "a") as archive:
            entry = zipfile.ZipInfo("link")
            entry.create_system = 3
            entry.external_attr = 0o120777 << 16
            archive.writestr(entry, "target")
        self.manifest()
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            gate.validate(self.root)

    def test_modified_artifact(self):
        (self.root / "UPGRADE.md").write_text("changed")
        with self.assertRaisesRegex(ValueError, "Invalid artifact"):
            gate.validate(self.root)


if __name__ == "__main__":
    unittest.main()
