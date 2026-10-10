#!/usr/bin/env python3
"""Compare the production PTY renderer to an independent VT screen with mksh."""

import fcntl
import os
from pathlib import Path
import pty
import select
import shlex
import shutil
import signal
import struct
import subprocess
import tempfile
import termios
import time
import unittest

import pyte


PROJECT = Path(__file__).resolve().parents[2]
PROMPT = ":/data/user/0/de.agentcodi.pkg/files/agentcodi/workspace/HalloweenTown $ "


class TerminalShellTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mksh = os.environ.get("AGENTCODI_TEST_MKSH") or shutil.which("mksh")
        if not cls.mksh:
            raise RuntimeError("Install mksh or set AGENTCODI_TEST_MKSH")
        cls.temporary = tempfile.TemporaryDirectory(prefix="agentcodi-terminal-test-")
        cls.build = Path(cls.temporary.name)
        cls.binary = cls.build / "package-shell"
        # Substitute only the host executable, retaining the production argv.
        wrapper = cls.build / "exec-wrapper.cpp"
        wrapper.write_text('''#include <cerrno>
#include <cstdlib>
#include <cstring>
extern "C" int __real_execv(const char*, char* const[]);
extern "C" int __wrap_execv(const char* path, char* const args[]) {
  if (std::strcmp(path, "/system/bin/sh") != 0) { errno = EINVAL; return -1; }
  return __real_execv(std::getenv("AGENTCODI_TEST_MKSH"), args);
}
''')
        subprocess.run([
            os.environ.get("CXX", "g++"), "-std=c++17", "-Wall", "-Wextra", "-Werror",
            str(PROJECT / "modules/native-engine/src/main/cpp/package_shell_main.cpp"),
            str(wrapper), "-Wl,--wrap=execv", "-o", str(cls.binary),
        ], check=True)
        renderer = cls.build / "RenderTerminal.java"
        renderer.write_text('''import de.agentcodi.core.TerminalOutputBuffer;
import de.agentcodi.core.PackageDiagnosticsCommand;
import java.io.DataInputStream;
import java.io.EOFException;
public final class RenderTerminal {
  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("package-diagnostics")) {
      System.out.print(PackageDiagnosticsCommand.create());
      return;
    }
    TerminalOutputBuffer buffer = new TerminalOutputBuffer();
    DataInputStream input = new DataInputStream(System.in);
    while (true) {
      int event;
      try { event = input.readInt(); } catch (EOFException end) { break; }
      if (event == 2) {
        buffer.resize(input.readInt(), input.readInt());
      } else {
        int length = input.readInt();
        // Split every UTF-8/control sequence across output notifications.
        for (int i = 0; i < length; i++) buffer.append(new byte[] {input.readByte()});
      }
    }
    System.out.print(buffer.snapshot());
  }
}
''')
        core = PROJECT / "modules/core/src/main/java/de/agentcodi/core"
        subprocess.run([
            "javac", "-encoding", "UTF-8", "-d", str(cls.build), str(renderer),
            str(core / "TerminalOutputBuffer.java"), str(core / "CrashReportFormatter.java"),
            str(core / "BuildIdentity.java"), str(core / "PackageDiagnosticsCommand.java"),
        ], check=True)
        cls.diagnostics_command = subprocess.run([
            "java", "-cp", str(cls.build), "RenderTerminal", "package-diagnostics",
        ], stdout=subprocess.PIPE, check=True).stdout
        cls.environment = dict(os.environ, AGENTCODI_TEST_MKSH=cls.mksh,
                               PS1=PROMPT, TERM="xterm", ENV="/dev/null",
                               HISTFILE="/dev/null", LC_ALL="C.UTF-8")

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(dir=self.build)
        subprocess.run(["git", "init", "--quiet", self.workspace.name], check=True)
        self.raw = bytearray()
        self.events = bytearray(struct.pack(">iii", 2, 24, 42))
        self.reference = pyte.HistoryScreen(42, 24, history=4096)
        self.stream = pyte.ByteStream(self.reference)
        self.pid, self.master = pty.fork()
        if self.pid == 0:
            self.resize(42)
            os.chdir(self.workspace.name)
            os.execve(str(self.binary), [str(self.binary), "--interactive"], self.environment)
        self.read_until(PROMPT.encode())
        # mksh learns its initial width after the first command.
        self.send(b":\n")

    def tearDown(self):
        os.kill(self.pid, signal.SIGKILL)
        os.close(self.master)
        os.waitpid(self.pid, 0)
        self.workspace.cleanup()

    def resize(self, columns):
        descriptor = 0 if self.pid == 0 else self.master
        fcntl.ioctl(descriptor, termios.TIOCSWINSZ, struct.pack("HHHH", 24, columns, 0, 0))
        if self.pid != 0:
            self.events.extend(struct.pack(">iii", 2, 24, columns))
            # Configure only the viewport: pyte.resize() destructively drops right cells.
            # Keep its independent VT parser while retaining offscreen output on resize.
            self.reference.columns = columns
            self.reference.ensure_hbounds()

    def read_until(self, marker):
        received = bytearray()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if select.select([self.master], [], [], 0.05)[0]:
                received.extend(os.read(self.master, 65536))
                if marker in received:
                    self.raw.extend(received)
                    self.events.extend(struct.pack(">ii", 1, len(received)) + received)
                    self.stream.feed(bytes(received))
                    return
        self.fail("Shell did not produce the expected marker: " + repr(received))

    def send(self, command):
        os.write(self.master, command)
        self.read_until(PROMPT.encode())

    def rendered(self):
        return subprocess.run([
            "java", "-cp", str(self.build), "RenderTerminal",
        ], input=bytes(self.events), stdout=subprocess.PIPE, check=True).stdout.decode("utf-8")

    def assert_matches_reference(self):
        lines = []
        for row in self.reference.history.top:
            lines.append("".join(row.get(column, self.reference.default_char).data
                                 for column in range(max(row, default=-1) + 1)).rstrip())
        lines.extend(line.rstrip() for line in self.reference.display)
        self.assertEqual("\n".join(lines).rstrip(), self.rendered().rstrip())

    def test_editor_is_enabled(self):
        self.assertEqual(0, termios.tcgetattr(self.master)[3] & termios.ICANON,
                         "mksh must retain its interactive line editor")

    def test_git_status_redraw_matches_a_real_terminal(self):
        self.send(b"git status\n")
        self.assertIn(b"git statu\rit statu", self.raw)
        self.assert_matches_reference()
        self.assertIn("No commits yet\n", self.rendered())

    def test_git_commit_redraw_matches_a_real_terminal(self):
        # An empty repository cannot produce a commit.
        self.send(b'git commit -m "one"\n')
        self.assertIn(b"git commi\rit commi", self.raw)
        self.assert_matches_reference()
        result = subprocess.run(["git", "rev-parse", "--verify", "HEAD"],
                                cwd=self.workspace.name, capture_output=True)
        self.assertNotEqual(0, result.returncode)

    def test_long_commands_match_the_terminal_after_viewport_resizes(self):
        for columns in (20, 42, 80, 120):
            with self.subTest(columns=columns):
                self.resize(columns)
                text = "wrapped-command-" + "x" * 200 + "-€"
                command = "printf '%s\\n' '" + text + "'"
                self.send((command + "\n").encode("utf-8"))
                self.assert_matches_reference()
                self.assertIn(text, self.rendered().replace("\n", ""))

    def test_tab_completion_remains_available(self):
        (Path(self.workspace.name) / "completion-fixture-unique.txt").write_text(
            "completion-success\n")
        os.write(self.master, b"cat ./completion-fixture-un")
        self.read_until(b"-un")
        self.send(b"\t\n")
        self.assertIn("\ncompletion-success\n", self.rendered())
        self.assert_matches_reference()

    def test_previous_output_returns_after_narrowing_and_widening(self):
        text = "retained-output-1234567890"
        self.send(("printf '%s\\n' '" + text + "'\n").encode())
        self.assertIn("\n" + text + "\n", self.rendered())
        self.resize(10)
        self.assertNotIn(text, self.rendered())
        self.assert_matches_reference()
        self.resize(42)
        self.assertIn("\n" + text + "\n", self.rendered())
        self.assert_matches_reference()

    def test_history_navigation_remains_available(self):
        self.send(b"printf 'history-ok\\n'\n")
        previous_count = self.rendered().count("\nhistory-ok\n")
        self.send(b"\x1b[A\n")
        self.assertEqual(previous_count + 1, self.rendered().count("\nhistory-ok\n"))
        self.assert_matches_reference()

    def test_backspace_edits_pending_input(self):
        os.write(self.master, b"printf 'edit-x")
        self.read_until(b"x")
        self.send(b"\x7fok\\n'\n")
        self.assertIn("\nedit-ok\n", self.rendered())
        self.assert_matches_reference()

    def test_control_c_interrupts_pending_input(self):
        os.write(self.master, b"printf should-not-run")
        self.read_until(b"run")
        self.send(b"\x03")
        self.send(b"printf 'after-interrupt\\n'\n")
        self.assertNotIn("\nshould-not-run", self.rendered())
        self.assertIn("\nafter-interrupt\n", self.rendered())
        self.assert_matches_reference()

    def prepare_diagnostics(self):
        prefix = Path(self.workspace.name) / "prefix with 'quote"
        (prefix / "bin").mkdir(parents=True)
        (prefix / "var/lib/dpkg").mkdir(parents=True)
        query = shutil.which("dpkg-query")
        self.assertIsNotNone(query, "Package diagnostics require host dpkg-query")
        (prefix / "bin/dpkg-query").symlink_to(query)
        (prefix / "var/lib/dpkg/status").write_text(
            "Package: diagnostic-fixture\nStatus: hold reinstreq installed\n"
            "Architecture: arm64\nVersion: 1.2-3\n"
            "Maintainer: Test <fixture@example.invalid>\nDescription: fixture\n\n")
        # These live shell variables are deliberately not exported.
        self.send(("unset PREFIX; PREFIX=" + shlex.quote(str(prefix))
                   + "; PATH=$PREFIX/bin:$PATH\n").encode())
        return prefix

    def assert_diagnostics_report(self, prefix):
        text = self.rendered().replace("\n", "")
        self.assertIn("PREFIX=" + str(prefix), text)
        self.assertIn("dpkg-query=" + str(prefix / "bin/dpkg-query"), text)
        self.assertIn("diagnostic-fixture", text)
        self.assertIn("hold reinstreq installed", text)

    def test_package_diagnostics_handles_integer_shell_variables(self):
        prefix = self.prepare_diagnostics()
        self.send(b"typeset -i agentcodi_program=13 agentcodi_path=17\n")
        self.send(self.diagnostics_command)
        self.assert_diagnostics_report(prefix)
        self.send(b"echo caller-values=$agentcodi_program,$agentcodi_path\n")
        self.assertIn("caller-values=13,17", self.rendered().replace("\n", ""))

    def test_package_diagnostics_bypasses_aliases_and_preserves_them(self):
        prefix = self.prepare_diagnostics()
        self.send(b"alias printf='echo printf-alias'\n")
        self.send(b"alias command='echo command-alias'\n")
        self.send(self.diagnostics_command)
        self.assert_diagnostics_report(prefix)
        self.send(b"printf; command\n")
        self.assertIn("printf-alias\ncommand-alias\n", self.rendered())

    def test_package_diagnostics_bypasses_helper_functions_and_preserves_tools(self):
        prefix = self.prepare_diagnostics()
        self.send(b"printf() { echo printf-function; }; command() { echo command-function; }\n")
        self.send(b"node() { echo node-function; }\n")
        self.send(self.diagnostics_command)
        self.assert_diagnostics_report(prefix)
        self.assertIn("node=node", self.rendered().replace("\n", ""))
        self.send(b"printf; command; node\n")
        self.assertIn("printf-function\ncommand-function\nnode-function\n", self.rendered())

    def test_noninteractive_commands_retain_quoting_and_exit_status(self):
        result = subprocess.run([str(self.binary), "-c", "printf '%s' 'a b'; exit 7"],
                                env=self.environment, capture_output=True)
        self.assertEqual(7, result.returncode)
        self.assertEqual(b"a b", result.stdout)


if __name__ == "__main__":
    unittest.main()
