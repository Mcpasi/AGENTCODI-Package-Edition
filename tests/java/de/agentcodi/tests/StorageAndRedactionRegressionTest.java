package de.agentcodi.tests;

import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.storage.ArchCommandWrappers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/** Reports both reproductions even when the first one fails in the host suite. */
public final class StorageAndRedactionRegressionTest {
    private StorageAndRedactionRegressionTest() {
    }

    public static int run() throws Exception {
        AssertionError failures = new AssertionError("Storage and redaction regressions failed");
        runCase("archWrapperMustNotOverwriteHardLinkTarget", new TestSupport.ThrowingRunnable() {
            @Override public void run() throws Exception {
                archWrapperMustNotOverwriteHardLinkTarget();
            }
        }, failures);
        runCase("quotedPasswordsMustBeRedactedCompletely", new TestSupport.ThrowingRunnable() {
            @Override public void run() {
                quotedPasswordsMustBeRedactedCompletely();
            }
        }, failures);
        if (failures.getSuppressed().length != 0) {
            throw failures;
        }
        return 2;
    }

    private static void archWrapperMustNotOverwriteHardLinkTarget() throws Exception {
        Path root = Files.createTempDirectory("agentcodi-wrapper-hardlink-");
        try {
            Path appFiles = Files.createDirectory(root.resolve("files"));
            Path state = Files.createDirectory(root.resolve("state"));
            Path bin = Files.createDirectory(state.resolve("arch-bin"));
            Path bridge = root.resolve("bridge");
            Files.write(bridge, "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));

            Path original = root.resolve("user-document.txt");
            String originalText = "User document that runtime preparation must preserve.\n";
            Files.write(original, originalText.getBytes(StandardCharsets.UTF_8));
            Set<PosixFilePermission> originalPermissions =
                PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(original, originalPermissions);
            Path wrapper = Files.createLink(bin.resolve("bash"), original);
            TestSupport.assertTrue(Files.isSameFile(original, wrapper),
                "fixture is a real hard link to a user document");

            IOException rejection = null;
            try {
                ArchCommandWrappers.prepare(state.toFile(), appFiles.toFile(), bridge.toFile(),
                    false, null);
            } catch (IOException expected) {
                rejection = expected;
            }

            TestSupport.assertEquals(originalText,
                new String(Files.readAllBytes(original), StandardCharsets.UTF_8),
                "preparing an Arch launcher must not overwrite its hard-link target");
            TestSupport.assertEquals(originalPermissions, Files.getPosixFilePermissions(original),
                "preparing an Arch launcher must not chmod its hard-link target");
            TestSupport.assertTrue(rejection != null,
                "an existing hard-linked Arch launcher must be rejected");
            TestSupport.assertTrue(Files.isSameFile(original, wrapper),
                "rejecting a linked launcher leaves the existing entry untouched");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void quotedPasswordsMustBeRedactedCompletely() {
        // Every value is a synthetic fixture. Spaces and punctuation are valid password characters.
        String password = "fixture-first fixture-tail,comma;semicolon&and}brace";
        String[] inputs = {
            "before password=\"" + password + "\" after",
            "before password='" + password + "' after",
            "{\"password\":\"" + password + "\",\"operation\":\"connect\"}"
        };
        String[] expected = {
            "before password=\"<redacted>\" after",
            "before password='<redacted>' after",
            "{\"password\":\"<redacted>\",\"operation\":\"connect\"}"
        };
        for (int index = 0; index < inputs.length; index++) {
            TestSupport.assertEquals(expected[index], CrashReportFormatter.redactSecrets(inputs[index]),
                "a quoted password is masked through its closing quote while context remains");
        }
    }

    private static void runCase(
        String name, TestSupport.ThrowingRunnable test, AssertionError failures
    ) {
        try {
            test.run();
            System.out.println("PASS reproduction: " + name);
        } catch (Exception | AssertionError failure) {
            System.out.println("FAIL reproduction: " + name);
            failures.addSuppressed(new AssertionError(name, failure));
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }
}
