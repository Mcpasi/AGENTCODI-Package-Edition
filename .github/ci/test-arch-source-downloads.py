#!/usr/bin/env python3
"""Offline regressions for source-download recovery, integrity and build handoff."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stdout
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
import urllib.error
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "scripts/arch-terminal/fetch-sources.py"
spec = importlib.util.spec_from_file_location("arch_downloads", HELPER)
downloads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(downloads)


def fixture_inputs():
    contents = {name: (name + " pinned archive").encode() for name in downloads.INPUTS}
    lock = {name: {"file": name + ".archive", "url": "https://sources.test/" + name,
                   "sha256": hashlib.sha256(data).hexdigest()}
            for name, data in contents.items()}
    return lock, contents


class ArchSourceDownloadsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="arch-download-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.cache = self.root / "cache"
        self.destination = self.root / "work/.cache/sources"
        self.lock, self.contents = fixture_inputs()
        self.lock_path = self.root / "dependencies.lock.json"
        self.lock_path.write_text(json.dumps(self.lock))
        patch = mock.patch.object(downloads.urllib.request, "urlopen", side_effect=self.response)
        self.urlopen = patch.start()
        self.addCleanup(patch.stop)
        patch = mock.patch.object(downloads.time, "sleep")
        self.sleep = patch.start()
        self.addCleanup(patch.stop)
        output = redirect_stdout(io.StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)

    def response(self, request, **kwargs):
        self.assertEqual(downloads.TIMEOUT, kwargs["timeout"])
        name = request.full_url.rsplit("/", 1)[1]
        return io.BytesIO(self.contents[name])

    def seed(self, *names):
        self.cache.mkdir(parents=True, exist_ok=True)
        for name in names:
            (self.cache / self.lock[name]["file"]).write_bytes(self.contents[name])

    def prepare(self):
        downloads.prepare(self.lock_path, self.cache, self.destination)

    def assert_no_partials(self):
        self.assertEqual([], list(self.root.rglob("*.part")))

    def test_timeout_before_response_retries_and_seeds_the_upstream_cache(self):
        self.seed("proot", "libarchive")
        self.urlopen.side_effect = [TimeoutError("read operation timed out"), io.BytesIO(self.contents["talloc"])]
        self.prepare()
        self.assertEqual(2, self.urlopen.call_count)
        self.sleep.assert_called_once_with(5)
        for name in downloads.INPUTS:
            filename = self.lock[name]["file"]
            self.assertEqual(self.contents[name], (self.cache / filename).read_bytes())
            self.assertEqual(self.contents[name], (self.destination / filename).read_bytes())
        self.assert_no_partials()

    def test_interrupted_body_restarts_without_appending_partial_bytes(self):
        self.seed("proot", "libarchive")

        class InterruptedResponse(io.BytesIO):
            def read(self, *args):
                if self.tell():
                    raise TimeoutError("body read timed out")
                return super().read(*args)

        interrupted = InterruptedResponse(b"incomplete bytes")
        self.urlopen.side_effect = [interrupted, io.BytesIO(self.contents["talloc"])]
        self.prepare()
        self.assertTrue(interrupted.closed)
        self.assertEqual(self.contents["talloc"], (self.destination / "talloc.archive").read_bytes())
        self.assertEqual(2, self.urlopen.call_count)
        self.assert_no_partials()

    def test_transient_http_and_connection_errors_are_retried(self):
        failures = [urllib.error.HTTPError("https://sources.test/talloc", code, "temporary", {}, io.BytesIO())
                    for code in (408, 429, 500, 502, 503, 504)]
        failures.extend([urllib.error.URLError("temporary DNS failure"), ConnectionResetError("reset"),
                         http.client.IncompleteRead(b"partial", 100)])
        for index, failure in enumerate(failures):
            with self.subTest(error=failure):
                self.urlopen.reset_mock()
                self.sleep.reset_mock()
                self.urlopen.side_effect = [failure, io.BytesIO(self.contents["talloc"])]
                archive = downloads.fetch(self.lock["talloc"], self.cache / str(index))
                self.assertEqual(self.contents["talloc"], archive.read_bytes())
                self.assertEqual(2, self.urlopen.call_count)
                self.sleep.assert_called_once_with(5)
                self.assert_no_partials()

    def test_permanent_http_errors_fail_immediately(self):
        for code in (401, 403, 404):
            with self.subTest(code=code):
                self.urlopen.reset_mock()
                failure = urllib.error.HTTPError("https://sources.test/talloc", code, "permanent", {}, io.BytesIO())
                self.urlopen.side_effect = failure
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    downloads.fetch(self.lock["talloc"], self.cache)
                self.assertIs(failure, caught.exception)
                self.assertEqual(1, self.urlopen.call_count)
                self.sleep.assert_not_called()
                self.assert_no_partials()

    def test_retry_budget_is_bounded_with_exponential_backoff(self):
        self.seed("proot", "libarchive")
        self.urlopen.side_effect = TimeoutError("still unavailable")
        with self.assertRaisesRegex(RuntimeError, "Download failed after 4 attempts: talloc.archive"):
            self.prepare()
        self.assertEqual(4, self.urlopen.call_count)
        self.assertEqual([mock.call(5), mock.call(10), mock.call(20)], self.sleep.call_args_list)
        self.assertFalse((self.cache / "talloc.archive").exists())
        self.assertFalse((self.destination / "talloc.archive").exists())
        self.assert_no_partials()

    def test_verified_cache_works_offline_across_isolated_builds(self):
        self.seed(*downloads.INPUTS)
        self.urlopen.side_effect = AssertionError("a valid cache must not access the network")
        self.prepare()
        second = self.root / "another-work/.cache/sources"
        downloads.prepare(self.lock_path, self.cache, second)
        for name, data in self.contents.items():
            filename = self.lock[name]["file"]
            self.assertEqual(data, (self.destination / filename).read_bytes())
            self.assertEqual(data, (second / filename).read_bytes())
        self.urlopen.assert_not_called()
        self.sleep.assert_not_called()

    def test_ci_prefetch_can_save_verified_inputs_before_any_build_starts(self):
        downloads.prepare(self.lock_path, self.cache)
        self.assertEqual(3, self.urlopen.call_count)
        self.assertFalse(self.destination.exists())
        for name, data in self.contents.items():
            self.assertEqual(data, (self.cache / self.lock[name]["file"]).read_bytes())
        self.urlopen.reset_mock()
        self.urlopen.side_effect = AssertionError("prefetched inputs must work offline")
        self.prepare()
        self.urlopen.assert_not_called()
        self.assert_no_partials()

    def test_corrupt_cache_is_redownloaded_and_corrupt_destination_is_replaced(self):
        self.seed(*downloads.INPUTS)
        (self.cache / "talloc.archive").write_bytes(b"bad cached bytes")
        self.destination.mkdir(parents=True)
        (self.destination / "talloc.archive").write_bytes(b"bad staged bytes")
        self.prepare()
        self.assertEqual(1, self.urlopen.call_count)
        self.assertEqual(self.contents["talloc"], (self.cache / "talloc.archive").read_bytes())
        self.assertEqual(self.contents["talloc"], (self.destination / "talloc.archive").read_bytes())
        self.assert_no_partials()

    def test_checksum_mismatch_cannot_publish_or_replace_staged_sources(self):
        self.seed("proot", "libarchive")
        self.destination.mkdir(parents=True)
        target = self.destination / "talloc.archive"
        target.write_bytes(b"previous staged contents")
        self.urlopen.side_effect = lambda *args, **kwargs: io.BytesIO(b"wrong downloaded contents")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch: talloc.archive"):
            self.prepare()
        self.assertEqual(b"previous staged contents", target.read_bytes())
        self.assertFalse((self.cache / "talloc.archive").exists())
        self.assertEqual(1, self.urlopen.call_count)
        self.sleep.assert_not_called()
        self.assert_no_partials()

    def test_local_write_failure_is_not_retried_as_a_network_error(self):
        with mock.patch.object(downloads.shutil, "copyfileobj", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(OSError, "disk full"):
                downloads.fetch(self.lock["talloc"], self.cache)
        self.assertEqual(1, self.urlopen.call_count)
        self.sleep.assert_not_called()
        self.assert_no_partials()

    def test_concurrent_downloads_publish_only_complete_verified_files(self):
        barrier = threading.Barrier(2)

        def response(request, **kwargs):
            barrier.wait(timeout=5)
            return self.response(request, **kwargs)

        self.urlopen.side_effect = response
        with ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(lambda _: downloads.fetch(self.lock["talloc"], self.cache), range(2)))
        self.assertEqual(results[0], results[1])
        self.assertEqual(self.contents["talloc"], results[0].read_bytes())
        self.assert_no_partials()


class ArchSourceHandoffTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="arch-source-handoff-' space-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        integration = self.root / "scripts/arch-terminal"
        integration.mkdir(parents=True)
        for name in ("fetch-sources.py", "test-upstream.sh", "build-runtime.sh"):
            shutil.copy2(ROOT / "scripts/arch-terminal" / name, integration / name)
        (integration / "verify-source.py").write_text("# Fixture verification succeeds.\n")
        self.vendor = self.root / "third_party/arch-terminal"
        scripts = self.vendor / "scripts"
        scripts.mkdir(parents=True)
        lock, contents = fixture_inputs()
        (scripts / "dependencies.lock.json").write_text(json.dumps(lock))
        (self.root / "third_party/arch-terminal.lock.json").write_text("{}")
        cache = self.root / ".cache/arch-terminal/sources"
        cache.mkdir(parents=True)
        for name, item in lock.items():
            (cache / item["file"]).write_bytes(contents[name])
        checksums = "".join(item["sha256"] + "  .cache/sources/" + item["file"] + "\n"
                            for item in lock.values())
        (scripts / "fixture-checksums").write_text(checksums)
        verify = ('#!/usr/bin/env bash\nset -eu\ncd -- "$(dirname -- "$0")/.."\n'
                  'sha256sum -c scripts/fixture-checksums\n')
        for name in ("test.sh", "test-rootfs.sh", "build-native.sh"):
            (scripts / name).write_text(verify)
        with (scripts / "build-native.sh").open("a") as script:
            script.write('mkdir -p app/build/generated/jniLibs/arm64-v8a app/build/generated/assets\n'
                         'printf "native fixture" > app/build/generated/jniLibs/arm64-v8a/libfixture.so\n'
                         'printf "asset fixture" > app/build/generated/assets/fixture\n')
        (scripts / "source-bundle.sh").write_text(
            '#!/usr/bin/env bash\nset -eu\ncd -- "$(dirname -- "$0")/.."\n'
            'mkdir -p output\nprintf "source fixture" > output/fixture.tar.gz\n'
            'sha256sum output/fixture.tar.gz > output/SOURCE-SHA256SUMS\n')
        self.work = self.root / "work"
        self.work.mkdir()

    def run_wrapper(self, name, *arguments):
        result = subprocess.run(["bash", str(self.root / "scripts/arch-terminal" / name), *arguments],
                                cwd=self.root, env={**os.environ, "RUNNER_TEMP": str(self.work)},
                                text=True, capture_output=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.vendor / ".cache").exists(), "vendored sources stay untouched")
        self.assertEqual([], list(self.work.iterdir()), "isolated copies are cleaned up")
        self.assertIn("talloc.archive: OK", result.stdout)

    def test_upstream_tests_and_rootfs_checks_receive_verified_cached_sources(self):
        self.run_wrapper("test-upstream.sh")
        self.run_wrapper("test-upstream.sh", "--rootfs")

    def test_runtime_build_receives_verified_sources_and_keeps_artifact_handoff(self):
        self.run_wrapper("build-runtime.sh")
        output = self.root / "output/arch-terminal"
        self.assertEqual(b"native fixture", (output / "native/libfixture.so").read_bytes())
        self.assertEqual(b"asset fixture", (output / "assets/fixture").read_bytes())
        self.assertTrue((output / "fixture.tar.gz").is_file())
        self.assertTrue((output / "SHA256SUMS").is_file())


if __name__ == "__main__":
    unittest.main()
