#!/usr/bin/env python3
"""Run the packaged shell and real output buffer against mksh in a narrow PTY."""

import fcntl
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import struct
import subprocess
import tempfile
import termios
import time
import unittest


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
public final class RenderTerminal {
  public static void main(String[] args) throws Exception {
    TerminalOutputBuffer buffer = new TerminalOutputBuffer();
    int value;
    // Deliberately split every UTF-8/control sequence across notifications.
    while ((value = System.in.read()) >= 0) buffer.append(new byte[] {(byte) value});
    System.out.print(buffer.snapshot());
  }
}
''')
        core = PROJECT / "modules/core/src/main/java/de/agentcodi/core"
        subprocess.run([
            "javac", "-encoding", "UTF-8", "-d", str(cls.build), str(renderer),
            str(core / "TerminalOutputBuffer.java"), str(core / "CrashReportFormatter.java"),
            str(core / "BuildIdentity.java"),
        ], check=True)
        cls.environment = dict(os.environ, AGENTCODI_TEST_MKSH=cls.mksh,
                               PS1=PROMPT, TERM="xterm", ENV="/dev/null",
                               HISTFILE="/dev/null", LC_ALL="C.UTF-8")

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(dir=self.build)
        subprocess.run(["git", "init", "--quiet", self.workspace.name], check=True)
        self.pid, self.master = pty.fork()
        if self.pid == 0:
            self.resize(42)
            os.chdir(self.workspace.name)
            os.execve(str(self.binary), [str(self.binary), "--interactive"], self.environment)
        self.raw = bytearray()
        self.read_until(PROMPT.encode())
        # mksh learns its initial width after the first command.
        self.send(b":\n")
        self.raw.clear()
        self.raw.extend(PROMPT.encode())

    def tearDown(self):
        os.kill(self.pid, signal.SIGKILL)
        os.close(self.master)
        os.waitpid(self.pid, 0)
        self.workspace.cleanup()

    def resize(self, columns):
        descriptor = 0 if self.pid == 0 else self.master
        fcntl.ioctl(descriptor, termios.TIOCSWINSZ, struct.pack("HHHH", 24, columns, 0, 0))

    def read_until(self, marker):
        received = bytearray()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if select.select([self.master], [], [], 0.05)[0]:
                received.extend(os.read(self.master, 65536))
                if marker in received:
                    self.raw.extend(received)
                    return
        self.fail("Shell did not produce the expected marker: " + repr(received))

    def send(self, command):
        os.write(self.master, command)
        self.read_until(PROMPT.encode())

    def rendered(self):
        return subprocess.run([
            "java", "-cp", str(self.build), "RenderTerminal",
        ], input=bytes(self.raw), stdout=subprocess.PIPE, check=True).stdout.decode("utf-8")

    def test_git_status_keeps_the_complete_command_without_redraw_fragments(self):
        self.send(b"git status\n")
        output = self.rendered()
        self.assertIn(PROMPT + "git status\n", output)
        self.assertNotIn("\nit status\n", output)
        self.assertIn("No commits yet\n", output)

    def test_git_commit_keeps_the_complete_command_without_redraw_fragments(self):
        # An empty repository cannot produce a commit; only its echo is checked.
        self.send(b'git commit -m "one"\n')
        self.assertIn(PROMPT + 'git commit -m "one"\n', self.rendered())
        result = subprocess.run(["git", "rev-parse", "--verify", "HEAD"],
                                cwd=self.workspace.name, capture_output=True)
        self.assertNotEqual(0, result.returncode)

    def test_long_commands_remain_complete_after_viewport_resizes(self):
        for columns in (20, 42, 80, 120):
            with self.subTest(columns=columns):
                self.resize(columns)
                text = "wrapped-command-" + "x" * 200 + "-€"
                command = "printf '%s\\n' '" + text + "'"
                self.send((command + "\n").encode("utf-8"))
                self.assertIn(PROMPT + command + "\n" + text + "\n", self.rendered())

    def test_control_c_interrupts_pending_input(self):
        os.write(self.master, b"printf should-not-run")
        self.read_until(b"should-not-run")
        self.send(b"\x03")
        self.send(b"printf 'after-interrupt\\n'\n")
        output = self.rendered()
        self.assertNotIn("\nshould-not-run", output)
        self.assertIn("\nafter-interrupt\n", output)

    def test_noninteractive_commands_retain_quoting_and_exit_status(self):
        result = subprocess.run([str(self.binary), "-c", "printf '%s' 'a b'; exit 7"],
                                env=self.environment, capture_output=True)
        self.assertEqual(7, result.returncode)
        self.assertEqual(b"a b", result.stdout)


if __name__ == "__main__":
    unittest.main()
