package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Arch Terminal adaptation of Termux's Apache-2.0 session API, backed by C++ PTYs.
 * Rendering stays on the main thread. Backpressure only pauses the reader; it never terminates a session.
 */
public final class TerminalSession extends TerminalOutput {
    public final String mHandle = UUID.randomUUID().toString();
    public String mSessionName;
    private final String executable, cwd;
    private final String[] arguments, environment;
    private final Integer transcriptRows;
    private TerminalSessionClient client;
    private TerminalEmulator emulator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ByteQueue output = new ByteQueue(256 * 1024);
    private final InputQueue input = new InputQueue(1024 * 1024);
    private final AtomicBoolean renderQueued = new AtomicBoolean();
    private final byte[] receive = new byte[16384];
    private volatile int pid;
    private int master = -1;
    private boolean outputEnded, processEnded;
    private int exitStatus;

    public TerminalSession(String executable, String cwd, String[] arguments, String[] environment,
                           Integer transcriptRows, TerminalSessionClient client) {
        this.executable = executable; this.cwd = cwd; this.arguments = arguments;
        this.environment = environment; this.transcriptRows = transcriptRows; this.client = client;
    }
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        this.client = client;
        if (emulator != null) emulator.updateTerminalSessionClient(client);
    }
    public void updateSize(int columns, int rows, int width, int height) {
        if (emulator == null) initializeEmulator(columns, rows, width, height);
        else {
            if (pid > 0 && !processEnded) JNI.setPtyWindowSize(master, rows, columns, width, height);
            emulator.resize(columns, rows, width, height);
        }
    }
    public void initializeEmulator(int columns, int rows, int width, int height) {
        if (emulator != null) return;
        int[] processId = new int[1];
        master = JNI.createSubprocess(executable, cwd, arguments, environment, processId, rows, columns, width, height);
        pid = processId[0];
        int readerFd = -1, writerFd = -1;
        try {
            emulator = new TerminalEmulator(this, columns, rows, width, height, transcriptRows, client);
            readerFd = JNI.duplicate(master);
            writerFd = JNI.duplicate(master);
        } catch (RuntimeException | Error failure) {
            if (readerFd >= 0) JNI.close(readerFd);
            if (writerFd >= 0) JNI.close(writerFd);
            finishIfRunning();
            final int failedChild = pid;
            new Thread(() -> JNI.waitFor(failedChild), "arch-pty-failed-start-reaper").start();
            JNI.close(master);
            master = -1;
            pid = -1;
            throw failure;
        }
        final int reader = readerFd, writer = writerFd, child = pid;
        new Thread(() -> {
            try {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = JNI.read(reader, buffer)) > 0) {
                    if (!output.write(buffer, 0, count)) break;
                    scheduleRender();
                }
            } finally {
                JNI.close(reader);
                main.post(() -> { outputEnded = true; scheduleRender(); });
            }
        }, "arch-pty-read").start();
        new Thread(() -> {
            try {
                byte[] bytes;
                while ((bytes = input.take()) != null) JNI.write(writer, bytes, bytes.length);
            } catch (IOException error) {
                main.post(() -> client.logWarn("PTY", client.getInputErrorMessage(error.getMessage())));
            } finally { input.close(); JNI.close(writer); }
        }, "arch-pty-write").start();
        new Thread(() -> {
            int result = JNI.waitFor(child);
            main.post(() -> { processEnded = true; exitStatus = result; input.close(); scheduleRender(); });
        }, "arch-pty-wait").start();
    }
    private void scheduleRender() {
        if (renderQueued.compareAndSet(false, true)) main.post(this::drain);
    }
    private void drain() {
        renderQueued.set(false);
        if (pid == -1) return;
        int count = 0, total = 0;
        while (total < 65536 && (count = output.read(receive, false)) > 0) {
            emulator.append(receive, count);
            total += count;
        }
        if (total > 0) client.onTextChanged(this);
        if (total >= 65536) { scheduleRender(); return; }
        if (processEnded && outputEnded && count <= 0) {
            pid = -1;
            output.close();
            JNI.close(master);
            master = -1;
            byte[] ending = ("\r\n[" + client.getProcessExitMessage(exitStatus) + "]\r\n").getBytes(StandardCharsets.UTF_8);
            emulator.append(ending, ending.length);
            client.onTextChanged(this);
            client.onSessionFinished(this);
        }
    }
    @Override public void write(byte[] data, int offset, int count) {
        if (pid <= 0 || count <= 0) return;
        if (!input.offer(data, offset, count)) {
            client.logWarn("PTY", client.getInputBufferFullMessage());
            client.onBell(this);
        }
    }
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) throw new IllegalArgumentException("Invalid code point");
        write((prependEscape ? "\033" : "") + new String(Character.toChars(codePoint)));
    }
    public TerminalEmulator getEmulator() { return emulator; }
    public String getTitle() { return emulator == null ? null : emulator.getTitle(); }
    public int getPid() { return pid; }
    public boolean isRunning() { return pid > 0 && !processEnded; }
    public int getExitStatus() { return exitStatus; }
    public String getCwd() {
        if (!isRunning()) return null;
        try { return new File("/proc/" + pid + "/cwd").getCanonicalPath(); }
        catch (IOException error) { return null; }
    }
    public void reset() { if (emulator != null) { emulator.reset(); client.onTextChanged(this); } }
    public void finishIfRunning() { finishIfRunning(OsConstants.SIGTERM); }
    /** Let the process owner choose the shutdown signal required by its child. */
    public void finishIfRunning(int signal) {
        if (isRunning()) {
            try { Os.kill(pid, signal); }
            catch (Exception error) { client.logWarn("PTY", error.getMessage()); }
        }
    }
    @Override public void titleChanged(String oldTitle, String title) { client.onTitleChanged(this); }
    @Override public void onCopyTextToClipboard(String text) { client.onCopyTextToClipboard(this, text); }
    @Override public void onPasteTextFromClipboard() { client.onPasteTextFromClipboard(this); }
    @Override public void onBell() { client.onBell(this); }
    @Override public void onColorsChanged() { client.onColorsChanged(this); }
}
