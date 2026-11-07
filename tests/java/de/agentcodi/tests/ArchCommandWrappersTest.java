package de.agentcodi.tests;

import de.agentcodi.storage.ArchCommandWrappers;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ArchCommandWrappersTest {
    public static int run() throws Exception {
        Path root = Files.createTempDirectory("agentcodi-arch-quotes-");
        Path files = Files.createDirectories(root.resolve("files with ' quotes $()"));
        Path state = Files.createDirectory(root.resolve("state"));
        Path bridge = root.resolve("bridge with ' quotes");
        Files.write(bridge, ("#!/bin/sh\nprintf '%s\\n' \"$AGENTCODI_ARCH_FILES\" "
            + "\"${PROOT_NO_SECCOMP-unset}\" \"$@\"\n").getBytes(StandardCharsets.UTF_8));
        bridge.toFile().setExecutable(true, true);
        try {
            ArchCommandWrappers.prepare(state.toFile(), files.toFile(), bridge.toFile(),
                true, new String[] {"custom-tool", "../escape", "..", "bad name"});
            Process process = new ProcessBuilder("sh", state.resolve("arch-bin/custom-tool").toString(),
                "arg with spaces", "$(not-a-command)", "'").start();
            String output = read(process);
            TestSupport.assertEquals(0, process.waitFor(), "Arch command wrapper exit");
            TestSupport.assertEquals(files.toFile().getCanonicalPath()
                + "\n1\n--command\ncustom-tool\narg with spaces\n$(not-a-command)\n'\n",
                output, "shell quoting preserves paths and arguments");
            ArchCommandWrappers.prepare(state.toFile(), files.toFile(), bridge.toFile(), false, null);
            process = new ProcessBuilder("sh", state.resolve("arch-bin/bash").toString(), "-c", "echo $HOME").start();
            TestSupport.assertEquals(files.toFile().getCanonicalPath() + "\nunset\n-c\necho $HOME\n",
                read(process), "Bash launcher preserves shell flags and compatibility setting");
            TestSupport.assertEquals(0, process.waitFor(), "Bash wrapper exit");
            TestSupport.assertFalse(Files.exists(state.resolve("escape")), "invalid command names skipped");
            Path wrapper = state.resolve("arch-bin/node");
            Files.delete(wrapper);
            Files.createSymbolicLink(wrapper, bridge);
            try {
                ArchCommandWrappers.prepare(state.toFile(), files.toFile(), bridge.toFile(), false, null);
                throw new AssertionError("linked wrapper accepted");
            } catch (java.io.IOException expected) {
                TestSupport.assertTrue(Files.isSymbolicLink(wrapper), "existing links remain untouched");
            }
            return 4;
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
    }

    private static String read(Process process) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = process.getInputStream().read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
