package de.archterminal;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageView;
import android.widget.TextView;
import com.termux.view.TerminalView;
import com.termux.terminal.TerminalSession;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Runs on real ARM64 Android. Every deadline here belongs only to the test harness. */
public final class TerminalInstrumentation extends Instrumentation {
    private Bundle arguments;
    private volatile Activity latestActivity;
    private final StringBuilder passed = new StringBuilder();
    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        super.callActivityOnCreate(activity, state);
        latestActivity = activity;
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        TerminalLanguage savedLanguage = AppLanguage.get(getTargetContext());
        try {
            // Legacy menu/setup regressions use German labels; keep the user's preference intact.
            AppLanguage.set(getTargetContext(), TerminalLanguage.GERMAN);
            if ("overlapping-close".equals(arguments.getString("sessionRegression"))) {
                testSessionStopActions(false, true);
            } else if ("true".equals(arguments.getString("languageRegression"))) {
                testLanguageSelection();
            } else if ("drain-close".equals(arguments.getString("sessionRegression"))) {
                testDeferredSessionOutput(true);
            } else if ("drain-restart".equals(arguments.getString("sessionRegression"))) {
                testDeferredSessionOutput(false);
            } else {
                testExtraction();
                testUpgradeFailureDoesNotBlockShell();
                testPty();
                testTail();
                testInterrupt();
                testDeferredSessionOutput(true);
                testDeferredSessionOutput(false);
                testSessionStopActions(false, false);
                testSessionStopActions(true, false);
                testSessionStopActions(false, true);
                if ("true".equals(arguments.getString("rootfs"))) testArch();
                testStartupLogo();
                testKeyboard();
                testMultipleSessions();
                testLanguageSelection();
            }
            result.putString("stream", "\nPASS\n" + passed);
            result.putBoolean("archterminal.success", true);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + error + "\nPassed:\n" + passed);
            result.putBoolean("archterminal.success", false);
        } finally {
            AppLanguage.set(getTargetContext(), savedLanguage);
        }
        finish(result.getBoolean("archterminal.success") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void testExtraction() throws Exception {
        Path test = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "arch-extract-test-");
        try {
            Path archive = test.resolve("rootfs.tar.gz");
            // The Android asset merger transparently expands files ending in .gz; .tgz keeps this fixture compressed.
            try (InputStream input = getContext().getAssets().open("extractor-hardlinks.tgz")) { Files.copy(input, archive); }
            Path root = Files.createDirectory(test.resolve("root"));
            Native.resetExtractionCancellation();
            Native.extract(archive.toString(), root.toString());
            byte[] expected = "#!/bin/sh\nprintf 'hardlink copy works\\n'\n".getBytes(StandardCharsets.UTF_8);
            long sourceInode = android.system.Os.stat(root.resolve("usr/bin/source").toString()).st_ino;
            for (String name : new String[]{"alias", "forward-alias"}) {
                Path copy = root.resolve("usr/bin/" + name);
                if (Files.isSymbolicLink(copy) || !Arrays.equals(expected, Files.readAllBytes(copy)) || !copy.toFile().canExecute())
                    throw new AssertionError("Archive hardlink copy lost contents or execute mode: " + name);
                if (android.system.Os.stat(copy.toString()).st_ino == sourceInode)
                    throw new AssertionError("Extractor created a real hardlink: " + name);
            }
            Files.delete(root.resolve("usr/bin/source"));
            if (!Arrays.equals(expected, Files.readAllBytes(root.resolve("usr/bin/alias"))))
                throw new AssertionError("Hardlink copy depends on original path");
            passed.append("Android rootfs extraction: hardlink copies, forward chain, executable modes\n");
        } finally { RootfsInstaller.deleteTree(test); }
    }
    private void testUpgradeFailureDoesNotBlockShell() throws Exception {
        Path files = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "arch-upgrade-test-");
        try {
            Path root = files.resolve("arch");
            Path packageDirectory = Files.createDirectories(root.resolve("var/lib/pacman/local/broken-1"));
            // Truncated gzip: the actual JNI repair must reject this optional package metadata.
            Files.write(packageDirectory.resolve("mtree"), new byte[]{0x1f, (byte) 0x8b, 0x08});
            Path mirror = root.resolve("etc/pacman.d/mirrorlist");
            Files.createDirectories(mirror.getParent());
            byte[] customMirror = "Server = https://chosen.example/$arch/$repo\n".getBytes(StandardCharsets.UTF_8);
            Files.write(mirror, customMirror);
            boolean rejected = false;
            try { Native.repairDirectoryModes(root.toString()); }
            catch (IllegalStateException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Invalid mtree fixture did not fail native repair");
            Context isolated = new ContextWrapper(getTargetContext()) {
                @Override public File getFilesDir() { return files.toFile(); }
            };
            // Reflection also runs this unchanged test APK against 0.1.3's void-returning method.
            Method prepare = RuntimeFiles.class.getDeclaredMethod("prepare", Context.class);
            prepare.setAccessible(true);
            try { prepare.invoke(null, isolated); }
            catch (java.lang.reflect.InvocationTargetException error) {
                throw new AssertionError("Optional rootfs repair blocked shell startup: " + error.getCause(), error.getCause());
            }
            if (!Files.isRegularFile(files.resolve("runtime/resolv.conf"))) throw new AssertionError("Runtime DNS file missing after failed repair");
            if (Files.exists(root.resolve(RootfsConfig.DIRECTORY_MODES_MARKER))) throw new AssertionError("Failed repair marked successful");
            if (!Arrays.equals(customMirror, Files.readAllBytes(mirror))) throw new AssertionError("Custom mirror changed");
            Probe probe = shell("printf 'UPGRADE_FAILURE_SHELL_OK\\n'");
            probe.awaitExit();
            if (!probe.text.contains("UPGRADE_FAILURE_SHELL_OK")) throw new AssertionError("Shell did not run after failed repair");
            passed.append("Optional upgrade failure: actual JNI, runtime preparation, working shell, preserved mirror and retry\n");
        } finally { RootfsInstaller.deleteTree(files); }
    }
    private final class Probe extends SessionCallbacks {
        final CountDownLatch finished = new CountDownLatch(1);
        volatile String text = "";
        volatile int exit = Integer.MIN_VALUE;
        TerminalSession session;
        SessionCallbacks delegate;
        @Override public String getProcessExitMessage(int status) {
            return delegate == null ? super.getProcessExitMessage(status) : delegate.getProcessExitMessage(status);
        }
        @Override public void onTextChanged(TerminalSession session) {
            text = session.getEmulator().getScreen().getTranscriptText();
            if (delegate != null) delegate.onTextChanged(session);
        }
        @Override public void onTitleChanged(TerminalSession session) { if (delegate != null) delegate.onTitleChanged(session); }
        @Override public void onColorsChanged(TerminalSession session) { if (delegate != null) delegate.onColorsChanged(session); }
        @Override public void onSessionFinished(TerminalSession session) {
            text = session.getEmulator().getScreen().getTranscriptText(); exit = session.getExitStatus();
            if (delegate != null) delegate.onSessionFinished(session);
            finished.countDown();
        }
        void awaitText(String expected) throws Exception {
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (!text.contains(expected)) {
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Missing output: " + expected + "\n" + text);
                Thread.sleep(20);
            }
        }
        void awaitExit() throws Exception {
            if (!finished.await(30, TimeUnit.SECONDS)) { runOnMainSync(session::finishIfRunning); throw new AssertionError("Device test did not finish\n" + text); }
            if (exit != 0) throw new AssertionError("Process exit " + exit + "\n" + text);
        }
    }
    private Probe shell(String command) {
        Probe probe = new Probe();
        runOnMainSync(() -> {
            probe.session = new TerminalSession("/system/bin/sh", getTargetContext().getFilesDir().getAbsolutePath(),
                new String[]{"sh", "-c", command}, new String[]{"PATH=/system/bin", "TERM=xterm-256color"}, 10000, probe);
            probe.session.updateSize(80, 24, 0, 0);
        });
        return probe;
    }
    private void testPty() throws Exception {
        Probe probe = shell("test -t 0 && test -t 1 && test -t 2 || exit 9; printf 'READY\\n'; read line; stty size; printf '<%s>DONE\\n' \"$line\"");
        probe.awaitText("READY");
        Thread.sleep(600);
        AtomicBoolean running = new AtomicBoolean();
        runOnMainSync(() -> { running.set(probe.session.isRunning()); probe.session.updateSize(101, 47, 0, 0); probe.session.write("Grüße\n"); });
        if (!running.get()) throw new AssertionError("Idle PTY was closed");
        probe.awaitExit();
        if (!probe.text.contains("47 101") || !probe.text.contains("<Grüße>DONE")) throw new AssertionError("PTY UTF-8/resize failure\n" + probe.text);
        passed.append("Android C++ PTY, TTY, resize, UTF-8, idle\n");
    }
    private void testTail() throws Exception {
        Probe probe = shell("head -c 262144 /dev/zero | tr '\\000' x; printf '\\r\\nFINAL_MARKER\\n'");
        probe.awaitExit();
        if (!probe.text.contains("FINAL_MARKER")) throw new AssertionError("Final output was lost on exit");
        passed.append("256 KiB output, exit drain\n");
    }
    private void testInterrupt() throws Exception {
        Probe probe = shell("trap 'echo INTERRUPTED; exit 0' INT; echo READY; while :; do sleep 1; done");
        probe.awaitText("READY");
        runOnMainSync(() -> probe.session.write("\003"));
        probe.awaitExit();
        if (!probe.text.contains("INTERRUPTED")) throw new AssertionError("Ctrl-C failed");
        passed.append("Ctrl-C delivery\n");
    }
    private void testStartupLogo() throws Exception {
        Probe probe = shell("exec /system/bin/sh");
        Activity activity = null;
        TerminalService owner = null;
        SessionManager<TerminalSession> previous = null;
        try {
            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            Activity current = activity;
            awaitUi(() -> field(current, "service") != null && current.hasWindowFocus(), "Logo Activity did not connect");
            owner = (TerminalService) field(current, "service");
            previous = manager(owner);
            TerminalService connected = owner;
            // Display setup without changing the service's session or any installed Arch.
            runOnMainSync(() -> {
                connected.listen(null);
                field(current, "service", null);
                field(current, "attached", null);
                render(current);
            });
            testSetupActions(current);
            ImageView logo = (ImageView) field(current, "startupLogo");
            ObjectAnimator pulse = (ObjectAnimator) field(current, "logoPulse");
            awaitUi(() -> logo.isShown() && logo.getWidth() > 0, "Startup logo was not visible");
            runOnMainSync(() -> {
                if (logo.getDrawable() == null || logo.isFocusable() || logo.isClickable()
                    || logo.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                    throw new AssertionError("Startup decoration can intercept input or accessibility focus");
                if (((View) field(current, "terminal")).isShown()) throw new AssertionError("Setup overlaps the terminal");
            });
            boolean animationsEnabled = ValueAnimator.areAnimatorsEnabled();
            if (animationsEnabled) awaitUi(() -> pulse.isRunning() && logo.getAlpha() < 0.9f, "Startup logo did not pulse");
            else awaitUi(() -> !pulse.isStarted() && logo.getAlpha() == 1f, "Disabled animations were not respected");
            if (!getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                throw new AssertionError("Android did not accept the global Home action");
            awaitUi(() -> !current.hasWindowFocus(), "Global Home action did not put the Activity in background");
            awaitUi(() -> !pulse.isStarted() && logo.getAlpha() == 1f, "Startup animation kept running in background");
            getTargetContext().startActivity(intent);
            awaitUi(() -> field(current, "service") != null && current.hasWindowFocus(), "Logo Activity did not resume");
            runOnMainSync(() -> {
                connected.listen(null);
                field(current, "service", null);
                field(current, "attached", null);
                render(current);
            });
            if (animationsEnabled) awaitUi(() -> pulse.isRunning() && logo.getAlpha() < 0.9f, "Startup animation did not resume");
            runOnMainSync(() -> {
                field(connected, "sessions", singleSession(probe.session));
                field(current, "service", connected);
                render(current);
            });
            TerminalView terminal = (TerminalView) field(current, "terminal");
            awaitUi(() -> terminal.isShown() && terminal.hasFocus() && !logo.isShown()
                && !pulse.isStarted() && logo.getAlpha() == 1f, "Startup logo interferes with the terminal");
            passed.append("Startup logo: visible pulse, decorative focus, background stop/resume, immediate terminal handoff\n");
        } finally {
            Activity current = activity;
            TerminalService connected = owner;
            SessionManager<TerminalSession> saved = previous;
            runOnMainSync(() -> {
                if (connected != null) field(connected, "sessions", saved);
                if (current != null) {
                    field(current, "service", connected);
                    field(current, "attached", null);
                    if (connected != null) render(current);
                    current.finish();
                }
                probe.session.finishIfRunning();
            });
        }
    }
    private void testKeyboard() throws Exception {
        Probe probe = shell("printf 'KEYBOARD_READY\\n'; exec /system/bin/sh");
        Activity activity = null;
        TerminalService owner = null;
        SessionManager<TerminalSession> previous = null;
        try {
            probe.awaitText("KEYBOARD_READY");
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity current = activity;
            awaitUi(() -> field(current, "service") != null && current.hasWindowFocus(), "Activity did not connect and gain window focus");
            owner = (TerminalService) field(current, "service");
            previous = manager(owner);
            TerminalService connected = owner;
            // Exercise the real Activity with a disposable Android shell; no Arch download is needed.
            runOnMainSync(() -> { field(connected, "sessions", singleSession(probe.session)); render(current); });
            TerminalView terminal = (TerminalView) field(current, "terminal");
            awaitUi(() -> terminal.isShown() && terminal.getWidth() > 0, "Terminal was not laid out");
            runOnMainSync(() -> {
                if (((View) field(current, "startupLogo")).isShown() || ((ObjectAnimator) field(current, "logoPulse")).isStarted())
                    throw new AssertionError("Startup logo is still active during command input");
            });
            setInTouchMode(true);
            runOnMainSync(() -> current.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(terminal.getWindowToken(), 0));
            awaitUi(() -> !keyboardVisible(terminal), "Keyboard did not close before tap test");
            tap(terminal);
            awaitUi(() -> keyboardVisible(terminal), "Tap did not open keyboard: focusable=" + terminal.isFocusable()
                + ", focusableInTouchMode=" + terminal.isFocusableInTouchMode());
            awaitUi(() -> terminal.hasFocus() && current.getSystemService(android.view.inputmethod.InputMethodManager.class).isActive(terminal),
                "Terminal did not become the active input editor");
            runOnMainSync(() -> {
                InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
                if (input == null || !input.commitText("printf 'IME_%s\\n' 'INPUT_OK'\n", 1))
                    throw new AssertionError("IME text was not accepted");
            });
            probe.awaitText("IME_INPUT_OK");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitUi(() -> !keyboardVisible(terminal), "Back did not hide keyboard");
            tap(terminal);
            awaitUi(() -> keyboardVisible(terminal), "Tap did not reopen keyboard");
            keyboardMenu(current);
            awaitUi(() -> !keyboardVisible(terminal), "Menu did not hide keyboard");
            keyboardMenu(current);
            awaitUi(() -> keyboardVisible(terminal), "Menu did not reopen keyboard");
            passed.append("Keyboard: touch focus, tap/open, IME command through PTY, Back/reopen, menu hide/show\n");
        } finally {
            Activity current = activity;
            TerminalService connected = owner;
            SessionManager<TerminalSession> saved = previous;
            runOnMainSync(() -> {
                if (connected != null) { field(connected, "sessions", saved); render(current); }
                if (current != null) current.finish();
                probe.session.finishIfRunning();
            });
        }
    }
    @SuppressWarnings("unchecked")
    private static SessionManager<TerminalSession> manager(TerminalService service) {
        return (SessionManager<TerminalSession>) field(service, "sessions");
    }
    private static SessionManager<TerminalSession> singleSession(TerminalSession terminal) {
        SessionManager<TerminalSession> manager = new SessionManager<>(new SessionManager.Backend<TerminalSession>() {
            @Override public TerminalSession create() { return terminal; }
            @Override public boolean isRunning(TerminalSession session) { return session.isRunning(); }
            @Override public void finish(TerminalSession session) { session.finishIfRunning(); }
        });
        try { manager.create(); }
        catch (Exception error) { throw new AssertionError(error); }
        return manager;
    }
    private void testDeferredSessionOutput(boolean close) throws Exception {
        Path temporary = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "session-drain-");
        Path release = temporary.resolve("release");
        List<Probe> probes = new ArrayList<>();
        SessionManager<TerminalSession> isolated = new SessionManager<>(new SessionManager.Backend<TerminalSession>() {
            @Override public TerminalSession create() {
                String command = probes.isEmpty()
                    ? "trap '' HUP; (while [ ! -f '" + release + "' ]; do sleep 0.05; done; printf 'DEFERRED_%s\\n' TAIL) & exit 0"
                    : "trap 'exit 0' TERM; printf 'RESTART_READY\\n'; while IFS= read -r line; do :; done";
                Probe probe = new Probe();
                probe.session = new TerminalSession("/system/bin/sh", getTargetContext().getFilesDir().getAbsolutePath(),
                    new String[]{"sh", "-c", command}, new String[]{"PATH=/system/bin", "TERM=xterm-256color"}, 10000, probe);
                probe.session.updateSize(80, 24, 0, 0);
                probes.add(probe);
                return probe.session;
            }
            @Override public boolean isRunning(TerminalSession session) { return session.isRunning(); }
            @Override public void finish(TerminalSession session) { session.finishIfRunning(); }
        });
        SessionCallbacks callbacks = new SessionCallbacks() {
            @Override public void onSessionFinished(TerminalSession session) { isolated.finished(session); }
        };
        try {
            checkUi(() -> {
                try { isolated.create(); }
                catch (Exception error) { throw new AssertionError(error); }
                probes.get(0).delegate = callbacks;
            });
            Probe first = probes.get(0);
            // The child deliberately holds the PTY open after waitpid has reaped its parent.
            awaitUi(() -> (boolean) field(first.session, "processEnded"), "Drain fixture's parent did not exit");
            checkUi(() -> {
                if (first.session.isRunning() || (boolean) field(first.session, "outputEnded") || first.finished.getCount() != 1)
                    throw new AssertionError("Drain fixture must have a reaped process and an open PTY reader");
                SessionManager.Entry<TerminalSession> original = isolated.selected();
                try {
                    if (close) isolated.close(original); else isolated.restartSelected();
                } catch (Exception error) { throw new AssertionError(error); }
                if (isolated.selected() != original || !isolated.entries().contains(original))
                    throw new AssertionError(close ? "Close discarded a PTY before its final output callback"
                        : "Restart replaced a PTY before its final output callback");
            });
            Files.createFile(release);
            first.awaitExit();
            if (!first.text.contains("DEFERRED_TAIL")) throw new AssertionError("Deferred PTY output was lost");
            checkUi(() -> {
                if (close) {
                    if (!isolated.isEmpty()) throw new AssertionError("Closed draining PTY remained after its callback");
                } else {
                    if (isolated.selected().terminal != first.session) throw new AssertionError("Drained transcript was replaced automatically");
                    try { isolated.restartSelected(); }
                    catch (Exception error) { throw new AssertionError(error); }
                    probes.get(1).delegate = callbacks;
                    if (isolated.selected().number != 1 || isolated.selected().terminal == first.session)
                        throw new AssertionError("Completed tab did not restart under its original number");
                }
            });
            if (!close) probes.get(1).awaitText("RESTART_READY");
            passed.append(close ? "Close while draining: reaped parent, live PTY reader, deferred tail retained through callback\n"
                : "Restart while draining: waits for deferred tail, keeps transcript, restarts completed tab under the same number\n");
        } finally {
            Files.write(release, new byte[0]);
            runOnMainSync(() -> { for (Probe probe : probes) { probe.delegate = null; probe.session.finishIfRunning(); } });
            for (Probe probe : probes) if (!probe.finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Drain fixture cleanup did not finish");
            RootfsInstaller.deleteTree(temporary);
        }
    }
    /** Use real PRoot and the service's production stop backend, including detached tracees. */
    @SuppressWarnings("unchecked")
    private void testSessionStopActions(boolean fromNotification, boolean overlapping) throws Exception {
        Context context = getTargetContext();
        AtomicReference<TerminalService> owner = new AtomicReference<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                owner.set(((TerminalService.LocalBinder) binder).service());
                connectedLatch.countDown();
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {}
        };
        AtomicBoolean bound = new AtomicBoolean();
        List<Probe> probes = new ArrayList<>();
        Path temporary = Files.createTempDirectory(context.getCacheDir().toPath(), "stop-proot-");
        Activity activity = null;
        boolean savedWake = context.getSharedPreferences("terminal", Context.MODE_PRIVATE).getBoolean("wake", true);
        try {
            Intent serviceIntent = new Intent(context, TerminalService.class);
            runOnMainSync(() -> {
                context.startForegroundService(serviceIntent);
                bound.set(context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE));
            });
            if (!bound.get() || !connectedLatch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Stop-test service did not bind");
            TerminalService connected = owner.get();
            SessionManager.Backend<TerminalSession> production = (SessionManager.Backend<TerminalSession>) field(manager(connected), "backend");
            SessionManager<TerminalSession> isolated = new SessionManager<>(new SessionManager.Backend<TerminalSession>() {
                @Override public TerminalSession create() {
                    File libraries = new File(context.getApplicationInfo().nativeLibraryDir);
                    String executable = new File(libraries, "libproot.so").getAbsolutePath();
                    // No Arch rootfs: trace Android's own shell, including a separate process session.
                    String command = "trap '' TERM HUP INT; "
                        + "setsid /system/bin/sh -c 'trap \"\" TERM HUP INT; printf \"STOP_CHILD_%s_READY\\n\" \"$$\"; while :; do sleep 1; done' & "
                        + "printf 'STOP_READY\\n'; while IFS= read -r command; do eval \"$command\"; done";
                    Probe probe = new Probe();
                    probe.delegate = (SessionCallbacks) field(connected, "callbacks");
                    probe.session = new TerminalSession(executable, context.getFilesDir().getAbsolutePath(),
                        new String[]{executable, "--kill-on-exit", "-r", "/", "/system/bin/sh", "-c", command},
                        new String[]{"PATH=/system/bin", "TERM=xterm-256color", "PROOT_NO_SECCOMP=1",
                            "PROOT_LOADER=" + new File(libraries, "libproot-loader.so").getAbsolutePath(), "PROOT_TMP_DIR=" + temporary}, 10000, probe);
                    probe.session.updateSize(80, 24, 0, 0);
                    probes.add(probe);
                    return probe.session;
                }
                @Override public boolean isRunning(TerminalSession session) { return session.isRunning(); }
                @Override public void finish(TerminalSession session) { production.finish(session); }
            });
            runOnMainSync(() -> {
                if (!connected.sessions().isEmpty()) throw new AssertionError("Stop test must start before any real Arch sessions");
                field(connected, "sessions", isolated);
                connected.startSession();
                connected.startSession();
                connected.setWakeEnabled(true);
            });
            if (probes.size() != 2) throw new AssertionError("Real PRoot stop fixtures did not start: " + connected.status());
            for (Probe probe : probes) {
                probe.awaitText("STOP_READY");
                awaitUi(() -> stopChildPid(probe) > 0, "Detached PRoot child did not start: " + probe.text);
            }
            if (!overlapping) activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity current = activity;
            if (!overlapping) awaitUi(() -> field(current, "service") == connected && current.hasWindowFocus(), "Stop-test Activity did not connect");
            Probe first = probes.get(0), second = probes.get(1);
            if (overlapping) {
                checkUi(() -> {
                    List<SessionManager.Entry<TerminalSession>> entries = connected.sessions();
                    connected.closeSession(entries.get(0));
                    if (connected.stopping()) throw new AssertionError("Closing one tab stopped another open tab");
                    // Both close requests run in one main-thread turn: neither exit callback can run yet.
                    connected.closeSession(entries.get(1));
                    if (!connected.stopping()) throw new AssertionError("Closing every tab before exit callbacks must stop the service");
                });
                awaitStopped(first);
                awaitStopped(second);
                awaitUi(() -> connected.sessions().isEmpty() && terminalNotification() == null
                    && !((android.os.PowerManager.WakeLock) field(connected, "wake")).isHeld(),
                    "Overlapping tab closes left the notification or WakeLock alive");
            } else if (fromNotification) {
                android.app.Notification notification = terminalNotification();
                if (notification == null || notification.actions == null) throw new AssertionError("Terminal notification has no stop action");
                android.app.PendingIntent stop = null;
                for (android.app.Notification.Action action : notification.actions)
                    if ("Alle beenden".contentEquals(action.title)) stop = action.actionIntent;
                if (stop == null) throw new AssertionError("Notification stop PendingIntent is missing");
                if (!getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) throw new AssertionError("Home failed before notification stop");
                awaitUi(() -> field(current, "service") == null, "Activity did not unbind before notification stop");
                // Send the actual notification PendingIntent while the Activity is in the background.
                stop.send();
                awaitStopped(first);
                awaitStopped(second);
                awaitUi(() -> connected.sessions().isEmpty() && terminalNotification() == null
                    && !((android.os.PowerManager.WakeLock) field(connected, "wake")).isHeld(), "Notification stop left sessions, a notification or WakeLock");
                stop.send();
                waitForIdleSync();
                if (terminalNotification() != null || !connected.sessions().isEmpty()) throw new AssertionError("Repeated notification stop resurrected the service notification");
            } else {
                sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
                clickVisibleText("Sitzung schließen");
                clickVisibleText("Schließen");
                awaitStopped(second);
                awaitUi(() -> connected.sessions().size() == 1 && connected.session() == first.session, "Menu close did not select the surviving session");
                runOnMainSync(() -> first.session.write("printf 'STILL_%s\\n' OPEN\n"));
                first.awaitText("STILL_OPEN");
                awaitUi(current::hasWindowFocus, "Menu close did not return focus");
                sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
                clickVisibleText("Sitzung schließen");
                clickVisibleText("Schließen");
                awaitStopped(first);
                awaitUi(() -> current.isFinishing() && connected.sessions().isEmpty() && terminalNotification() == null
                    && !((android.os.PowerManager.WakeLock) field(connected, "wake")).isHeld(), "Closing the last session did not finish the Activity and remove its notification/WakeLock");
            }
            passed.append(overlapping ? "Overlapping tab closes: both requests before exit callbacks, real PRoot and detached children reaped, notification/WakeLock removed\n"
                : fromNotification ? "Notification stop: real PendingIntent in background, both PRoot sessions and detached children reaped, notification/WakeLock removed, repeated stop\n"
                : "Menu close: real PRoot and detached child reaped, other session stays usable, last close finishes Activity and removes notification/WakeLock\n");
        } finally {
            Activity current = activity;
            runOnMainSync(() -> {
                // Also clean up the intentionally broken SIGTERM case when running this test against 0.1.7.
                for (Probe probe : probes) {
                    probe.delegate = null;
                    if (probe.session.getPid() > 0) try { android.system.Os.kill(probe.session.getPid(), android.system.OsConstants.SIGQUIT); }
                    catch (android.system.ErrnoException ignored) {}
                }
                if (current != null) current.finish();
            });
            for (Probe probe : probes) if (!probe.finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Stop-test PRoot cleanup did not finish");
            runOnMainSync(() -> {
                if (owner.get() != null) { owner.get().setWakeEnabled(savedWake); owner.get().stopSelf(); }
                if (bound.get()) context.unbindService(connection);
            });
            if (owner.get() != null) awaitUi(() -> (boolean) field(owner.get(), "destroyed"), "Stop-test service remained bound");
            RootfsInstaller.deleteTree(temporary);
        }
    }
    private int stopChildPid(Probe probe) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("STOP_CHILD_(\\d+)_READY").matcher(probe.text);
        return match.find() ? Integer.parseInt(match.group(1)) : -1;
    }
    private void awaitStopped(Probe probe) throws Exception {
        if (!probe.finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("PRoot ignored the stop action; output:\n" + probe.text);
        int child = stopChildPid(probe);
        if (child <= 0) throw new AssertionError("Detached child PID missing");
        awaitUi(() -> {
            try { android.system.Os.kill(child, 0); return false; }
            catch (android.system.ErrnoException error) { return error.errno == android.system.OsConstants.ESRCH; }
        }, "Detached child survived PRoot cleanup: " + child);
    }
    private android.app.Notification terminalNotification() {
        for (android.service.notification.StatusBarNotification notification : getTargetContext().getSystemService(android.app.NotificationManager.class).getActiveNotifications())
            if (notification.getId() == 1) return notification.getNotification();
        return null;
    }
    private void testLanguageSelection() throws Exception {
        TerminalLanguage savedLanguage = AppLanguage.get(getTargetContext());
        Activity activity = null;
        TerminalService owner = null;
        SessionManager<TerminalSession> previous = null;
        Probe probe = shell("export value=preserved; printf 'LANGUAGE_READY\\n'; exec /system/bin/sh");
        try {
            AppLanguage.set(getTargetContext(), TerminalLanguage.GERMAN);
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity original = activity;
            awaitUi(() -> field(original, "service") != null && original.hasWindowFocus(), "Language Activity did not connect");
            owner = (TerminalService) field(original, "service");
            TerminalService connected = owner;
            previous = manager(connected);
            probe.delegate = (SessionCallbacks) field(connected, "callbacks");
            SessionManager<TerminalSession> isolated = new SessionManager<>(new SessionManager.Backend<TerminalSession>() {
                @Override public TerminalSession create() { return probe.session; }
                @Override public boolean isRunning(TerminalSession session) { return session.isRunning(); }
                @Override public void finish(TerminalSession session) { session.finishIfRunning(); }
            });
            runOnMainSync(() -> { field(connected, "sessions", isolated); connected.startSession(); render(original); });
            probe.awaitText("LANGUAGE_READY");
            int pid = probe.session.getPid();
            sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
            clickVisibleText("Sprache / Language");
            clickVisibleText("English");
            awaitUi(() -> latestActivity != original && field(latestActivity, "service") == connected
                && latestActivity.hasWindowFocus(), "Language change did not recreate and reconnect the Activity");
            Activity english = latestActivity;
            activity = english;
            runOnMainSync(() -> {
                if (AppLanguage.get(getTargetContext()) != TerminalLanguage.ENGLISH
                    || !"New session".equals(english.getString(R.string.new_session))
                    || !"Install / resume Arch".equals(english.getString(R.string.install_arch)))
                    throw new AssertionError("English preference or resources were not applied");
                if (connected.session() != probe.session || probe.session.getPid() != pid
                    || field(english, "attached") != probe.session)
                    throw new AssertionError("Language change replaced the running PTY");
                android.app.Notification notification = terminalNotification();
                if (notification == null || !"Stop all".contentEquals(notification.actions[0].title))
                    throw new AssertionError("Service notification did not change language");
                probe.session.write("printf 'AFTER_LANGUAGE_%s_%s\\n' OK \"$value\"\n");
            });
            probe.awaitText("AFTER_LANGUAGE_OK_preserved");
            runOnMainSync(() -> probe.session.write("exit\n"));
            probe.awaitExit();
            if (!probe.text.contains("Process exited · 0 · press Enter for a new session") || probe.text.contains("Prozess beendet"))
                throw new AssertionError("Process exit message was not translated\n" + probe.text);
            sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
            clickVisibleText("Language / Sprache");
            clickVisibleText("Deutsch");
            awaitUi(() -> latestActivity != english && field(latestActivity, "service") == connected
                && latestActivity.hasWindowFocus(), "Switching back to German did not reconnect");
            activity = latestActivity;
            if (!"Neue Sitzung".equals(activity.getString(R.string.new_session))
                || AppLanguage.get(getTargetContext()) != TerminalLanguage.GERMAN)
                throw new AssertionError("German selection was not restored");
            passed.append("Language: actual menu, persisted English/German preference, Activity recreation, same PTY/PID/scrollback, live notification and English exit message\n");
        } finally {
            Activity current = latestActivity == null ? activity : latestActivity;
            TerminalService connected = owner;
            SessionManager<TerminalSession> saved = previous;
            runOnMainSync(() -> {
                AppLanguage.set(getTargetContext(), savedLanguage);
                probe.delegate = null;
                probe.session.finishIfRunning();
                if (connected != null && saved != null) { field(connected, "sessions", saved); connected.languageChanged(); }
                if (current != null) current.finish();
            });
        }
    }
    private void testMultipleSessions() throws Exception {
        Activity activity = null;
        TerminalService owner = null;
        SessionManager<TerminalSession> previous = null;
        List<Probe> probes = new ArrayList<>();
        boolean previousWake = true;
        try {
            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            Activity current = activity;
            awaitUi(() -> field(current, "service") != null && current.hasWindowFocus(), "Multi-session Activity did not connect");
            owner = (TerminalService) field(current, "service");
            TerminalService connected = owner;
            previous = manager(connected);
            previousWake = connected.wakeEnabled();
            SessionManager<TerminalSession> isolated = new SessionManager<>(new SessionManager.Backend<TerminalSession>() {
                @Override public TerminalSession create() {
                    Probe probe = new Probe();
                    probe.delegate = (SessionCallbacks) field(connected, "callbacks");
                    probe.session = new TerminalSession("/system/bin/sh", getTargetContext().getFilesDir().getAbsolutePath(),
                        new String[]{"sh", "-c", "trap 'exit 0' TERM; printf 'MULTI_READY\\n'; while IFS= read -r command; do eval \"$command\"; done"},
                        new String[]{"PATH=/system/bin", "TERM=xterm-256color"}, 10000, probe);
                    probe.session.updateSize(80, 24, 0, 0);
                    probes.add(probe);
                    return probe.session;
                }
                @Override public boolean isRunning(TerminalSession session) { return session.isRunning(); }
                @Override public void finish(TerminalSession session) { session.finishIfRunning(); }
            });
            runOnMainSync(() -> {
                field(connected, "sessions", isolated);
                connected.startSession();
                connected.setWakeEnabled(true);
                render(current);
            });
            Probe first = probes.get(0);
            first.awaitText("MULTI_READY");
            TerminalView terminal = (TerminalView) field(current, "terminal");
            SessionBar bar = (SessionBar) field(current, "sessionBar");
            android.widget.LinearLayout tabs = (android.widget.LinearLayout) field(bar, "tabs");
            awaitUi(() -> terminal.isShown() && terminal.getWidth() > 0 && field(terminal, "mEmulator") != null
                && bar.isShown() && bar.getWidth() > 0, "First terminal was not laid out before input");
            runOnMainSync(() -> first.session.write("value=FIRST; printf 'PRIVATE_%s\\n' \"$value\"\n"));
            tap((View) field(bar, "add"));
            awaitUi(() -> probes.size() == 2 && field(current, "attached") == probes.get(1).session
                && field(terminal, "mEmulator") == probes.get(1).session.getEmulator(), "Plus did not attach and lay out the second terminal");
            Probe second = probes.get(1);
            second.awaitText("MULTI_READY");
            first.awaitText("PRIVATE_FIRST");
            runOnMainSync(() -> {
                InputConnection input = terminal.onCreateInputConnection(new EditorInfo());
                if (input == null || !input.commitText("value=SECOND; printf 'PRIVATE_%s\\n' \"$value\"\n", 1))
                    throw new AssertionError("Second terminal did not accept IME input");
            });
            second.awaitText("PRIVATE_SECOND");
            runOnMainSync(() -> {
                if (first.text.contains("PRIVATE_SECOND") || second.text.contains("PRIVATE_FIRST"))
                    throw new AssertionError("Session input/output leaked across terminals");
                ((View) field(current, "control")).performClick();
                tabs.getChildAt(0).performClick();
                if (field(current, "attached") != first.session || !terminal.hasFocus() || (boolean) field(current, "ctrl"))
                    throw new AssertionError("Selecting a tab lost its shell/focus or carried CTRL into another session");
                tabs.getChildAt(1).performClick();
                if (field(current, "attached") != second.session || tabs.getChildCount() != 2)
                    throw new AssertionError("Session tabs did not switch back to the same PTY");
                ((View) field(current, "keys")).setVisibility(View.GONE);
                if (!bar.isShown()) throw new AssertionError("Session navigation depends on extra keys");
            });
            if (!getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                throw new AssertionError("Android did not accept Home during multi-session test");
            awaitUi(() -> field(current, "service") == null, "Activity did not unbind in background");
            runOnMainSync(() -> first.session.write("printf 'BACKGROUND_%s\\n' 'OK'\n"));
            first.awaitText("BACKGROUND_OK");
            getTargetContext().startActivity(intent);
            awaitUi(() -> field(current, "service") == connected && current.hasWindowFocus()
                && field(current, "attached") == second.session, "Rebinding lost the selected terminal");
            awaitUi(current::hasWindowFocus, "Activity did not regain focus before creating a session from the menu");
            sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
            clickVisibleText("Neue Sitzung");
            awaitUi(() -> probes.size() == 3, "Session menu did not create another PTY");
            runOnMainSync(() -> {
                connected.selectSession(isolated.entries().get(1));
                tabs.getChildAt(1).performLongClick();
            });
            clickVisibleText("Schließen");
            if (!second.finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Closing one tab did not finish its PTY");
            Probe third = probes.get(2);
            third.awaitText("MULTI_READY");
            awaitUi(() -> connected.sessions().size() == 2 && connected.session() == third.session, "Closed tab did not select its neighbor");
            runOnMainSync(() -> {
                if (!first.session.isRunning() || !third.session.isRunning()
                    || !((android.os.PowerManager.WakeLock) field(connected, "wake")).isHeld())
                    throw new AssertionError("Closing one terminal stopped another session or released its WakeLock");
                first.session.write("exit 7\n");
            });
            if (!first.finished.await(10, TimeUnit.SECONDS) || first.exit != 7)
                throw new AssertionError("Background shell did not report its own exit status");
            awaitUi(() -> connected.session() == third.session && isolated.runningCount() == 1, "Background exit changed the active shell");
            runOnMainSync(() -> {
                if (!((android.os.PowerManager.WakeLock) field(connected, "wake")).isHeld())
                    throw new AssertionError("Background exit released a running terminal's WakeLock");
                tabs.getChildAt(0).performClick();
                if (field(current, "attached") != first.session || !terminal.getCurrentSession().getEmulator().getScreen().getTranscriptText().contains("PRIVATE_FIRST"))
                    throw new AssertionError("Exited terminal lost its scrollback");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            awaitUi(() -> probes.size() == 4 && connected.selectedSession().number == 1
                && connected.sessions().size() == 2 && third.session.isRunning(), "Enter did not restart only the ended tab");
            Probe restarted = probes.get(3);
            restarted.awaitText("MULTI_READY");
            runOnMainSync(() -> connected.closeSession(isolated.selected()));
            if (!restarted.finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Restarted PTY did not close");
            awaitUi(() -> connected.session() == third.session && !connected.stopping(), "Closing the restarted tab stopped another shell");
            runOnMainSync(isolated::closeAll);
            if (!third.finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Final test PTY did not close");
            awaitUi(() -> isolated.isEmpty() && !((android.os.PowerManager.WakeLock) field(connected, "wake")).isHeld(),
                "Final PTY exit left a session or WakeLock behind");
            passed.append("Multiple terminals: plus/menu creation, independent IME/shell state and scrollback, tab selection/focus, background/rebind, close isolation, background exit, Enter restart, WakeLock lifetime\n");
        } finally {
            Activity current = activity;
            TerminalService connected = owner;
            SessionManager<TerminalSession> saved = previous;
            boolean savedWake = previousWake;
            runOnMainSync(() -> {
                for (Probe probe : probes) { probe.delegate = null; probe.session.finishIfRunning(); }
                if (connected != null && saved != null) {
                    field(connected, "sessions", saved);
                    connected.setWakeEnabled(savedWake);
                }
                if (current != null) { if (connected != null) render(current); current.finish(); }
            });
        }
    }
    private void clickVisibleText(String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> items = root.findAccessibilityNodeInfosByText(text);
                if (!items.isEmpty()) {
                    Rect bounds = new Rect();
                    items.get(0).getBoundsInScreen(bounds);
                    tap(bounds.centerX(), bounds.centerY());
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Dialog item was not available: " + text);
    }
    private void testSetupActions(Activity activity) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                View action = (View) field(activity, "action");
                View cancel = (View) field(activity, "cancel");
                TextView message = (TextView) field(activity, "message");
                if (action.getVisibility() != View.GONE || message.getText().toString().contains("791 MiB"))
                    throw new AssertionError("Unconnected Activity offers Arch installation before checking the existing rootfs");
                setup(activity, false, true, false, false, null);
                if (action.getVisibility() != View.GONE || !message.getText().toString().equals("Konsole wird gestartet …"))
                    throw new AssertionError("Installed Arch shows an install action while connecting");
                setup(activity, true, true, false, false, "Arch Linux ARM ist bereit zur Installation.");
                if (action.getVisibility() != View.GONE || message.getText().toString().contains("Installation"))
                    throw new AssertionError("Installed Arch shows the service's initial installation message");
                setup(activity, true, true, false, true, "Start fehlgeschlagen: Testfehler");
                if (action.getVisibility() != View.VISIBLE || !((TextView) action).getText().toString().equals("Konsole erneut starten")
                    || !message.getText().toString().contains("Testfehler") || message.getText().toString().contains("791 MiB"))
                    throw new AssertionError("Failed console start offers installation instead of a retry");
                setup(activity, false, false, false, false, null);
                if (action.getVisibility() != View.GONE || message.getText().toString().contains("791 MiB"))
                    throw new AssertionError("Installation offered before the service connected");
                setup(activity, true, false, false, false, "Arch fehlt.");
                if (action.getVisibility() != View.VISIBLE || !((TextView) action).getText().toString().equals("Arch installieren / fortsetzen")
                    || !message.getText().toString().contains("791 MiB"))
                    throw new AssertionError("Missing Arch cannot be installed after the service connected");
                setup(activity, true, false, true, false, "Arch wird geladen …");
                if (action.getVisibility() != View.GONE || cancel.getVisibility() != View.VISIBLE
                    || !message.getText().toString().equals("Arch wird geladen …"))
                    throw new AssertionError("Active installation shows a duplicate install action");
                render(activity);
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Setup UI check failed: " + failure.get(), failure.get());
        passed.append("Setup actions: no install while connecting/installed, console retry on failure, install only for missing Arch\n");
    }
    private static void setup(Activity activity, boolean connected, boolean ready, boolean installing, boolean failed, String status) {
        try {
            Method render = MainActivity.class.getDeclaredMethod("renderSetup", boolean.class, boolean.class, boolean.class, boolean.class, String.class);
            render.setAccessible(true);
            render.invoke(activity, connected, ready, installing, failed, status);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void keyboardMenu(Activity activity) throws Exception {
        // With animations disabled the IME can close before the previous dialog returns focus.
        awaitUi(activity::hasWindowFocus, "Activity did not regain focus before the menu key");
        sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> items = root.findAccessibilityNodeInfosByText("Tastatur ein / aus");
                if (!items.isEmpty()) {
                    Rect bounds = new Rect();
                    items.get(0).getBoundsInScreen(bounds);
                    tap(bounds.centerX(), bounds.centerY());
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Keyboard menu item was not available");
    }
    private void tap(View view) {
        int[] position = new int[2];
        runOnMainSync(() -> { view.getLocationOnScreen(position); position[0] += view.getWidth() / 2; position[1] += Math.min(view.getHeight() / 2, 100); });
        tap(position[0], position[1]);
    }
    private void tap(int x, int y) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, now + (action == MotionEvent.ACTION_UP ? 40 : 0), action, x, y, 0);
            sendPointerSync(event);
            event.recycle();
        }
        waitForIdleSync();
    }
    private boolean keyboardVisible(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (Build.VERSION.SDK_INT >= 30) return insets != null && insets.isVisible(WindowInsets.Type.ime());
        float threshold = 120 * view.getResources().getDisplayMetrics().density;
        if (insets != null && insets.getSystemWindowInsetBottom() - insets.getStableInsetBottom() > threshold) return true;
        Rect visible = new Rect();
        view.getWindowVisibleDisplayFrame(visible);
        return view.getRootView().getHeight() - visible.bottom > threshold;
    }
    private void awaitUi(BooleanSupplier condition, String failure) throws Exception {
        AtomicBoolean result = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            runOnMainSync(() -> result.set(condition.getAsBoolean()));
            if (result.get()) return;
            Thread.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(failure);
    }
    private void checkUi(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get().getMessage(), failure.get());
    }
    private static Object field(Object target, String name) { return field(target, name, null, false); }
    private static void field(Object target, String name, Object value) { field(target, name, value, true); }
    private static Object field(Object target, String name, Object value, boolean write) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            if (write) field.set(target, value);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void render(Activity activity) {
        try {
            Method render = MainActivity.class.getDeclaredMethod("render");
            render.setAccessible(true);
            render.invoke(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void testArch() throws Exception {
        if (!RootfsInstaller.installed(getTargetContext())) throw new AssertionError("Install Arch in the app before --rootfs device tests");
        RuntimeFiles.prepare(getTargetContext());
        Probe probe = new Probe();
        LaunchSpec launch = new LaunchSpec(getTargetContext().getFilesDir(), new File(getTargetContext().getApplicationInfo().nativeLibraryDir), true,
            "/bin/sh", "-c", "test -t 0 && uname -m && pacman --version && pacman -Q bash pacman && printf 'ARCH_PACMAN_OK\\n'");
        runOnMainSync(() -> {
            probe.session = new TerminalSession(launch.executable, launch.cwd, launch.arguments, launch.environment, 10000, probe);
            probe.session.updateSize(80, 24, 0, 0);
        });
        probe.awaitExit();
        if (!probe.text.contains("aarch64") || !probe.text.contains("ARCH_PACMAN_OK")) throw new AssertionError("ARM64 pacman failed\n" + probe.text);
        passed.append("Android PRoot, glibc loader, Arch ARM64, pacman query\n");
    }
}
