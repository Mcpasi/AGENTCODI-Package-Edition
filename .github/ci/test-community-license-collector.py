#!/usr/bin/env python3
"""Check license inheritance, declared alternatives and original attribution."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("collector", Path(__file__).with_name("collect-community-licenses.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)

class CollectorTest(unittest.TestCase):
    def test_locked_closure_resolves_versions_cycles_and_conservative_edges(self):
        packages = [
            {"name": "root", "version": "1", "dependencies": ["dep 2"]},
            {"name": "dep", "version": "1"},
            {"name": "dep", "version": "2", "dependencies": ["root", "platform-only"]},
            {"name": "platform-only", "version": "1"},
            {"name": "unrelated", "version": "1"},
        ]
        self.assertEqual([("dep", "2"), ("platform-only", "1"), ("root", "1")],
                         [(p["name"], p["version"]) for p in collector.locked_closure(packages, ["root"])])
        packages[0]["dependencies"] = ["dep"]
        with self.assertRaisesRegex(ValueError, "Ambiguous locked dependency"):
            collector.locked_closure(packages, ["root"])
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous root"):
            collector.locked_closure(packages, ["absent"])

    def test_source_metadata_resolves_inherited_license_and_authors(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Cargo.toml").write_text('[workspace]\nmembers=["member"]\n'
                                            '[workspace.package]\nversion="1.2.3"\nlicense="MIT"\nauthors=["Original Author"]\n')
            (root / "member").mkdir()
            (root / "member/Cargo.toml").write_text('[package]\nname="member"\nversion.workspace=true\n'
                                                   'license.workspace=true\nauthors.workspace=true\n')
            package, location = collector.source_packages(root)[("member", "1.2.3")]
            self.assertEqual("MIT", package["license"])
            self.assertEqual(["Original Author"], package["authors"])
            self.assertEqual(root / "member", location)

    def test_reuse_license_directory_keeps_named_terms(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "LICENSES").mkdir()
            (root / "LICENSES/MIT.txt").write_text("permission")
            (root / "unrelated.txt").write_text("data")
            self.assertEqual([root / "LICENSES/MIT.txt"], collector.files(root))

    def test_selected_mit_terms_retain_actual_notice_without_placeholder_dates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "source"
            root.mkdir()
            (root / "lib.rs").write_text("// Copyright 2017 Original Author\nfn main() {}\n")
            (root / "README.md").write_text("Original package documentation")
            package = {"name": "fixture", "version": "1.0", "license": "MIT", "authors": ["Original Author"],
                       "repository": "https://github.com/example/fixture"}
            output, files, evidence = collector.declared_terms(package, root, Path(tmp) / "output", root)
            text = (output / "LICENSE-MIT.txt").read_text()
            self.assertIn("Permission is hereby granted", text)
            self.assertNotIn("<year>", text)
            self.assertNotIn("<copyright holders>", text)
            self.assertEqual("MIT", evidence["selected_spdx"])
            self.assertIn("// Copyright 2017 Original Author",
                          (output / "ORIGINAL-COPYRIGHT-NOTICES.txt").read_text())
            self.assertIn("\nSelected distribution terms: MIT\n",
                          (output / "ATTRIBUTION.txt").read_text())

    def test_apache_alternative_is_selected_only_when_declared(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "LICENSE").write_text("Complete original Apache terms")
            package = {"name": "fixture", "version": "1.0", "license": "MIT OR Apache-2.0",
                       "authors": ["Author"]}
            output, _, evidence = collector.declared_terms(package, root, root / "out", root)
            self.assertEqual("Complete original Apache terms", (output / "LICENSE-Apache-2.0.txt").read_text())
            self.assertEqual("Apache-2.0", evidence["selected_spdx"])

    def test_unsupported_license_is_not_silently_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(ValueError, "No reviewed standard-license selection"):
                collector.declared_terms({"name": "fixture", "version": "1.0", "license": "BSD-2-Clause"},
                                         root, root / "out", root)

    def test_reviewed_three_way_alternatives_select_declared_apache_terms(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "LICENSE").write_text("Complete original Apache terms")
            for expression in ("MIT OR Apache-2.0 OR LGPL-2.1-or-later", "MIT OR Apache-2.0 OR Zlib"):
                package = {"name": "fixture", "version": "1.0", "license": expression}
                _, _, evidence = collector.declared_terms(package, root, root / "out", root)
                self.assertEqual("Apache-2.0", evidence["selected_spdx"])
                self.assertEqual(expression, evidence["declared_spdx"])

if __name__ == "__main__":
    unittest.main()
