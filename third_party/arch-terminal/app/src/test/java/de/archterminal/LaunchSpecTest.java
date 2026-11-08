package de.archterminal;

import org.junit.Test;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class LaunchSpecTest {
    @Test public void pathsWithSpacesAreSingleArguments() {
        LaunchSpec launch = new LaunchSpec(new File("/data/user/0/de.archterminal/files space"), new File("/data/app/native libs"), false);
        List<String> argv = Arrays.asList(launch.arguments);
        assertEquals("/data/app/native libs/libproot.so", launch.executable);
        assertEquals(launch.executable, argv.get(0));
        assertEquals("/data/user/0/de.archterminal/files space/arch", argv.get(argv.indexOf("-r") + 1));
        assertTrue(argv.contains("--kill-on-exit"));
        assertTrue(argv.contains("/bin/bash"));
        assertTrue(Arrays.asList(launch.environment).contains("PROOT_LOADER=/data/app/native libs/libproot-loader.so"));
    }
    @Test public void guestEnvironmentHasNoAndroidLibraryPathsOrDeadlines() {
        LaunchSpec launch = new LaunchSpec(new File("/private/files"), new File("/apk/lib"), false, "/usr/bin/pacman", "--version");
        List<String> argv = Arrays.asList(launch.arguments);
        assertTrue(argv.contains("-i"));
        assertEquals("--version", argv.get(argv.size() - 1));
        for (String arg : launch.arguments) assertFalse(arg.contains("timeout"));
        for (String variable : launch.environment) {
            assertFalse(variable.startsWith("LD_PRELOAD="));
            assertFalse(variable.startsWith("LD_LIBRARY_PATH="));
            assertFalse(variable.contains("com.termux"));
        }
    }
    @Test public void compatibilityModeIsExplicit() {
        LaunchSpec standard = new LaunchSpec(new File("/files"), new File("/lib"), false);
        LaunchSpec compatible = new LaunchSpec(new File("/files"), new File("/lib"), true);
        assertFalse(Arrays.asList(standard.environment).contains("PROOT_NO_SECCOMP=1"));
        assertTrue(Arrays.asList(compatible.environment).contains("PROOT_NO_SECCOMP=1"));
    }
    @Test public void englishGuestUsesUtf8WithoutRequiringGeneratedLocales() {
        LaunchSpec launch = new LaunchSpec(new File("/files"), new File("/lib"), false, TerminalLanguage.ENGLISH, "/bin/bash", "-c", "printf 'hello world'");
        List<String> argv = Arrays.asList(launch.arguments);
        assertTrue(argv.contains("LANG=C.UTF-8"));
        assertTrue(argv.contains("LC_ALL=C.UTF-8"));
        assertTrue(argv.contains("LANGUAGE=en"));
        assertTrue(argv.contains("ARCH_TERMINAL_LANGUAGE=en"));
        assertEquals("printf 'hello world'", argv.get(argv.size() - 1));
        assertFalse(Arrays.asList(launch.environment).contains("ARCH_TERMINAL_LANGUAGE=en"));
    }
    @Test public void defaultGuestKeepsGermanManagedMessagesAndUtf8() {
        LaunchSpec launch = new LaunchSpec(new File("/files"), new File("/lib"), false);
        assertTrue(Arrays.asList(launch.arguments).contains("ARCH_TERMINAL_LANGUAGE=de"));
        assertTrue(Arrays.asList(launch.arguments).contains("LANG=C.UTF-8"));
    }
}
