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
    private String status = "Arch Linux ARM ist bereit zur Installation.";
    private boolean installing, stopping, destroyed, startFailed;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Arch-Konsole", NotificationManager.IMPORTANCE_LOW));
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
        if (startFailed || session() == null || installing) return status;
        SessionManager.Entry<TerminalSession> selected = sessions.selected();
        if (selected.closing()) return "Sitzung " + selected.number + " wird beendet …";
        if (!selected.terminal.isRunning()) return "Sitzung " + selected.number + " beendet · Status " + selected.terminal.getExitStatus() + " · Enter startet neu";
        return runningStatus();
    }
    private String runningStatus() {
        int count = sessions.runningCount();
        return count + (count == 1 ? " Sitzung läuft" : " Sitzungen laufen") + " · aarch64";
    }
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
            .setOngoing(true).addAction(new Notification.Action.Builder(null, "Alle beenden", stop).build()).build();
    }
    void install() {
        if (installing || ready()) return;
        installing = true;
        status = "Arch-Download wird vorbereitet …";
        acquireWake();
        installer = new RootfsInstaller(this, text -> main.post(() -> { status = text; changed(); }));
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
                status = result == null ? "Arch installiert. Konsole starten." : result;
                updateWake();
                if (!destroyed && result == null) startSession();
                changed();
            });
        }, "arch-install").start();
    }
    void cancelInstall() { if (installer != null) { installer.cancel(); status = "Installation wird angehalten …"; changed(); } }
    void startSession() { startSession(false); }
    void restartSession() { startSession(true); }
    private void startSession(boolean restart) {
        if (destroyed || stopping || installing) return;
        startFailed = false;
        try {
            if (restart) sessions.restartSelected(); else sessions.create();
        } catch (Exception error) {
            startFailed = true;
            status = "Start fehlgeschlagen: " + error.getMessage() + " · Erneut versuchen oder Kompatibilitätsmodus wählen.";
            Toast.makeText(this, status, Toast.LENGTH_LONG).show();
        }
        updateWake();
        changed();
    }
    private TerminalSession createTerminal() throws Exception {
        if (!ready()) throw new IllegalStateException("Arch ist noch nicht installiert.");
        String upgradeWarning = RuntimeFiles.prepare(this);
        boolean compatible = getSharedPreferences("terminal", MODE_PRIVATE).getBoolean("no_seccomp", false);
        LaunchSpec launch = new LaunchSpec(getFilesDir(), new File(getApplicationInfo().nativeLibraryDir), compatible);
        TerminalSession fresh = new TerminalSession(launch.executable, launch.cwd, launch.arguments, launch.environment, 10000, callbacks);
        fresh.updateSize(80, 24, 0, 0);
        if (upgradeWarning != null) {
            byte[] notice = ("Hinweis: " + upgradeWarning + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            if (message.contains("Eingabepuffer voll")) Toast.makeText(TerminalService.this, message, Toast.LENGTH_LONG).show();
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
