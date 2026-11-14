#!/usr/bin/env python3
"""Regression tests for actual APK bytes, bootstrap legal ownership and release gates."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("contract", REPO / "scripts/package-edition/verify-apk-contract.py")
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class ApkContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.staged = self.root / "staged"
        self.project = self.root / "project"
        self.apk = self.root / "fixture.apk"
        spec = contract.contract()
        self.entries = {"lib/" + spec["abi"] + "/" + name: name.encode()
                        for name in spec["native_files"]}
        self.entries.update({name: ("Legal fixture: " + name).encode()
                             for name in spec["third_party_assets"]})
        for name in spec["resources"]:
            self.entries[name] = (REPO / "app/src/main" / name).read_bytes()
            path = self.project / "app/src/main" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.entries[name])
        distributor = (REPO / "third_party/libcxx/DISTRIBUTOR-LICENSE").read_bytes()
        self.entries["assets/third-party/libcxx/DISTRIBUTOR-LICENSE"] = distributor
        path = self.project / "third_party/libcxx/DISTRIBUTOR-LICENSE"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(distributor)
        llvm = (REPO / "third_party/libcxx/LLVM-LICENSES").read_bytes()
        self.entries["assets/third-party/libcxx/LLVM-LICENSES"] = llvm
        path = self.project / "third_party/libcxx/LLVM-LICENSES"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(llvm)
        community_dir = REPO / "third_party/community-codex"
        target_dir = self.project / "third_party/community-codex"
        target_dir.mkdir(parents=True, exist_ok=True)
        for filename in ("Cargo.lock", "DEPENDENCY-LICENSE-INDEX.json", "DEPENDENCY-LICENSES.zip",
                         *contract.mpl_sources.FILENAMES):
            data = (community_dir / filename).read_bytes()
            (target_dir / filename).write_bytes(data)
            if filename != "Cargo.lock":
                self.entries["assets/third-party/codex/" + filename] = data
        self.community_index = json.loads((community_dir / "DEPENDENCY-LICENSE-INDEX.json").read_bytes())
        self.community_provenance = json.loads((community_dir / "DEPENDENCY-PROVENANCE.json").read_bytes())
        runtime = self.community_provenance["community_release"]
        runtime["apk_relocation"]["sha256"] = contract.digest(self.entries["lib/arm64-v8a/libcodex.so"])
        runtime["native_sha256"]["codex-code-mode-host"] = contract.digest(
            self.entries["lib/arm64-v8a/libcodex-codehost.so"])
        pins = self.project / ".github/ci/community-codex-release.json"
        pins.parent.mkdir(parents=True, exist_ok=True)
        pins.write_text(json.dumps(runtime))
        self.update_community()
        for name, source in contract.arch_assets().items():
            target = self.project / source.relative_to(REPO)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
            self.entries[name] = source.read_bytes()
        self.write()

    def update_community(self):
        base = self.project / "third_party/community-codex"
        root = "assets/third-party/codex/"
        index = json.dumps(self.community_index).encode()
        self.entries[root + "DEPENDENCY-LICENSE-INDEX.json"] = index
        self.community_provenance["index_sha256"] = contract.digest(index)
        self.community_provenance["archive_sha256"] = contract.digest(self.entries[root + "DEPENDENCY-LICENSES.zip"])
        self.entries[root + "DEPENDENCY-PROVENANCE.json"] = json.dumps(self.community_provenance).encode()
        for filename in ("DEPENDENCY-LICENSE-INDEX.json", "DEPENDENCY-LICENSES.zip", "DEPENDENCY-PROVENANCE.json"):
            (base / filename).write_bytes(self.entries[root + filename])

    def test_missing_locked_dependency_is_rejected(self):
        selected = next(c for c in self.community_index["components"]
                        if c["kind"] == "cargo-locked-dependency-closure")
        self.community_index["components"].remove(selected)
        self.community_provenance["cargo_component_count"] -= 1
        self.update_community()
        self.write()
        with self.assertRaisesRegex(ValueError, "Community legal locked dependency coverage"):
            self.verify()

    def write(self, staged=True):
        if staged:
            for name, data in self.entries.items():
                if name.startswith(("lib/", "assets/")):
                    path = self.staged / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)

    def verify(self, release=False):
        return contract.verify(self.apk, self.staged, self.project, release=release)

    def test_exact_payload_and_legal_bytes_pass_with_complete_notices(self):
        report = self.verify()
        self.assertTrue(report["license_release_ready"])
        self.assertEqual("0.2.0", report["arch_terminal"]["version"])

    def test_changed_arch_rootfs_pin_is_rejected(self):
        self.entries["assets/rootfs.lock.json"] = b"{}"
        self.write()
        with self.assertRaisesRegex(ValueError, "Arch Terminal asset differs"):
            self.verify()

    def test_changed_arch_license_is_rejected(self):
        self.entries["assets/licenses/LICENSE"] = b"changed license"
        self.write()
        with self.assertRaisesRegex(ValueError, "Arch Terminal asset differs"):
            self.verify()

    def test_retired_apt_payload_is_rejected(self):
        self.entries["assets/third-party/package-bootstrap/bootstrap-aarch64.zip"] = b"retired"
        self.write()
        with self.assertRaisesRegex(ValueError, "APK asset set differs"):
            self.verify()

    def test_foreign_abi_is_rejected(self):
        self.entries["lib/x86_64/libcodex.so"] = b"foreign"
        self.write()
        with self.assertRaisesRegex(ValueError, "native file/ABI"):
            self.verify()

    def test_retired_and_unknown_assets_are_rejected(self):
        for name in ("assets/third-party/node/LICENSE", "assets/unreviewed.bin"):
            self.entries[name] = b"unexpected"
            self.write()
            with self.assertRaisesRegex(ValueError, "asset set"):
                self.verify()
            del self.entries[name]

    def test_native_mutation_after_staging_is_rejected(self):
        self.entries["lib/arm64-v8a/libcodex.so"] = b"mutated"
        self.write(staged=False)
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.verify()

    def test_legal_mutation_after_staging_is_rejected(self):
        self.entries["assets/third-party/codex/NOTICE"] = b"mutated"
        self.write(staged=False)
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.verify()

    def test_final_release_accepts_complete_dependency_notices(self):
        report = self.verify(release=True)
        self.assertTrue(report["license_release_ready"])
        self.assertFalse(report["release_blockers"])
        self.assertEqual(13, report["community"]["mpl_sources"]["components"])
        self.assertEqual(12, report["community"]["mpl_sources"]["source_archives"])

    def test_release_rejects_missing_mpl_source_delivery(self):
        for filename in contract.mpl_sources.FILENAMES:
            path = "assets/third-party/codex/" + filename
            original = self.entries.pop(path)
            self.write()
            with self.assertRaisesRegex(ValueError, "asset set"):
                self.verify(release=True)
            self.entries[path] = original

    def test_new_mpl_component_requires_updated_source_offer(self):
        self.community_index["components"][0]["license"] = "MPL-2.0"
        self.update_community()
        self.write()
        with self.assertRaisesRegex(ValueError, "MPL source availability notice differs"):
            self.verify(release=True)

    def test_final_release_is_blocked_when_a_dependency_loses_its_notice(self):
        component = self.community_index["components"][0]
        component["files"] = []
        self.community_index["gaps"] = [{"name": component["name"], "version": component["version"]}]
        covered = {record["path"] for part in self.community_index["components"] for record in part["files"]}
        root = "assets/third-party/codex/"
        with zipfile.ZipFile(io.BytesIO(self.entries[root + "DEPENDENCY-LICENSES.zip"])) as archive:
            texts = {name: archive.read(name) for name in covered}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name, data in texts.items():
                archive.writestr(name, data)
        self.entries[root + "DEPENDENCY-LICENSES.zip"] = stream.getvalue()
        self.update_community()
        self.write()
        self.assertFalse(self.verify()["license_release_ready"])
        with self.assertRaisesRegex(ValueError, "Final APK license gate"):
            self.verify(release=True)

    def test_community_notice_cannot_be_reused_with_a_different_native_binary(self):
        self.entries["lib/arm64-v8a/libcodex.so"] = b"unexpected replacement runtime"
        self.write()
        with self.assertRaisesRegex(ValueError, "native artifact binding"):
            self.verify()

    def test_community_lock_snapshot_cannot_drift(self):
        lock = self.project / "third_party/community-codex/Cargo.lock"
        lock.write_bytes(lock.read_bytes() + b"\n# changed snapshot\n")
        with self.assertRaisesRegex(ValueError, "Cargo.lock binding"):
            self.verify()

    def test_community_package_checksum_must_match_the_pinned_lock(self):
        component = next(part for part in self.community_index["components"] if part.get("checksum"))
        component["checksum"] = "0" * 64
        self.update_community()
        self.write()
        with self.assertRaisesRegex(ValueError, "package/source checksum"):
            self.verify()

    def test_v8_notice_revision_must_match_its_audited_source(self):
        component = next(part for part in self.community_index["components"] if part["kind"] == "v8-source-material")
        component["source"] = "0" * 40
        self.update_community()
        self.write()
        with self.assertRaisesRegex(ValueError, "V8/Rust legal source binding"):
            self.verify()

    def test_community_archive_cannot_contain_unindexed_material(self):
        root = "assets/third-party/codex/"
        stream = io.BytesIO(self.entries[root + "DEPENDENCY-LICENSES.zip"])
        with zipfile.ZipFile(stream, "a") as archive:
            archive.writestr("texts/unindexed.txt", b"unexpected")
        self.entries[root + "DEPENDENCY-LICENSES.zip"] = stream.getvalue()
        self.update_community()
        self.write()
        with self.assertRaisesRegex(ValueError, "Community legal ZIP file set"):
            self.verify()

    def test_duplicate_apk_entry_is_rejected(self):
        with zipfile.ZipFile(self.apk, "a") as archive:
            archive.writestr("assets/third-party/codex/NOTICE", b"duplicate")
        with self.assertRaisesRegex(ValueError, "Duplicate APK"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
