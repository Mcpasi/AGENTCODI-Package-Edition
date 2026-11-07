package de.archterminal;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class MainActivity extends Activity {
    private TerminalService service;
    private TerminalView terminal;
    private TerminalSession attached;
    private LinearLayout root, setup;
    private HorizontalScrollView keys;
    private SessionBar sessionBar;
    private TextView message;
    private ImageView startupLogo;
    private ObjectAnimator logoPulse;
    private Button action, cancel, control, alt;
    private boolean bound, started, ctrl, altDown, volumeControl;
    private int textSize;
    private float accumulatedScale = 1f;
    private final TerminalService.Listener sessionListener = this::render;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TerminalService.LocalBinder) binder).service();
            service.listen(sessionListener);
            if (service.ready() && service.session() == null) service.startSession();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; attached = null; render(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        textSize = getPreferences(MODE_PRIVATE).getInt("font", 14);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 13, 18));
        sessionBar = new SessionBar(this, new SessionBar.Listener() {
            @Override public void select(SessionManager.Entry<TerminalSession> entry) { if (service != null) service.selectSession(entry); }
            @Override public void close(SessionManager.Entry<TerminalSession> entry) { closeSession(entry); }
            @Override public void create() { if (service != null) service.startSession(); }
        });
        root.addView(sessionBar, new LinearLayout.LayoutParams(-1, dp(48)));
        terminal = new TerminalView(this, null);
        // The embedded view relies on its host to enable focus for IME input.
        terminal.setFocusable(true);
        terminal.setFocusableInTouchMode(true);
        terminal.setTerminalViewClient(viewClient);
        terminal.setTextSize(sp(textSize));
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setContentDescription("Arch Linux ARM Konsole");
        terminal.setOnCreateContextMenuListener((menu, view, info) -> menu.add("Konsole-Menü").setOnMenuItemClickListener(item -> { menu(); return true; }));
        root.addView(terminal, new LinearLayout.LayoutParams(-1, 0, 1));
        setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setPadding(dp(24), dp(24), dp(24), dp(24));
        setup.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        startupLogo = new ImageView(this);
        startupLogo.setImageResource(R.drawable.ic_arch_linux);
        startupLogo.setFocusable(false);
        startupLogo.setClickable(false);
        startupLogo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        logoParams.rightMargin = dp(16);
        headingRow.addView(startupLogo, logoParams);
        logoPulse = ObjectAnimator.ofFloat(startupLogo, View.ALPHA, 1f, 0.55f);
        logoPulse.setDuration(1200);
        logoPulse.setRepeatCount(ValueAnimator.INFINITE);
        logoPulse.setRepeatMode(ValueAnimator.REVERSE);
        TextView heading = text("ARCH\nTERMINAL", 28, Color.rgb(84, 200, 232));
        headingRow.addView(heading);
        setup.addView(headingRow);
        message = text("App wird verbunden …", 14, Color.LTGRAY);
        message.setTextIsSelectable(true);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.topMargin = dp(24);
        setup.addView(message, messageParams);
        action = button("Arch installieren");
        action.setVisibility(View.GONE);
        action.setOnClickListener(view -> { if (service != null) { if (service.ready()) service.startSession(); else service.install(); } });
        setup.addView(action);
        cancel = button("Download anhalten");
        cancel.setOnClickListener(view -> { if (service != null) service.cancelInstall(); });
        setup.addView(cancel);
        root.addView(setup, new LinearLayout.LayoutParams(-1, 0, 1));
        keys = new HorizontalScrollView(this);
        keys.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addKey(row, "ESC", () -> send("\033"));
        addKey(row, "TAB", () -> send("\t"));
        control = addKey(row, "CTRL", () -> { ctrl = !ctrl; control.setSelected(ctrl); control.setTextColor(ctrl ? Color.CYAN : Color.LTGRAY); });
        alt = addKey(row, "ALT", () -> { altDown = !altDown; alt.setSelected(altDown); alt.setTextColor(altDown ? Color.CYAN : Color.LTGRAY); });
        addKey(row, "←", () -> arrow(KeyEvent.KEYCODE_DPAD_LEFT));
        addKey(row, "↓", () -> arrow(KeyEvent.KEYCODE_DPAD_DOWN));
        addKey(row, "↑", () -> arrow(KeyEvent.KEYCODE_DPAD_UP));
        addKey(row, "→", () -> arrow(KeyEvent.KEYCODE_DPAD_RIGHT));
        addKey(row, "⋮", this::menu);
        keys.addView(row);
        root.addView(keys, new LinearLayout.LayoutParams(-1, dp(44)));
        setContentView(root);
        // Legacy fullscreen prevents adjustResize on some versions; use the visible frame for the IME.
        if (Build.VERSION.SDK_INT < 30) root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visible = new Rect();
            root.getWindowVisibleDisplayFrame(visible);
            int covered = Math.max(0, root.getHeight() - visible.bottom);
            int bottom = covered > dp(120) ? covered : 0;
            if (root.getPaddingBottom() != bottom) root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), bottom);
        });
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(cutout.left, cutout.top, cutout.right, Math.max(cutout.bottom, ime.bottom));
            return insets;
        });
        immersive();
        render();
    }
    @Override protected void onStart() {
        super.onStart();
        started = true;
        updateLogoAnimation();
        Intent intent = new Intent(this, TerminalService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        started = false;
        updateLogoAnimation();
        if (service != null) service.unlisten(sessionListener);
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onStop();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
        updateLogoAnimation();
    }
    private void updateLogoAnimation() {
        if (logoPulse == null) return;
        // Decoration only: no delayed shell, overlay, input focus, or work while hidden.
        if (started && startupLogo.isShown() && hasWindowFocus() && ValueAnimator.areAnimatorsEnabled()) {
            if (!logoPulse.isStarted()) logoPulse.start();
        } else {
            logoPulse.cancel();
            startupLogo.setAlpha(1f);
        }
    }
    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    private void render() {
        if (service != null && service.stopping() && service.sessions().isEmpty()) { finish(); return; }
        TerminalSession current = service == null ? attached : service.session();
        boolean hasSession = current != null;
        sessionBar.setVisibility(hasSession ? View.VISIBLE : View.GONE);
        sessionBar.render(service == null ? Collections.emptyList() : service.sessions(),
            service == null ? null : service.selectedSession(), service != null && !service.stopping());
        terminal.setVisibility(hasSession ? View.VISIBLE : View.GONE);
        setup.setVisibility(hasSession ? View.GONE : View.VISIBLE);
        updateLogoAnimation();
        keys.setVisibility(hasSession && getPreferences(MODE_PRIVATE).getBoolean("keys", true) ? View.VISIBLE : View.GONE);
        if (hasSession) {
            if (attached != current) {
                // Modifier latches belong to the current interaction, not to another shell.
                ctrl = altDown = volumeControl = false;
                control.setSelected(false); control.setTextColor(Color.LTGRAY);
                alt.setSelected(false); alt.setTextColor(Color.LTGRAY);
                attached = current; terminal.attachSession(current); terminal.requestFocus();
            }
            terminal.onScreenUpdated();
        } else {
            // The rootfs marker is available before the asynchronous service connection.
            boolean ready = service == null ? RootfsInstaller.installed(this) : service.ready();
            boolean installing = service != null && service.installing();
            renderSetup(service != null, ready, installing, service != null && service.startFailed(), service == null ? null : service.status());
        }
    }
    private void renderSetup(boolean connected, boolean ready, boolean installing, boolean failed, String status) {
        boolean installAction = connected && !ready && !installing;
        boolean retryAction = connected && ready && failed;
        if (!connected) status = ready ? "Konsole wird gestartet …" : "App wird verbunden …";
        else if (ready && !failed) status = "Konsole wird gestartet …";
        message.setText(status + (installAction ? "\n\nArch ARM64 wird einmalig geladen (ca. 791 MiB). Mindestens 5 GiB freien internen Speicher vorsehen.\n\nLange und stille Befehle bleiben aktiv. Lautstärke leiser = CTRL; Lautstärke lauter = Menü." : ""));
        action.setText(retryAction ? "Konsole erneut starten" : "Arch installieren / fortsetzen");
        action.setVisibility(installAction || retryAction ? View.VISIBLE : View.GONE);
        action.setEnabled(installAction || retryAction);
        cancel.setVisibility(connected && installing ? View.VISIBLE : View.GONE);
    }
    private void send(String text) { if (service != null && attached != null && attached.isRunning()) attached.write(text); terminal.requestFocus(); }
    private void arrow(int code) { if (service != null) terminal.handleKeyCode(code, 0); terminal.requestFocus(); }
    private void keyboard() {
        terminal.requestFocus();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }
    private void menu() {
        String[] options = {"Tastatur ein / aus", "CTRL-C", "Neue Sitzung", "PRoot-Kompatibilität", "CPU-Wachhaltung ein / aus", "Schrift kleiner", "Schrift größer", "Lizenzen", "Sitzung schließen", "Zusatztasten ein / aus", "Alle Sitzungen beenden"};
        new AlertDialog.Builder(this).setTitle("Arch Terminal").setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: keyboard(); break;
                case 1: send("\003"); break;
                case 2: if (service != null) service.startSession(); break;
                case 3:
                    boolean old = getSharedPreferences("terminal", MODE_PRIVATE).getBoolean("no_seccomp", false);
                    getSharedPreferences("terminal", MODE_PRIVATE).edit().putBoolean("no_seccomp", !old).apply();
                    Toast.makeText(this, "Kompatibilitätsmodus " + (!old ? "an" : "aus") + " · gilt ab der nächsten Sitzung", Toast.LENGTH_LONG).show(); break;
                case 4: if (service != null) { service.setWakeEnabled(!service.wakeEnabled()); Toast.makeText(this, "CPU-Wachhaltung " + (service.wakeEnabled() ? "an" : "aus"), Toast.LENGTH_SHORT).show(); } break;
                case 5: font(-1); break;
                case 6: font(1); break;
                case 7: licenses(); break;
                case 8: if (service != null) closeSession(service.selectedSession()); break;
                case 9: getPreferences(MODE_PRIVATE).edit().putBoolean("keys", !getPreferences(MODE_PRIVATE).getBoolean("keys", true)).apply(); render(); break;
                case 10: if (service != null) new AlertDialog.Builder(this).setMessage("Alle Sitzungen und ihre laufenden Prozesse beenden?")
                    .setNegativeButton("Zurück", null).setPositiveButton("Alle beenden", (d, w) -> { if (service != null) service.stopAllSessions(); }).show(); break;
            }
        }).show();
    }
    private void closeSession(SessionManager.Entry<TerminalSession> entry) {
        if (service == null || entry == null || entry.closing()) return;
        new AlertDialog.Builder(this).setMessage("Terminal " + entry.number + " und seine laufenden Prozesse beenden?")
            .setNegativeButton("Zurück", null).setPositiveButton("Schließen", (dialog, which) -> { if (service != null) service.closeSession(entry); }).show();
    }
    private void licenses() {
        try {
            StringBuilder content = new StringBuilder("Arch Terminal · GPL-3.0-or-later\n\nArch-Linux-Logo · Thayer Williams · CC-BY-NC-SA-3.0\nTermux terminal emulator/view · Apache-2.0\nPRoot · GPL-2.0-or-later\ntalloc · LGPL-3.0-or-later\nlibarchive · BSD and component terms\n\n");
            for (String name : getAssets().list("licenses")) {
                content.append("\n\n── ").append(name).append(" ──\n\n");
                try (InputStream input = getAssets().open("licenses/" + name)) {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = input.read(bytes)) > 0) buffer.write(bytes, 0, count);
                    content.append(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                }
            }
            TextView text = text(content.toString(), 12, Color.LTGRAY); text.setTextIsSelectable(true); text.setPadding(dp(16), dp(16), dp(16), dp(16));
            ScrollView scroll = new ScrollView(this); scroll.addView(text);
            new AlertDialog.Builder(this).setTitle("Lizenzen").setView(scroll).setPositiveButton("Schließen", null).show();
        } catch (Exception error) { Toast.makeText(this, error.toString(), Toast.LENGTH_LONG).show(); }
    }
    private void font(int delta) {
        textSize = Math.max(8, Math.min(32, textSize + delta));
        getPreferences(MODE_PRIVATE).edit().putInt("font", textSize).apply();
        terminal.setTextSize(sp(textSize));
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) { volumeControl = event.getAction() == KeyEvent.ACTION_DOWN; return true; }
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) { if (event.getAction() == KeyEvent.ACTION_UP) menu(); return true; }
        return super.dispatchKeyEvent(event);
    }
    private final TerminalViewClient viewClient = new TerminalViewClient() {
        public float onScale(float scale) { accumulatedScale *= scale; if (accumulatedScale > 1.15f) { font(1); accumulatedScale = 1; } else if (accumulatedScale < 0.85f) { font(-1); accumulatedScale = 1; } return 1; }
        public void onSingleTapUp(MotionEvent event) { if (attached != null) ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT); }
        public boolean shouldBackButtonBeMappedToEscape() { return false; }
        public boolean shouldEnforceCharBasedInput() { return true; }
        public boolean shouldUseCtrlSpaceWorkaround() { return true; }
        public boolean isTerminalViewSelected() { return true; }
        public void copyModeChanged(boolean mode) {}
        public boolean onKeyDown(int code, KeyEvent event, TerminalSession session) {
            if (code == KeyEvent.KEYCODE_ENTER && !session.isRunning() && service != null) { service.restartSession(); return true; }
            return false;
        }
        public boolean onKeyUp(int code, KeyEvent event) { return false; }
        public boolean onLongPress(MotionEvent event) { return false; }
        public boolean readControlKey() { return ctrl || volumeControl; }
        public boolean readAltKey() { return altDown; }
        public boolean readShiftKey() { return false; }
        public boolean readFnKey() { return false; }
        public boolean onCodePoint(int point, boolean down, TerminalSession session) {
            if ((point == 10 || point == 13) && !session.isRunning() && service != null) { service.restartSession(); return true; }
            return false;
        }
        public void onEmulatorSet() {}
        public void logError(String tag, String value) { android.util.Log.e(tag, value); }
        public void logWarn(String tag, String value) { android.util.Log.w(tag, value); }
        public void logInfo(String tag, String value) { android.util.Log.i(tag, value); }
        public void logDebug(String tag, String value) { android.util.Log.d(tag, value); }
        public void logVerbose(String tag, String value) { android.util.Log.v(tag, value); }
        public void logStackTraceWithMessage(String tag, String value, Exception error) { android.util.Log.e(tag, value, error); }
        public void logStackTrace(String tag, Exception error) { android.util.Log.e(tag, error.toString(), error); }
    };
    private Button addKey(LinearLayout row, String label, Runnable callback) {
        Button key = button(label); key.setTextSize(12); key.setMinWidth(dp(48)); key.setMinimumWidth(dp(48)); key.setPadding(dp(10), 0, dp(10), 0);
        key.setFocusable(false); key.setBackgroundColor(Color.TRANSPARENT); key.setOnClickListener(view -> callback.run()); row.addView(key, new LinearLayout.LayoutParams(-2, -1)); return key;
    }
    private Button button(String label) { Button button = new Button(this); button.setText(label); button.setTextColor(Color.LTGRAY); button.setAllCaps(false); return button; }
    private TextView text(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setTypeface(Typeface.MONOSPACE); return text; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int sp(int value) { return Math.round(value * getResources().getDisplayMetrics().scaledDensity); }
}
