package de.archterminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.system.OsConstants;
import android.widget.Toast;
import com.termux.terminal.TerminalSession;
import java.io.File;
import java.util.List;

/** Owns every PTY independently of the Activity. No idle, output, or command deadline. */
public final class TerminalService extends Service {
    interface Listener { void changed(); }
    final class LocalBinder extends Binder { TerminalService service() { return TerminalService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final String CHANNEL = "arch-terminal", STOP = "de.archterminal.STOP";
    private Listener listener;
    private final SessionManager<TerminalSession> sessions = new SessionManager<>(new SessionManager.Backend<TerminalSession>() {
        @Override public TerminalSession create() throws Exception { return createTerminal(); }
        @Override public boolean isRunning(TerminalSession terminal) { return terminal.isRunning(); }
        @Override public void finish(TerminalSession terminal) {
            // The pinned PRoot ignores SIGTERM; SIGQUIT kills and reaps all its tracees.
            terminal.finishIfRunning(OsConstants.SIGQUIT);
        }
    });
    private RootfsInstaller installer;
    private PowerManager.WakeLock wake;
    private int statusResource = R.string.ready_to_install;
    private Object[] statusArguments = new Object[0];
    private boolean installing, stopping, destroyed, startFailed;

    @Override public void onCreate() {
        super.onCreate();
        updateNotificationChannel();
        startForeground(1, notification());
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArchTerminal:PTY");
        wake.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) stopAllSessions();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    void listen(Listener listener) { this.listener = listener; if (listener != null) listener.changed(); }
    void unlisten(Listener listener) { if (this.listener == listener) this.listener = null; }
    TerminalSession session() { return sessions.selected() == null ? null : sessions.selected().terminal; }
    List<SessionManager.Entry<TerminalSession>> sessions() { return sessions.entries(); }
    SessionManager.Entry<TerminalSession> selectedSession() { return sessions.selected(); }
    boolean stopping() { return stopping; }
    String status() {
        if (startFailed || session() == null || installing) return text(statusResource, statusArguments);
        SessionManager.Entry<TerminalSession> selected = sessions.selected();
        if (selected.closing()) return text(R.string.closing_session_status, Integer.toString(selected.number));
        if (!selected.terminal.isRunning()) return text(R.string.ended_session_status, Integer.toString(selected.number), selected.terminal.getExitStatus());
        return runningStatus();
    }
    private String runningStatus() {
        int count = sessions.runningCount();
        return AppLanguage.wrap(this).getResources().getQuantityString(R.plurals.running_sessions, count, count);
    }
    private String text(int resource, Object... arguments) { return AppLanguage.text(this, resource, arguments); }
    private void setStatus(int resource, Object... arguments) { statusResource = resource; statusArguments = arguments; }
    private void updateNotificationChannel() {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, text(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
    }
    void languageChanged() { updateNotificationChannel(); changed(); }
    boolean installing() { return installing; }
    boolean startFailed() { return startFailed; }
    boolean ready() { return RootfsInstaller.installed(this); }
    private void changed() {
        if (destroyed) return;
        if (!stopping || !sessions.isEmpty() || installing)
            getSystemService(NotificationManager.class).notify(1, notification());
        if (listener != null) listener.changed();
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TerminalService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("Arch Terminal").setContentText(sessions.runningCount() > 0 ? runningStatus() : status()).setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null, text(R.string.stop_all), stop).build()).build();
    }
    void install() {
        if (installing || ready()) return;
        installing = true;
        setStatus(R.string.preparing_download);
        acquireWake();
        installer = new RootfsInstaller(this, (resource, arguments) -> main.post(() -> { setStatus(resource, arguments); changed(); }));
        RootfsInstaller current = installer;
        changed();
        new Thread(() -> {
            String failure = null;
            try { current.install(); }
            catch (Exception error) { failure = error.getMessage() == null ? error.toString() : error.getMessage(); }
            final String result = failure;
            main.post(() -> {
                installing = false;
                installer = null;
                if (result == null) setStatus(R.string.installed);
                else setStatus(R.string.installation_failed, result);
                updateWake();
                if (!destroyed && result == null) startSession();
                changed();
            });
        }, "arch-install").start();
    }
    void cancelInstall() { if (installer != null) { installer.cancel(); setStatus(R.string.pausing_installation); changed(); } }
    void startSession() { startSession(false); }
    void restartSession() { startSession(true); }
    private void startSession(boolean restart) {
        if (destroyed || stopping || installing) return;
        startFailed = false;
        try {
            if (restart) sessions.restartSelected(); else sessions.create();
        } catch (Exception error) {
            startFailed = true;
            setStatus(R.string.start_failed, error.getMessage());
            Toast.makeText(this, status(), Toast.LENGTH_LONG).show();
        }
        updateWake();
        changed();
    }
    private TerminalSession createTerminal() throws Exception {
        if (!ready()) throw new IllegalStateException(text(R.string.not_installed));
        String upgradeWarning = RuntimeFiles.prepare(this);
        boolean compatible = getSharedPreferences("terminal", MODE_PRIVATE).getBoolean("no_seccomp", false);
        LaunchSpec launch = new LaunchSpec(getFilesDir(), new File(getApplicationInfo().nativeLibraryDir), compatible, AppLanguage.get(this));
        TerminalSession fresh = new TerminalSession(launch.executable, launch.cwd, launch.arguments, launch.environment, 10000, callbacks);
        fresh.updateSize(80, 24, 0, 0);
        if (upgradeWarning != null) {
            byte[] notice = (text(R.string.notice, upgradeWarning) + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            fresh.getEmulator().append(notice, notice.length);
        }
        return fresh;
    }
    void selectSession(SessionManager.Entry<TerminalSession> entry) {
        if (sessions.select(entry)) { startFailed = false; changed(); }
    }
    void closeSession(SessionManager.Entry<TerminalSession> entry) {
        if (!sessions.entries().contains(entry)) return;
        sessions.close(entry);
        // Other tabs may already be closing but still awaiting their PTY exit callbacks.
        if (sessions.entries().stream().allMatch(SessionManager.Entry::closing)) stopping = true;
        updateWake();
        changed();
        stopIfFinished();
    }
    void stopAllSessions() {
        if (installing) { cancelInstall(); return; }
        stopping = true;
        sessions.closeAll();
        updateWake();
        changed();
        stopIfFinished();
    }
    private void stopIfFinished() {
        if (!stopping || !sessions.isEmpty()) return;
        releaseWake();
        // stopSelf alone keeps a bound service alive until its Activity unbinds.
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    void setWakeEnabled(boolean value) {
        getSharedPreferences("terminal", MODE_PRIVATE).edit().putBoolean("wake", value).apply();
        updateWake();
    }
    boolean wakeEnabled() { return getSharedPreferences("terminal", MODE_PRIVATE).getBoolean("wake", true); }
    private void acquireWake() { if (wakeEnabled() && !wake.isHeld()) wake.acquire(); }
    private void releaseWake() { if (wake != null && wake.isHeld()) wake.release(); }
    private void updateWake() {
        if (!destroyed && wakeEnabled() && (installing || sessions.runningCount() > 0)) acquireWake();
        else releaseWake();
    }
    private final SessionCallbacks callbacks = new SessionCallbacks() {
        @Override public String getProcessExitMessage(int status) { return text(R.string.process_ended, status); }
        @Override public String getInputErrorMessage(String detail) { return text(R.string.input_ended, detail); }
        @Override public String getInputBufferFullMessage() { return text(R.string.input_buffer_full); }
        @Override public void onTextChanged(TerminalSession changed) { if (!destroyed && changed == session() && listener != null) listener.changed(); }
        @Override public void onTitleChanged(TerminalSession changed) { changed(); }
        @Override public void onColorsChanged(TerminalSession changed) { if (!destroyed && changed == session() && listener != null) listener.changed(); }
        @Override public void onSessionFinished(TerminalSession ended) {
            if (destroyed || !sessions.finished(ended)) return;
            updateWake();
            changed();
            stopIfFinished();
        }
        @Override public void onCopyTextToClipboard(TerminalSession changed, String text) {
            // Only explicit user selection copies text, not program-generated OSC 52 sequences.
        }
        @Override public void logWarn(String tag, String message) {
            super.logWarn(tag, message);
            if (message.equals(text(R.string.input_buffer_full))) Toast.makeText(TerminalService.this, message, Toast.LENGTH_LONG).show();
        }
    };
    @Override public void onDestroy() {
        destroyed = true;
        listener = null;
        cancelInstall();
        sessions.closeAll();
        releaseWake();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
