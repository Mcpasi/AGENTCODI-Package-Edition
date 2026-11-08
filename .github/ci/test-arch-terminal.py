#!/usr/bin/env python3
"""Portable integration regressions for unchanged Arch Terminal and its adapters."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import tarfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "modules/native-engine/src/main/cpp"


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


source = module("arch_source", ROOT / "scripts/arch-terminal/verify-source.py")
resources = module("arch_resources", ROOT / "scripts/arch-terminal/prepare-resources.py")
sources = module("arch_sources", ROOT / "scripts/arch-terminal/source-bundle.py")


class ArchSourceTest(unittest.TestCase):
    def test_upstream_snapshot_is_unchanged(self):
        source.verify()

    def test_required_upstream_and_compile_inputs_are_not_ignored(self):
        lock = json.loads((ROOT / "third_party/arch-terminal.lock.json").read_text())
        paths = ["third_party/arch-terminal/" + name for name in lock["files"]]
        paths.extend(["third_party/androidx-annotation/annotation-1.3.0.jar",
                      "third_party/android-lambda-stubs/core-lambda-stubs.jar"])
        result = subprocess.run(["git", "check-ignore", "--no-index", "--stdin"],
                                cwd=ROOT, input="\n".join(paths) + "\n", text=True, capture_output=True)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_source_gate_rejects_a_changed_upstream_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copytree(ROOT / "third_party/arch-terminal", root / "third_party/arch-terminal")
            shutil.copy2(ROOT / "third_party/arch-terminal.lock.json", root / "third_party/arch-terminal.lock.json")
            extra = root / "third_party/arch-terminal/app/src/main/java/de/archterminal/Injected.java"
            extra.write_text("unreviewed source")
            with self.assertRaisesRegex(ValueError, "file set changed"):
                source.verify(root)
            extra.unlink()
            (root / "third_party/arch-terminal/app/src/main/java/de/archterminal/LaunchSpec.java").write_text("changed")
            with self.assertRaisesRegex(ValueError, "source changed"):
                source.verify(root)

    def test_resources_preserve_both_terminal_namespaces_and_app_theme(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "res"
            resources.prepare(output)
            self.assertTrue((output / "drawable/ic_arch_linux.xml").is_file())
            self.assertTrue((output / "drawable/text_select_handle_left_material.xml").is_file())
            self.assertEqual((ROOT / "app/src/main/res/values/styles.xml").read_bytes(),
                             (output / "values/styles.xml").read_bytes())
            self.assertIn("ArchTerminalTheme", (output / "values/arch_terminal.xml").read_text())

    def test_source_bundle_contains_the_inventory_and_portable_checksums(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "modules").mkdir()
            (root / "modules/source.java").write_text("exact application source")
            (root / "LICENSE").write_text("original license")
            output = root / "output/arch-terminal"
            output.mkdir(parents=True)
            upstream = output / "ArchTerminal-0.1.9-native-inputs.tar.gz"
            upstream.write_bytes(b"exact upstream inputs")
            with mock.patch.object(sources, "ROOT", root), mock.patch.object(
                    sources.subprocess, "check_output", return_value=b"LICENSE\0modules/source.java\0"):
                sources.bundle()
            with tarfile.open(output / "AGENTCODI-Arch-Terminal-source.tar.gz") as archive:
                self.assertEqual(set(archive.getnames()), {"LICENSE", "modules/source.java"})
                self.assertEqual(archive.extractfile("modules/source.java").read(), b"exact application source")
            sums = (output / "SOURCE-SHA256SUMS").read_text().splitlines()
            self.assertEqual(len(sums), 2)
            for record in sums:
                expected, name = record.split("  ", 1)
                self.assertNotIn("/", name)
                self.assertEqual(hashlib.sha256((output / name).read_bytes()).hexdigest(), expected)

    def test_branch_build_exception_is_present_at_every_workflow_gate(self):
        branch = "Mcpasi/Fusion_Arch_Termux"
        for name in ("apk.yml", "apk-release.yml", "bootstrap.yml"):
            text = (ROOT / ".github/workflows" / name).read_text()
            for line in text.splitlines():
                if "if: github.ref" in line:
                    self.assertIn("refs/heads/main", line)
                    self.assertIn("refs/heads/" + branch, line)
            self.assertIn("TODO:", text)
            if name != "bootstrap.yml":
                self.assertIn("- '" + branch + "'", text)
                self.assertIn("name: agentcodi-arch-terminal", text)
                self.assertNotIn("agentcodi-package-bootstrap", text)


class ArchAdapterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.build = Path(cls.temporary.name)
        compiler = os.environ.get("CXX", "g++")
        for source_name, output in (("arch_shell_main.cpp", "shell"), ("arch_proot_main.cpp", "proot")):
            subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
                            str(NATIVE / source_name), "-o", str(cls.build / output)], check=True)
        subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC",
                        str(NATIVE / "arch_shell_environment.cpp"), "-ldl", "-o", str(cls.build / "shell-env.so")], check=True)
        probe = cls.build / "probe.cpp"
        probe.write_text('#include <pwd.h>\n#include <unistd.h>\n#include <cstdio>\n'
                         'int main() { passwd entry{}, *result = nullptr; char buffer[65536]; '
                         'if (getpwuid_r(getuid(), &entry, buffer, sizeof(buffer), &result) || !result) return 1; '
                         'std::puts(result->pw_shell); }\n')
        subprocess.run([compiler, str(probe), "-o", str(cls.build / "probe")], check=True)
        # The ordinary Linux C++ jobs do not compile the Android-only preload
        # branch. Exercise it through real supervisor child processes as well.
        subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread",
                        "-D__ANDROID__", "-I" + str(NATIVE),
                        str(NATIVE / "app_server_process.cpp"), str(NATIVE / "png_validator.cpp"),
                        str(NATIVE / "sha256.cpp"), str(ROOT / "tests/cpp/app_server_environment_probe.cpp"),
                        "-lz", "-o", str(cls.build / "environment-probe")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="arch-adapter-' space-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.files = self.root / "files"
        self.native = self.root / "native"
        self.native.mkdir()
        self.workspace = self.files / "agentcodi/workspace"
        self.workspace.mkdir(parents=True)
        (self.files / "arch/tmp").mkdir(parents=True)
        (self.files / "arch/.arch-terminal-ready").write_text("fixture")
        (self.files / "runtime/tmp").mkdir(parents=True)
        for source_name, output in (("shell", "libagentcodi-shell.so"), ("proot", "libproot.so")):
            shutil.copy2(self.build / source_name, self.native / output)
        fake = self.native / "libarch-proot.so"
        fake.write_text("#!/usr/bin/env python3\nimport json,os,sys,signal,time\n"
                        "if os.environ.get('ARCH_TEST_WAIT'):\n"
                        " signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                        " signal.signal(signal.SIGQUIT, lambda *args: sys.exit(23))\n"
                        " print('ready', flush=True)\n"
                        " while True: time.sleep(1)\n"
                        "print(json.dumps({'argv':sys.argv[1:],'environment':dict(os.environ)}))\n")
        fake.chmod(0o700)
        self.environment = {"PATH": os.environ["PATH"], "LANG": "C.UTF-8", "AGENTCODI_ARCH_FILES": str(self.files),
                            "LD_LIBRARY_PATH": "/not-the-guest", "PROOT_NO_SECCOMP": "1"}
        self.environment.pop("LD_PRELOAD", None)

    def execute(self, name, *arguments, check=True):
        return subprocess.run([str(self.native / name), *arguments], env=self.environment,
                              cwd=self.workspace, text=True, capture_output=True, check=check, timeout=10)

    def test_codex_bash_preserves_command_arguments_and_workspace(self):
        command = "printf '%s' 'quotes and $(literal command)'"
        result = json.loads(self.execute("libagentcodi-shell.so", "-c", command, "arg0", "x y").stdout)
        args = result["argv"]
        self.assertEqual(args[-5:], ["/bin/bash", "-c", command, "arg0", "x y"])
        self.assertEqual(args[args.index("-w") + 1], str(self.workspace))
        self.assertIn(str(self.workspace) + ":" + str(self.workspace), args)
        self.assertIn(str(self.workspace) + ":/workspace", args)
        self.assertIn("--kill-on-exit", args)

    def test_stdio_mcp_tools_keep_their_argv(self):
        result = json.loads(self.execute("libagentcodi-shell.so", "--command", "npx", "-y", "a package", "--", "'").stdout)
        self.assertEqual(result["argv"][-5:], ["npx", "-y", "a package", "--", "'"])

    def test_stdio_mcp_configuration_survives_guest_environment_cleanup(self):
        self.environment["MCP_TEST_TOKEN"] = "synthetic configuration with spaces"
        self.environment["CODEX_HOME"] = "/native/account-context"
        result = json.loads(self.execute("libagentcodi-shell.so", "--command", "node", "server.js").stdout)
        args = result["argv"]
        guest = args[args.index("/usr/bin/env") + 1:]
        self.assertIn("MCP_TEST_TOKEN=synthetic configuration with spaces", guest)
        self.assertFalse(any(value.startswith(("LD_", "PROOT_", "CODEX_HOME=")) for value in guest))
        self.assertEqual(guest[-2:], ["node", "server.js"])

    def test_android_loader_environment_does_not_enter_the_guest(self):
        result = json.loads(self.execute("libagentcodi-shell.so", "--interactive").stdout)
        env = result["environment"]
        self.assertNotIn("LD_LIBRARY_PATH", env)
        self.assertNotIn("LD_PRELOAD", env)
        self.assertEqual(env["PROOT_LOADER"], str(self.native / "libproot-loader.so"))
        self.assertEqual(env["PROOT_TMP_DIR"], str(self.files / "runtime/tmp"))
        self.assertEqual(env["PROOT_NO_SECCOMP"], "1")
        args = result["argv"]
        guest = args[args.index("/usr/bin/env") + 1:]
        self.assertEqual(guest[0], "-i")
        self.assertIn("HOME=/root", guest)
        self.assertIn("PATH=/usr/local/sbin:/usr/local/bin:/usr/bin", guest)
        self.assertEqual(guest[-2:], ["/bin/bash", "--login"])

    def test_upstream_terminal_launch_shares_codex_workspace(self):
        result = json.loads(self.execute("libproot.so", "--kill-on-exit", "--link2symlink", "-0",
                            "-r", str(self.files / "arch"), "-w", "/root", "-b", "/dev",
                            "/usr/bin/env", "-i", "HOME=/root", "/bin/bash", "--login").stdout)
        args = result["argv"]
        self.assertEqual(args[args.index("-w") + 1], str(self.workspace))
        self.assertIn(str(self.workspace) + ":/workspace", args)
        self.assertEqual(args[-5:], ["/usr/bin/env", "-i", "HOME=/root", "/bin/bash", "--login"])

    def test_missing_arch_fails_before_running_any_command(self):
        (self.files / "arch/.arch-terminal-ready").unlink()
        result = self.execute("libagentcodi-shell.so", "-c", "echo should-not-run", check=False)
        self.assertEqual(result.returncode, 126)
        self.assertEqual(result.stdout, "")
        self.assertIn("install Arch", result.stderr)

    def test_command_cancellation_uses_proot_cleanup_and_waits_for_exit(self):
        process = subprocess.Popen([str(self.native / "libagentcodi-shell.so"), "-c", "wait"],
                                   env={**self.environment, "ARCH_TEST_WAIT": "1"},
                                   cwd=self.workspace, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual(process.stdout.readline().strip(), "ready")
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=10)
            self.assertEqual(process.returncode, 23)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_unsupported_upstream_argv_fails_closed(self):
        result = self.execute("libproot.so", "-r", "/other-root", "/bin/sh", check=False)
        self.assertEqual(result.returncode, 126)

    def test_default_shell_discovery_uses_the_arch_bash_launcher(self):
        expected = str(self.files / "agentcodi/state/arch-bin/bash")
        env = {**os.environ, "LD_PRELOAD": str(self.build / "shell-env.so"), "AGENTCODI_ARCH_SHELL": expected}
        result = subprocess.run([str(self.build / "probe")], env=env, capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.strip(), expected)
        env["AGENTCODI_ARCH_SHELL"] = "relative/path"
        result = subprocess.run([str(self.build / "probe")], env=env, capture_output=True, text=True, check=True)
        self.assertNotEqual(result.stdout.strip(), "relative/path")

    def test_termux_passwd_macro_preserves_the_native_preload_symbol_and_probe(self):
        headers = self.root / "termux-headers"
        headers.mkdir()
        # Termux's patched pwd.h defines this wrapper and remaps getpwuid_r.
        # A wrapper call also rewrites pw_shell after the libc lookup, so the
        # APK probe must bypass the macro to test the real preloaded symbol.
        (headers / "pwd.h").write_text('''#pragma once
#include_next <pwd.h>
extern "C" {
static int android_polyfill_getpwuid_r(uid_t uid, passwd* entry, char* buffer,
                                     size_t size, passwd** result) {
    int status = getpwuid_r(uid, entry, buffer, size, result);
    if (status == 0 && *result != nullptr)
        entry->pw_shell = const_cast<char*>("/termux/polyfill/bash");
    return status;
}
}
#define getpwuid_r android_polyfill_getpwuid_r
''')
        compiler = os.environ.get("CXX", "g++")
        flags = [compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-isystem", str(headers)]
        # LD_PRELOAD separates entries at whitespace; use the compiler staging
        # directory for the library, while keeping quoted paths in the probe.
        library = self.build / "termux-shell-env.so"
        probe = self.native / "termux-shell-probe"
        for command in (
                flags + ["-shared", "-fPIC", str(NATIVE / "arch_shell_environment.cpp"), "-ldl", "-o", str(library)],
                flags + [str(ROOT / "tests/cpp/arch_shell_probe.cpp"), "-o", str(probe)]):
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        symbols = subprocess.check_output(["nm", "-D", "--defined-only", str(library)], text=True)
        self.assertIn("getpwuid_r", {line.split()[-1] for line in symbols.splitlines()})
        expected = str(self.files / "agentcodi/state/arch-bin/bash")
        environment = {"PATH": os.environ["PATH"], "AGENTCODI_ARCH_SHELL": expected}
        result = subprocess.run([str(probe)], env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, "probe must reject an absent preload")
        environment["LD_PRELOAD"] = str(library)
        result = subprocess.run([str(probe)], env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def supervisor_environment(self, mode):
        for relative in ("files/agentcodi/codex-home", "files/agentcodi/home",
                         "files/agentcodi/state", "files/usr", "files/tmp"):
            (self.root / relative).mkdir(mode=0o700, parents=True, exist_ok=True)
        executable = self.native / "environment-probe"
        shutil.copy2(self.build / "environment-probe", executable)
        result = subprocess.run([str(executable), str(self.root), mode],
                                env=self.environment, capture_output=True, text=True, check=True, timeout=10)
        return result.stdout.splitlines()

    def test_android_supervisor_fixture_does_not_require_the_apk_preload(self):
        self.assertFalse((self.native / "libagentcodi-arch-env.so").exists())
        self.assertEqual(self.supervisor_environment("custom"), ["unset", "unset"])

    def test_android_codex_launches_keep_the_arch_shell_preload(self):
        preload = self.native / "libagentcodi-arch-env.so"
        shutil.copy2(self.build / "shell-env.so", preload)
        expected = [str(preload), str(self.files / "agentcodi/state/arch-bin/bash")]
        for mode in ("managed", "explicit-app-server"):
            with self.subTest(mode=mode):
                self.assertEqual(self.supervisor_environment(mode), expected)


if __name__ == "__main__":
    unittest.main()
