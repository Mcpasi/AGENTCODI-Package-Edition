#!/usr/bin/env python3
"""Replay all eight reproduced long-work failures on current or baseline sources.

With --baseline, only the three affected production classes come from that
commit; the regression fixtures stay identical. No checkout or ref is changed.
A failing replay exits 1, so the old implementation is expected to fail.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


CASES = [
    "de.agentcodi.core.LongRunningTerminalTest#survivesFormerServerDeadline",
    "de.agentcodi.core.LongRunningTerminalTest#waitsForExitWithoutClientDeadline",
    "de.agentcodi.core.LongRunningTerminalTest#streamsBeyondFormerCaptureLimits",
    "de.agentcodi.core.LongRunningTerminalTest#acceptsCoalescedServerOutput",
    "de.agentcodi.tests.CodexSessionControllerTest#givesHumanRequestsTimeDuringLongWork",
    "de.agentcodi.tests.CodexSessionControllerTest#honorsLongServerUserInputDeadline",
    "de.agentcodi.tests.CodexSessionControllerTest#releasesResolvedInteractiveTimers",
    "de.agentcodi.tests.CodexSessionControllerTest#keepsReusedRequestIdsSafeFromAlreadyRunningTimers",
]
RUNNER = """
import java.lang.reflect.*;
public final class LongWorkReplay {
    public static void main(String[] args) throws Exception {
        int failures = 0;
        for (String test : args) {
            int split = test.indexOf('#');
            Method method = Class.forName(test.substring(0, split)).getDeclaredMethod(test.substring(split + 1));
            method.setAccessible(true);
            try {
                method.invoke(null);
                System.out.println("PASS " + test);
            } catch (InvocationTargetException error) {
                failures++;
                System.out.println("FAIL " + test + ": " + error.getCause());
            }
        }
        System.out.println("Failures: " + failures + "/" + args.length);
        System.exit(failures == 0 ? 0 : 1);
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", help="Commit supplying the original three production classes")
    arguments = parser.parse_args()
    project = Path(__file__).resolve().parents[2]
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not javac or not java:
        parser.error("A JDK providing java and javac is required")
    baseline = None
    if arguments.baseline:
        baseline = subprocess.check_output(["git", "rev-parse", "--verify", arguments.baseline + "^{commit}"],
            cwd=project, text=True).strip()
    with tempfile.TemporaryDirectory(prefix="agentcodi-long-work-replay-") as temporary:
        build = Path(temporary)
        classes = build / "classes"
        classes.mkdir()
        runner = build / "LongWorkReplay.java"
        runner.write_text(RUNNER, encoding="utf-8")
        replacements = {}
        if baseline:
            for name in ("CodexTerminalSession", "CodexAppServerClient", "CodexSessionController"):
                relative = "modules/core/src/main/java/de/agentcodi/core/" + name + ".java"
                source = build / (name + ".java")
                source.write_bytes(subprocess.check_output(["git", "show", baseline + ":" + relative], cwd=project))
                replacements[project / relative] = source
        sources = []
        for entry in (project / ".github/ci/java-sources.txt").read_text().splitlines():
            if entry.strip() and not entry.startswith("#"):
                path = project / entry
                sources.extend(sorted(path.rglob("*.java")) if path.is_dir() else [path])
        source_list = build / "sources.txt"
        source_list.write_text("\n".join(str(replacements.get(path, path)) for path in sources)
            + "\n" + str(runner) + "\n", encoding="utf-8")
        subprocess.run([javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
            "-d", str(classes), "@" + str(source_list)], cwd=project, check=True)
        return subprocess.run([java, "-cp", str(classes), "LongWorkReplay", *CASES], cwd=project).returncode


if __name__ == "__main__":
    raise SystemExit(main())
