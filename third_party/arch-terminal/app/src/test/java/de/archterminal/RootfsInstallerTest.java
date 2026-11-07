package de.archterminal;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.Assert.*;

public class RootfsInstallerTest {
    @Test public void failedStagingWithReadOnlyDirectoriesCanBeRemovedWithoutFollowingSymlinks() throws Exception {
        Path root = Files.createTempDirectory("arch-staging-test");
        Path outside = Files.createTempDirectory("arch-outside-test");
        try {
            Path readOnly = Files.createDirectory(root.resolve("readonly"));
            Files.write(readOnly.resolve("file"), new byte[]{1});
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
            Files.write(outside.resolve("keep"), new byte[]{2});
            Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("r-xr-xr-x"));
            Files.createSymbolicLink(root.resolve("outside"), outside);
            RootfsInstaller.deleteTree(root);
            assertFalse(Files.exists(root));
            assertArrayEquals(new byte[]{2}, Files.readAllBytes(outside.resolve("keep")));
            assertEquals(PosixFilePermissions.fromString("r-xr-xr-x"), Files.getPosixFilePermissions(outside));
        } finally {
            RootfsInstaller.deleteTree(root);
            RootfsInstaller.deleteTree(outside);
        }
    }
}
