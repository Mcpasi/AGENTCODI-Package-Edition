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
                RootfsConfig.LEGACY_MIRROR + "\nServer = https://chosen.example/$arch/$repo\n",
                RootfsConfig.PREVIOUS_MIRRORLIST + "Server = https://chosen.example/$arch/$repo\n",
                "Include = /etc/pacman.d/custom\n" + RootfsConfig.LEGACY_MIRROR + "\n"}) {
            Path root = fixture();
            Path mirror = root.resolve("etc/pacman.d/mirrorlist");
            Files.write(mirror, content.getBytes(StandardCharsets.UTF_8));
            RootfsConfig.upgrade(root.toFile(), path -> {});
            assertEquals(content, read(mirror));
        }
    }
    @Test public void previousDefaultMigratesEvenAfterDirectoryRepairHasCompleted() throws Exception {
        for (String previous : new String[]{RootfsConfig.PREVIOUS_MIRRORLIST, RootfsConfig.LEGACY_MIRROR + "\n"}) {
            Path root = fixture();
            Path mirror = root.resolve("etc/pacman.d/mirrorlist");
            Files.write(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER), new byte[0]);
            Files.write(mirror, ("# previous app default\n\n" + previous).getBytes(StandardCharsets.UTF_8));
            RootfsConfig.upgrade(root.toFile(), path -> fail("directory repair repeated"));
            assertEquals(RootfsConfig.DEFAULT_MIRRORLIST, read(mirror));
            assertTrue(Files.isRegularFile(root.resolve(RootfsConfig.MIRRORS_MARKER)));
            // A later manual choice must survive all subsequent launches.
            Files.write(mirror, previous.getBytes(StandardCharsets.UTF_8));
            RootfsConfig.upgrade(root.toFile(), path -> fail("directory repair repeated"));
            assertEquals(previous, read(mirror));
        }
    }
    @Test public void defaultMigrationAcceptsWhitespaceAndKeepsPackageConfiguration() throws Exception {
        Path root = fixture();
        Path mirror = root.resolve("etc/pacman.d/mirrorlist");
        String config = read(root.resolve("etc/pacman.conf"));
        Files.write(mirror, RootfsConfig.PREVIOUS_MIRRORLIST.replace("Server = ", "  Server\t=\t").getBytes(StandardCharsets.UTF_8));
        RootfsConfig.upgrade(root.toFile(), path -> {});
        assertEquals(RootfsConfig.DEFAULT_MIRRORLIST, read(mirror));
        assertEquals(config, read(root.resolve("etc/pacman.conf")));
        assertTrue(RootfsConfig.DEFAULT_MIRRORLIST.startsWith("Server = https://ca.us.mirror.archlinuxarm.org/"));
    }
    @Test public void oldManagedShellMessagesAreLocalizedWithoutOverwritingCustomizations() throws Exception {
        Path root = fixture();
        Path bashrc = root.resolve("root/.bashrc");
        Path profile = root.resolve("root/.bash_profile");
        Files.write(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER), new byte[0]);
        Files.write(bashrc, ("export CUSTOM=preserved\n# custom alias\n"
            + "printf 'Pakete aktualisieren: pacman -Syu\\n'\n").getBytes(StandardCharsets.UTF_8));
        Files.write(profile, ("# custom login\n"
            + "printf 'Arch-Paketschlüssel werden eingerichtet …\\n'\n"
            + "printf 'Schlüssel noch nicht bereit. Erneut ausführen: pacman-key --init && pacman-key --populate archlinuxarm\\n'\n")
            .getBytes(StandardCharsets.UTF_8));
        RootfsConfig.upgrade(root.toFile(), path -> fail("directory repair repeated"));
        String migrated = read(bashrc);
        String migratedProfile = read(profile);
        assertTrue(migrated.startsWith("export CUSTOM=preserved\n# custom alias\n"));
        assertTrue(migratedProfile.startsWith("# custom login\n"));
        assertTrue(migratedProfile.contains("Initializing Arch package signing keys"));
        assertTrue(migratedProfile.contains("Keys are not ready. Retry:"));
        assertShellLanguage(bashrc);
        RootfsConfig.upgrade(root.toFile(), path -> fail("directory repair repeated"));
        assertEquals(migrated, read(bashrc));
        assertEquals(migratedProfile, read(profile));
    }
    @Test public void freshProfilesAreValidBashAndUseTheSelectedLanguage() throws Exception {
        Path root = fixture();
        RootfsConfig.configure(root.toFile());
        for (String name : new String[]{"root/.bashrc", "root/.bash_profile"})
            assertEquals("Invalid shell profile: " + name, 0, new ProcessBuilder("/bin/bash", "-n", root.resolve(name).toString()).start().waitFor());
        assertShellLanguage(root.resolve("root/.bashrc"));
    }
    @Test public void shellProfileSymlinksRemainUnchangedDuringUpgrade() throws Exception {
        Path root = fixture();
        Path target = root.resolve("custom-profile");
        String original = "printf 'Pakete aktualisieren: pacman -Syu\\n'\n";
        Files.write(target, original.getBytes(StandardCharsets.UTF_8));
        for (String name : new String[]{"root/.bashrc", "root/.bash_profile"}) Files.createSymbolicLink(root.resolve(name), target);
        RootfsConfig.upgrade(root.toFile(), path -> {});
        assertEquals(original, read(target));
        assertTrue(Files.isSymbolicLink(root.resolve("root/.bashrc")));
        assertTrue(Files.isSymbolicLink(root.resolve("root/.bash_profile")));
    }
    @Test public void optionalUpgradeWarningUsesEnglishWhenSelected() throws Exception {
        Path root = fixture();
        String warning = RootfsConfig.upgradeForLaunch(root.toFile(), path -> { throw new java.io.IOException("repair denied"); }, TerminalLanguage.ENGLISH);
        assertEquals("Optional rootfs update deferred: repair denied", warning);
    }
    private void assertShellLanguage(Path bashrc) throws Exception {
        for (TerminalLanguage language : TerminalLanguage.values()) {
            ProcessBuilder builder = new ProcessBuilder("/bin/bash", "--noprofile", "--norc", bashrc.toString()).redirectErrorStream(true);
            builder.environment().put("ARCH_TERMINAL_LANGUAGE", language.code);
            Process process = builder.start();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = process.getInputStream().read(buffer)) != -1) output.write(buffer, 0, count);
            assertEquals(0, process.waitFor());
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(text, text.contains("pacman -Syyu"));
            assertEquals(text, language == TerminalLanguage.ENGLISH, text.contains("Refresh package lists and update packages:"));
            assertEquals(text, language == TerminalLanguage.GERMAN, text.contains("Paketlisten erneuern und Pakete aktualisieren:"));
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
