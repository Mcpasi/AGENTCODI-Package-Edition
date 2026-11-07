package de.archterminal;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class RootfsConfigTest {
    @Test public void signatureChecksRemainEnabledAndSandboxHasCompatibilitySetting() throws Exception {
        Path root = fixture();
        RootfsConfig.configure(root.toFile());
        String config = new String(Files.readAllBytes(root.resolve("etc/pacman.conf")), StandardCharsets.UTF_8);
        assertTrue(config.contains("SigLevel = Required DatabaseOptional"));
        assertTrue(config.contains("DisableSandbox"));
        assertTrue(config.contains("DisableDownloadTimeout"));
        assertFalse(config.contains("\nCheckSpace\n"));
        assertTrue(Files.isRegularFile(root.resolve("root/.bash_profile")));
        assertEquals(RootfsConfig.DEFAULT_MIRRORLIST, read(root.resolve("etc/pacman.d/mirrorlist")));
    }
    @Test public void missingPacmanNeverMarksTheRootfsReady() throws Exception {
        Path root = fixture();
        Files.delete(root.resolve("usr/bin/pacman"));
        try { RootfsConfig.configure(root.toFile()); fail("missing pacman accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("pacman")); }
        assertFalse(Files.exists(root.resolve(".arch-terminal-ready")));
    }
    @Test public void upgradeReplacesOnlyTheOldDefaultAndRepairsDirectoriesOnce() throws Exception {
        Path root = fixture();
        Path mirror = root.resolve("etc/pacman.d/mirrorlist");
        Files.write(mirror, ("# original default\n" + RootfsConfig.LEGACY_MIRROR + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("root/.bash_profile"), "custom profile\n".getBytes(StandardCharsets.UTF_8));
        int[] repairs = {0};
        RootfsConfig.upgrade(root.toFile(), path -> { assertEquals(root.toString(), path); repairs[0]++; });
        assertEquals(RootfsConfig.DEFAULT_MIRRORLIST, read(mirror));
        assertEquals("custom profile\n", read(root.resolve("root/.bash_profile")));
        RootfsConfig.upgrade(root.toFile(), path -> repairs[0]++);
        assertEquals(1, repairs[0]);
    }
    @Test public void upgradePreservesCustomMirrorsAndFallbacks() throws Exception {
        for (String content : new String[]{"Server = https://chosen.example/$arch/$repo\n",
                RootfsConfig.LEGACY_MIRROR + "\nServer = https://chosen.example/$arch/$repo\n"}) {
            Path root = fixture();
            Path mirror = root.resolve("etc/pacman.d/mirrorlist");
            Files.write(mirror, content.getBytes(StandardCharsets.UTF_8));
            RootfsConfig.upgrade(root.toFile(), path -> {});
            assertEquals(content, read(mirror));
        }
    }
    @Test public void upgradeDoesNotFollowACustomMirrorSymlink() throws Exception {
        Path root = fixture();
        Path target = Files.createTempFile("custom-mirror", ".txt");
        Files.write(target, (RootfsConfig.LEGACY_MIRROR + "\n").getBytes(StandardCharsets.UTF_8));
        Path mirror = root.resolve("etc/pacman.d/mirrorlist");
        Files.createSymbolicLink(mirror, target);
        RootfsConfig.upgrade(root.toFile(), path -> {});
        assertTrue(Files.isSymbolicLink(mirror));
        assertEquals(RootfsConfig.LEGACY_MIRROR + "\n", read(target));
        Files.delete(target);
    }
    @Test public void failedRepairCanBeRetriedAndNeverMarksSuccess() throws Exception {
        Path root = fixture();
        try {
            RootfsConfig.upgrade(root.toFile(), path -> { throw new java.io.IOException("repair failed"); });
            fail("repair failure ignored");
        } catch (java.io.IOException expected) { assertEquals("repair failed", expected.getMessage()); }
        assertFalse(Files.exists(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER)));
        RootfsConfig.upgrade(root.toFile(), path -> {});
        assertTrue(Files.exists(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER)));
    }
    @Test public void freshInstallDoesNotNeedLegacyRepairs() throws Exception {
        Path root = fixture();
        RootfsConfig.configure(root.toFile());
        RootfsConfig.upgrade(root.toFile(), path -> fail("fresh rootfs was repaired"));
    }
    @Test public void checkedUpgradeFailureDoesNotBlockLaunchAndCanBeRetried() throws Exception {
        Path root = fixture();
        String warning = RootfsConfig.upgradeForLaunch(root.toFile(), path -> { throw new java.io.IOException("repair denied"); });
        assertTrue(warning.contains("repair denied"));
        assertFalse(Files.exists(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER)));
        assertNull(RootfsConfig.upgradeForLaunch(root.toFile(), path -> {}));
        assertTrue(Files.exists(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER)));
    }
    @Test public void nativeUpgradeFailureDoesNotBlockLaunchOrChangeCustomMirror() throws Exception {
        Path root = fixture();
        Path mirror = root.resolve("etc/pacman.d/mirrorlist");
        String chosen = "Server = https://chosen.example/$arch/$repo\n";
        Files.write(mirror, chosen.getBytes(StandardCharsets.UTF_8));
        String warning = RootfsConfig.upgradeForLaunch(root.toFile(), path -> { throw new IllegalStateException("Invalid package mtree"); });
        assertTrue(warning.contains("Invalid package mtree"));
        assertEquals(chosen, read(mirror));
        assertFalse(Files.exists(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER)));
    }
    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private Path fixture() throws Exception {
        Path root = Files.createTempDirectory("arch-config-test");
        for (String name : new String[]{"usr/bin", "etc/pacman.d", "root", "var/lib/pacman/local"}) Files.createDirectories(root.resolve(name));
        for (String name : new String[]{"bash", "pacman", "env"}) Files.write(root.resolve("usr/bin/" + name), new byte[]{1});
        Files.write(root.resolve("etc/pacman.conf"), "[options]\nSigLevel = Required DatabaseOptional\n#DownloadUser = alpm\nCheckSpace\n[core]\nInclude = /etc/pacman.d/mirrorlist\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("var/lib/pacman/local/ALPM_DB_VERSION"), "9".getBytes(StandardCharsets.UTF_8));
        return root;
    }
}
