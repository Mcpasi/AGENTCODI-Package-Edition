package de.archterminal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

final class RootfsConfig {
    static final String DEFAULT_MIRRORLIST = "Server = https://ftp.halifax.rwth-aachen.de/archlinux-arm/$arch/$repo\n"
        + "Server = https://mirrors.dotsrc.org/archlinuxarm/$arch/$repo\n";
    static final String LEGACY_MIRROR = "Server = https://mirror.math.princeton.edu/pub/archlinuxarm/$arch/$repo";
    static final String DIRECTORY_MODES_MARKER = ".arch-terminal-directory-modes-v1";
    interface DirectoryRepair { void repair(String rootfs) throws IOException; }

    /** Maintenance failure must not prevent an already installed guest from starting. */
    static String upgradeForLaunch(File installed, DirectoryRepair repair) {
        try {
            upgrade(installed, repair);
            return null;
        } catch (IOException | RuntimeException error) {
            String detail = error.getMessage();
            if (detail == null || detail.isEmpty()) detail = error.getClass().getSimpleName();
            return "Optionale Rootfs-Aktualisierung verschoben: " + detail;
        }
    }

    /** Upgrade app defaults once without replacing a user's chosen mirror or shell profiles. */
    static void upgrade(File installed, DirectoryRepair repair) throws IOException {
        Path root = installed.toPath();
        Path marker = root.resolve(DIRECTORY_MODES_MARKER);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        Path mirror = root.resolve("etc/pacman.d/mirrorlist");
        if (Files.isRegularFile(mirror, LinkOption.NOFOLLOW_LINKS)) {
            List<String> servers = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(mirror, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (value.matches("Server\\s*=.*")) servers.add(value);
            }
            if (servers.size() == 1 && servers.get(0).equals(LEGACY_MIRROR))
                Files.write(mirror, DEFAULT_MIRRORLIST.getBytes(StandardCharsets.UTF_8));
        }
        repair.repair(installed.getAbsolutePath());
        Files.write(marker, new byte[0], java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    static void configure(File staging) throws IOException {
        Path root = staging.toPath();
        for (String name : new String[]{"usr/bin/bash", "usr/bin/pacman", "usr/bin/env"}) {
            Path binary = root.resolve(name);
            if (!Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Arch-Datei fehlt: " + name);
        }
        // No guest systemd or Linux capabilities are available under PRoot.
        // Keep package signatures; the downloader's filesystem/seccomp sandbox needs unavailable kernel privileges.
        Path config = root.resolve("etc/pacman.conf");
        List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        boolean options = false;
        boolean sandbox = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.equals("[options]")) options = true;
            else if (trimmed.startsWith("[")) options = false;
            if (options && trimmed.equals("CheckSpace")) lines.set(i, "# CheckSpace (PRoot has no mount table)");
            if (options && trimmed.equals("DisableSandbox")) sandbox = true;
        }
        String version = new String(Files.readAllBytes(root.resolve("var/lib/pacman/local/ALPM_DB_VERSION")), StandardCharsets.UTF_8).trim();
        if (version.isEmpty()) throw new IOException("Arch-Paketdatenbank fehlt");
        // DisableSandbox exists in pacman 7+. Detect the packaged man page/config before adding the option.
        boolean supportsSandbox = lines.stream().anyMatch(line -> line.contains("DisableSandbox") || line.contains("DownloadUser"));
        if (supportsSandbox && !sandbox) {
            int index = lines.indexOf("[options]");
            if (index >= 0) lines.add(index + 1, "DisableSandbox");
        }
        if (!lines.contains("DisableDownloadTimeout")) {
            int index = lines.indexOf("[options]");
            if (index >= 0) lines.add(index + 1, "DisableDownloadTimeout");
        }
        Files.write(config, lines, StandardCharsets.UTF_8);
        // Use HTTPS mirrors; package authentication remains required by pacman.conf.
        Path mirror = root.resolve("etc/pacman.d/mirrorlist");
        if (Files.isSymbolicLink(mirror)) Files.delete(mirror);
        Files.write(mirror, DEFAULT_MIRRORLIST.getBytes(StandardCharsets.UTF_8));
        Path bashrc = root.resolve("root/.bashrc");
        String welcome = "\n# Arch Terminal\nexport PS1='\\[\\e[1;36m\\]arch\\[\\e[0m\\]:\\w \\$ '\n"
            + "alias ll='ls -lah --color=auto'\n"
            + "printf '\\033[1;36mArch Linux ARM\\033[0m · aarch64 · PRoot\\n'\n"
            + "printf 'Pakete aktualisieren: pacman -Syu\\n'\n";
        if (Files.isSymbolicLink(bashrc)) Files.delete(bashrc);
        Files.write(bashrc, welcome.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        Path profile = root.resolve("root/.bash_profile");
        if (Files.isSymbolicLink(profile)) Files.delete(profile);
        String firstLogin = "# Arch Terminal: initialize shipped signing keys once, without a command deadline.\n"
            + "if [ ! -f /etc/pacman.d/gnupg/.arch-terminal-ready ]; then\n"
            + "  printf 'Arch-Paketschlüssel werden eingerichtet …\\n'\n"
            + "  if pacman-key --init && pacman-key --populate archlinuxarm; then\n"
            + "    touch /etc/pacman.d/gnupg/.arch-terminal-ready\n"
            + "  else\n"
            + "    printf 'Schlüssel noch nicht bereit. Erneut ausführen: pacman-key --init && pacman-key --populate archlinuxarm\\n'\n"
            + "  fi\n"
            + "fi\n[ -f ~/.bashrc ] && . ~/.bashrc\n";
        Files.write(profile, firstLogin.getBytes(StandardCharsets.UTF_8));
        // Fresh extractions already preserve directory modes and need no legacy repair.
        Files.write(root.resolve(DIRECTORY_MODES_MARKER), new byte[0], java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    private RootfsConfig() {}
}
