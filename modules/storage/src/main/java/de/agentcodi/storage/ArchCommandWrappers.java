package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.LinkedHashSet;
import java.util.Set;

/** Argument-preserving host launchers for programs installed inside Arch. */
public final class ArchCommandWrappers {
    public static void prepare(File state, File appFiles, File bridge,
        boolean compatible, String[] installed) throws IOException {
        File bin = new File(state, "arch-bin");
        if (Files.isSymbolicLink(bin.toPath())) throw new IOException("Invalid Arch command directory");
        Files.createDirectories(bin.toPath());
        Set<String> commands = new LinkedHashSet<String>();
        java.util.Collections.addAll(commands, "bash", "sh", "git", "rg", "node", "npm", "npx",
            "python", "python3", "pip", "pip3", "uv", "uvx");
        if (installed != null) java.util.Collections.addAll(commands, installed);
        for (String command : commands) {
            if (!command.matches("[A-Za-z0-9_][A-Za-z0-9_+.-]*")) continue;
            File wrapper = new File(bin, command);
            if (Files.isSymbolicLink(wrapper.toPath()) || (wrapper.exists()
                && !Files.isRegularFile(wrapper.toPath(), LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Invalid Arch command wrapper");
            }
            String mode = "bash".equals(command) || "sh".equals(command) ? "" : "--command " + quote(command) + " ";
            String script = "#!/system/bin/sh\nexport AGENTCODI_ARCH_FILES=" + quote(appFiles.getCanonicalPath()) + "\n"
                + (compatible ? "export PROOT_NO_SECCOMP=1\n" : "unset PROOT_NO_SECCOMP\n")
                + "exec " + quote(bridge.getCanonicalPath()) + " " + mode + "\"$@\"\n";
            Files.write(wrapper.toPath(), script.getBytes(StandardCharsets.UTF_8));
            if (!wrapper.setReadable(false, false) || !wrapper.setWritable(false, false)
                || !wrapper.setExecutable(false, false) || !wrapper.setReadable(true, true)
                || !wrapper.setWritable(true, true) || !wrapper.setExecutable(true, true)) {
                throw new IOException("Cannot secure Arch command wrapper");
            }
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private ArchCommandWrappers() {}
}
