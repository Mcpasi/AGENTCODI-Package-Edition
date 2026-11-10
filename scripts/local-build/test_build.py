#!/usr/bin/env python3
"""Offline regressions for the independent local build driver."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("local_build", HERE / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class LocalBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="agentcodi-local-tests-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_runtime_pins_follow_the_existing_builder(self):
        pins = build.read_pins(build.ROOT / "scripts/build-debug-apk.sh")
        self.assertIn("/v" + pins["CODEX_ANDROID_VERSION"] + "/", pins["CODEX_ANDROID_URL"])
        self.assertIn("/" + pins["R8_VERSION"] + "/", pins["R8_URL"])
        self.assertEqual("arm64-v8a", pins["ABI"])

    def test_pin_reader_rejects_shell_expressions_without_executing_them(self):
        script = self.directory / "builder.sh"
        marker = self.directory / "SHOULD_NOT_EXIST"
        script.write_text((build.ROOT / "scripts/build-debug-apk.sh").read_text().replace(
            'APP_NAME="AGENTCODI Package"', f'APP_NAME="$(touch {marker})"'))
        with self.assertRaisesRegex(build.BuildError, "Unsupported expression"):
            build.read_pins(script)
        self.assertFalse(marker.exists())

    def test_invalid_explicit_sdk_does_not_silently_use_a_different_installation(self):
        with self.assertRaisesRegex(build.BuildError, "Missing SDK"):
            build.choose_directory(self.directory / "absent", (), (self.directory,), "SDK")

    def test_bad_download_does_not_replace_existing_cache_bytes(self):
        destination = self.directory / "runtime.deb"
        destination.write_bytes(b"old cache")

        def fake_curl(args, **kwargs):
            Path(args[args.index("--output") + 1]).write_bytes(b"bad download")

        with patch.object(build, "run", side_effect=fake_curl):
            with self.assertRaisesRegex(build.BuildError, "SHA-256 mismatch"):
                build.download("https://example.invalid/runtime.deb", "0" * 64, destination)
        self.assertEqual(b"old cache", destination.read_bytes())
        self.assertEqual([destination], list(self.directory.iterdir()))

    def test_matching_download_cache_needs_no_network(self):
        destination = self.directory / "runtime.deb"
        destination.write_bytes(b"pinned bytes")
        with patch.object(build, "run") as command:
            self.assertEqual(destination, build.download("https://example.invalid", build.digest(destination), destination))
        command.assert_not_called()

    def test_binary_relocation_rejects_changed_layout_and_preserves_input(self):
        binary = self.directory / "library.so"
        binary.write_bytes(b"libz.so.1 libz.so.1")
        with self.assertRaisesRegex(build.BuildError, "Unexpected binary"):
            build.replace_bytes(binary, b"libz.so.1", b"libz_1.so", 1)
        self.assertEqual(b"libz.so.1 libz.so.1", binary.read_bytes())
        build.replace_bytes(binary, b"libz.so.1", b"libz_1.so", 2)
        self.assertEqual(b"libz_1.so libz_1.so", binary.read_bytes())

    def test_native_dependency_gate_rejects_missing_runtime_libraries(self):
        native = self.directory / "native"
        native.mkdir()
        contract = build.json.loads((build.ROOT / "scripts/package-edition/apk-contract.json").read_text())
        for name in contract["native_files"]:
            (native / name).write_bytes(b"fixture")

        def readelf(args, **kwargs):
            return {"-h": "Class: ELF64\nMachine: AArch64",
                    "-lW": "LOAD 0x0 0x0 0x0 0x100 0x100 R E 0x4000\n/system/bin/linker64",
                    "-dW": "(NEEDED) Shared library: [libmissing.so]"}[args[1]]

        with patch.object(build, "run", side_effect=readelf):
            with self.assertRaisesRegex(build.BuildError, "Unresolved ELF libraries"):
                build.verify_native(native, build.read_pins(build.ROOT / "scripts/build-debug-apk.sh"))

    def test_release_rejects_insecure_signing_files(self):
        key = self.directory / "keystore"
        key.write_bytes(b"private fixture")
        key.chmod(0o644)
        with patch.dict(os.environ, {"TEST_LOCAL_KEY": str(key)}):
            with self.assertRaisesRegex(build.BuildError, "must be non-empty, private"):
                build.private_file("TEST_LOCAL_KEY")
            key.chmod(0o600)
            self.assertEqual(key, build.private_file("TEST_LOCAL_KEY"))

    @unittest.skipUnless(shutil.which("javac"), "requires a local JDK")
    def test_javac_argfile_supports_checkout_paths_with_spaces_and_unicode(self):
        source = self.directory / "checkout with spaces ä" / "Example.java"
        source.parent.mkdir()
        source.write_text("final class Example {}\n")
        argfile = self.directory / "sources.txt"
        build.java_argfile(argfile, [source])
        subprocess.run(["javac", "-encoding", "UTF-8", "@" + str(argfile)], check=True)
        self.assertTrue(source.with_suffix(".class").is_file())


if __name__ == "__main__":
    unittest.main()
