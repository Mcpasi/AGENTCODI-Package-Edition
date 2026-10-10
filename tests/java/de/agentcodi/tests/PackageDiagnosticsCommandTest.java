package de.agentcodi.tests;

import de.agentcodi.core.PackageDiagnosticsCommand;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class PackageDiagnosticsCommandTest {
    private PackageDiagnosticsCommandTest() {
    }

    public static int run() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reportsLivePathsAndPackageState();
            fixture.refreshesAfterUpdateAndRemoval();
            fixture.reportsUnavailableDatabaseAndQueryTool();
            fixture.reportsFailedQuery();
            fixture.rejectsUnsetPrefixWithoutQueryingHost();
        }
        try (Fixture fixture = new Fixture()) {
            fixture.ignoresDiagnosticHelperAliases();
            fixture.ignoresDiagnosticHelperFunctions();
            fixture.reportsUnavailableCommandsWithCustomizedHelpers();
            fixture.supportsReadOnlyScratchVariables();
            fixture.reportsUnexportedLiveEnvironment();
            fixture.preservesCallerState();
            fixture.reportsCompletePackageStatus();
        }
        return 12;
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root = Files.createTempDirectory("agentcodi-diagnostics-");
        private final Path prefix = root.resolve("prefix with 'quote");
        private final Path home = root.resolve("user home");
        private final Path database = prefix.resolve("var/lib/dpkg/status");
        private final Path query = prefix.resolve("bin/dpkg-query");

        private Fixture() throws Exception {
            Files.createDirectories(query.getParent());
            Files.createDirectories(database.getParent());
            Files.createDirectories(home.resolve(".local/bin"));
            Files.createSymbolicLink(query, findExecutable("dpkg-query"));
            Files.createSymbolicLink(prefix.resolve("bin/node"), findExecutable("sh"));
            writeStatus("24.18.0-1", true);
        }

        private void reportsLivePathsAndPackageState() throws Exception {
            String output = report(true, 0);
            TestSupport.assertContains(output, "PREFIX=" + prefix, "actual prefix");
            TestSupport.assertContains(output, "HOME=" + home, "separate user home");
            TestSupport.assertContains(output, "node=" + prefix.resolve("bin/node"),
                "resolved managed command");
            TestSupport.assertContains(output, "nodejs-lts\t24.18.0-1\tinstalled",
                "real dpkg version and status");
            TestSupport.assertContains(output, "removed-fixture\t1.0-1\tconfig-files",
                "removed package is not reported as installed");
            TestSupport.assertFalse(output.contains("private-account-data"),
                "unrelated environment and authentication data are omitted");
        }

        private void refreshesAfterUpdateAndRemoval() throws Exception {
            writeStatus("1:24.19.0-2", true);
            String output = report(true, 0);
            TestSupport.assertContains(output, "nodejs-lts\t1:24.19.0-2\tinstalled",
                "fresh query after package upgrade");
            TestSupport.assertFalse(output.contains("24.18.0-1"), "no cached APK version");
            Files.delete(prefix.resolve("bin/node"));
            Files.createSymbolicLink(home.resolve(".local/bin/node"), findExecutable("sh"));
            writeStatus("1:24.19.0-2", false);
            output = report(true, 0);
            TestSupport.assertContains(output, "node=" + home.resolve(".local/bin/node"),
                "legacy fallback resolved after managed command removal");
            TestSupport.assertFalse(output.contains("nodejs-lts\t"),
                "fallback command does not imply an installed managed package");
        }

        private void reportsUnavailableDatabaseAndQueryTool() throws Exception {
            Files.delete(database);
            TestSupport.assertContains(report(true, 0), "Package database is unavailable.",
                "missing database is explicit");
            writeStatus("24.19.0-2", true);
            Files.delete(query);
            TestSupport.assertContains(report(true, 0), "Package query tool is unavailable.",
                "missing managed query tool never falls back to host dpkg");
            Files.createSymbolicLink(query, findExecutable("dpkg-query"));
        }

        private void reportsFailedQuery() throws Exception {
            Files.write(database, "not-a-valid-dpkg-record\n".getBytes(StandardCharsets.UTF_8));
            TestSupport.assertContains(report(true, 1), "Package query failed.",
                "corrupt database is reported with failure status");
        }

        private void rejectsUnsetPrefixWithoutQueryingHost() throws Exception {
            TestSupport.assertContains(report(false, 0), "Package prefix is unavailable.",
                "unset prefix never queries the host database");
        }

        private void ignoresDiagnosticHelperAliases() throws Exception {
            String output = report(true, 0,
                "alias printf='echo diagnostic-alias-invoked'\n"
                    + "alias command='echo diagnostic-alias-invoked'\n", "");
            assertManagedReport(output);
            TestSupport.assertFalse(output.contains("diagnostic-alias-invoked"),
                "diagnostics bypass helper aliases");
        }

        private void ignoresDiagnosticHelperFunctions() throws Exception {
            String output = report(true, 0,
                "printf() { echo diagnostic-function-invoked; }\n"
                    + "command() { echo diagnostic-function-invoked; }\n", "");
            assertManagedReport(output);
            TestSupport.assertFalse(output.contains("diagnostic-function-invoked"),
                "read-only diagnostics do not execute user helper functions");
        }

        private void supportsReadOnlyScratchVariables() throws Exception {
            assertManagedReport(report(true, 0,
                "readonly agentcodi_program=caller-program agentcodi_path=caller-path\n",
                ""));
            assertManagedReport(report(true, 0,
                "readonly agentcodi_path=caller-path\n", ""));
        }

        private void reportsUnavailableCommandsWithCustomizedHelpers() throws Exception {
            String output = report(true, 0,
                "PATH=$PREFIX/bin\n"
                    + "printf() { echo diagnostic-helper-invoked; }\n"
                    + "command() { echo diagnostic-helper-invoked; }\n", "");
            assertManagedReport(output);
            TestSupport.assertContains(output, "apt=<unavailable>\n",
                "missing commands remain explicit when helpers are customized");
            TestSupport.assertFalse(output.contains("diagnostic-helper-invoked"),
                "missing-command fallback uses the shell builtin");
        }

        private void reportsUnexportedLiveEnvironment() throws Exception {
            String output = report(false, 0,
                "PREFIX=$HOME/../\"prefix with 'quote\"\n"
                    + "unset TMPDIR\nTMPDIR=$HOME/live-tmp\n"
                    + "node() { echo tool-function-must-not-run; }\n", "");
            TestSupport.assertContains(output, "TMPDIR=" + home + "/live-tmp",
                "non-exported shell variables are read in the current shell");
            TestSupport.assertContains(output, "node=node",
                "user tool functions remain visible to command resolution");
            TestSupport.assertFalse(output.contains("tool-function-must-not-run"),
                "resolving a user tool never executes it");
            TestSupport.assertContains(output, "nodejs-lts\t24.18.0-1\tinstalled",
                "non-exported prefix still queries the managed database");
        }

        private void preservesCallerState() throws Exception {
            String output = report(true, 0,
                "set -eu\n"
                    + "set -- caller-first 'caller second'\n"
                    + "agentcodi_program=caller-program\n"
                    + "agentcodi_path=caller-path\n"
                    + "printf() { echo caller-printf; }\n"
                    + "command() { echo caller-command; }\n"
                    + "alias printf='echo caller-printf-alias'\n"
                    + "alias command='echo caller-command-alias'\n",
                "if [ \"$#\" -eq 2 ] && [ \"$1\" = caller-first ]"
                    + " && [ \"$2\" = 'caller second' ]"
                    + " && [ \"$agentcodi_program\" = caller-program ]"
                    + " && [ \"$agentcodi_path\" = caller-path ];"
                    + " then echo caller-state-preserved; else exit 8; fi\n"
                    + "printf\ncommand\n"
                    + "\\printf\n\\command\n"
                    + "case $- in *e*u*|*u*e*) echo caller-options-preserved;;"
                    + " *) exit 9;; esac\n");
            TestSupport.assertContains(output, "\ncaller-state-preserved\n",
                "caller positional arguments and variables survive the subshell");
            TestSupport.assertContains(output, "\ncaller-command\n",
                "caller command function survives the subshell");
            TestSupport.assertContains(output, "caller-printf-alias",
                "caller printf alias survives the subshell");
            TestSupport.assertContains(output, "caller-command-alias",
                "caller command alias survives the subshell");
            TestSupport.assertContains(output, "\ncaller-printf\n",
                "caller printf function survives the subshell");
            TestSupport.assertContains(output, "caller-options-preserved",
                "caller errexit and nounset are unchanged");
        }

        private void reportsCompletePackageStatus() throws Exception {
            String status = "Package: damaged-fixture\nStatus: install reinstreq installed\n"
                + "Architecture: arm64\nVersion: 2.0-1\nDescription: damaged package\n\n"
                + "Package: held-fixture\nStatus: hold ok installed\n"
                + "Architecture: arm64\nVersion: 3.0-1\nDescription: held package\n\n";
            Files.write(database, status.getBytes(StandardCharsets.UTF_8));
            String output = report(true, 0);
            TestSupport.assertContains(output,
                "damaged-fixture\t2.0-1\tinstalled\tinstall reinstreq installed",
                "reinstallation-required flag is visible alongside the package state");
            TestSupport.assertContains(output,
                "held-fixture\t3.0-1\tinstalled\thold ok installed",
                "hold selection is visible alongside the package state");
        }

        private void assertManagedReport(String output) {
            TestSupport.assertContains(output, "PREFIX=" + prefix,
                "helper customization does not alter live paths");
            TestSupport.assertContains(output, "dpkg-query=" + query,
                "helper customization does not alter command resolution");
            TestSupport.assertContains(output, "nodejs-lts\t24.18.0-1\tinstalled",
                "helper customization does not prevent querying managed packages");
        }

        private String report(boolean hasPrefix, int expectedExit) throws Exception {
            return report(hasPrefix, expectedExit, "", "");
        }

        private String report(boolean hasPrefix, int expectedExit, String setup, String after)
            throws Exception {
            Path output = root.resolve("report.txt");
            ProcessBuilder builder = new ProcessBuilder(
                findExecutable("sh").toString(), "-c",
                setup + PackageDiagnosticsCommand.create() + after
            ).redirectErrorStream(true).redirectOutput(output.toFile());
            Map<String, String> environment = builder.environment();
            environment.put("PATH", prefix.resolve("bin") + File.pathSeparator
                + home.resolve(".local/bin") + File.pathSeparator + System.getenv("PATH"));
            environment.put("HOME", home.toString());
            environment.put("TMPDIR", root.toString());
            environment.put("LD_LIBRARY_PATH", prefix.resolve("lib").toString());
            environment.put("NPM_CONFIG_PREFIX", home.resolve(".local").toString());
            environment.put("XDG_CACHE_HOME", home.resolve(".cache").toString());
            environment.put("CODEX_HOME", "private-account-data");
            environment.put("UNRELATED_SECRET", "private-account-data");
            if (hasPrefix) {
                environment.put("PREFIX", prefix.toString());
            } else {
                environment.remove("PREFIX");
            }
            Process process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Package diagnostics timed out");
            }
            String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            TestSupport.assertEquals(Integer.valueOf(expectedExit),
                Integer.valueOf(process.exitValue()), "diagnostics exit status: " + text);
            return text;
        }

        private void writeStatus(String version, boolean installed) throws Exception {
            String status = (installed ? "Package: nodejs-lts\nStatus: install ok installed\n"
                + "Architecture: arm64\nVersion: " + version
                + "\nDescription: managed test package\n\n" : "")
                + "Package: removed-fixture\nStatus: deinstall ok config-files\n"
                + "Architecture: arm64\nVersion: 1.0-1\nDescription: removed package\n\n";
            Files.write(database, status.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws Exception {
            try (Stream<Path> paths = Files.walk(root)) {
                Path[] entries = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path entry : entries) {
                    Files.delete(entry);
                }
            }
        }
    }

    private static Path findExecutable(String name) {
        for (String entry : System.getenv("PATH").split(File.pathSeparator)) {
            Path path = new File(entry, name).toPath().toAbsolutePath();
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path;
            }
        }
        throw new AssertionError("Required test tool is unavailable: " + name);
    }
}
