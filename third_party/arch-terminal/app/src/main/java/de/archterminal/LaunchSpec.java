package de.archterminal;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Constructs argv directly: no shell quoting and no command deadline. */
final class LaunchSpec {
    final String executable;
    final String cwd;
    final String[] arguments;
    final String[] environment;

    LaunchSpec(File files, File nativeLibraries, boolean disableSeccomp, String... command) {
        File root = new File(files, "arch");
        File runtime = new File(files, "runtime");
        executable = new File(nativeLibraries, "libproot.so").getAbsolutePath();
        cwd = files.getAbsolutePath();
        List<String> argv = new ArrayList<>(Arrays.asList(executable, "--kill-on-exit", "--link2symlink", "-0",
            "-r", root.getAbsolutePath(), "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", new File(root, "tmp").getAbsolutePath() + ":/dev/shm",
            "-b", new File(runtime, "resolv.conf").getAbsolutePath() + ":/etc/resolv.conf",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin", "TERM=xterm-256color", "COLORTERM=truecolor",
            "LANG=C.UTF-8", "TMPDIR=/tmp"));
        if (command.length == 0) argv.addAll(Arrays.asList("/bin/bash", "--login"));
        else argv.addAll(Arrays.asList(command));
        arguments = argv.toArray(new String[0]);
        List<String> vars = new ArrayList<>(Arrays.asList("PATH=/system/bin", "HOME=" + files.getAbsolutePath(),
            "TMPDIR=" + new File(runtime, "tmp").getAbsolutePath(),
            "PROOT_TMP_DIR=" + new File(runtime, "tmp").getAbsolutePath(),
            "PROOT_LOADER=" + new File(nativeLibraries, "libproot-loader.so").getAbsolutePath(),
            "TERM=xterm-256color", "LANG=C.UTF-8"));
        if (disableSeccomp) vars.add("PROOT_NO_SECCOMP=1");
        environment = vars.toArray(new String[0]);
    }
}
