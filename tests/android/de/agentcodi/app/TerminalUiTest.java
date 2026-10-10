package de.agentcodi.app;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import de.agentcodi.core.CodexAppServerClient;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.JsonCodec;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.TerminalOutputBuffer;
import de.agentcodi.runtime.AgentRuntimeService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TerminalUiTest {
    private ActivityController<TerminalActivity> lifecycle;
    private TerminalActivity activity;
    private View root;
    private CodexSessionController session;
    private Object terminal;
    private RecordingTransport transport;
    private Object runtimeState;
    private RuntimeSnapshot previousRuntime;

    @Before
    public void create() throws Exception {
        transport = new RecordingTransport();
        session = new CodexSessionController(transport, "/terminal-ui-fixture", null,
            "/terminal-ui-fixture/libagentcodi-shell.so");
        set(session, "ready", true);
        CodexAppServerClient client = (CodexAppServerClient) get(session, "client");
        client.start();
        set(client, "initialized", true);
        terminal = get(session, "terminal");
        set(terminal, "running", true);
        set(terminal, "processId", "ui-terminal");
        Field controller = AgentRuntimeService.class.getDeclaredField("sessionController");
        controller.setAccessible(true);
        controller.set(null, session);
        Field state = AgentRuntimeService.class.getDeclaredField("STATE");
        state.setAccessible(true);
        runtimeState = state.get(null);
        previousRuntime = (RuntimeSnapshot) get(runtimeState, "snapshot");
        set(runtimeState, "snapshot", new RuntimeSnapshot(1L, RuntimePhase.READY,
            "", "", "", "/terminal-ui-fixture"));
        lifecycle = Robolectric.buildActivity(TerminalActivity.class).create();
        activity = lifecycle.get();
        assertTrue("terminal UI initialized", (Boolean) get(activity, "uiReady"));
        set(activity, "refreshActive", true);
        root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        lifecycle.visible();
        renderAndLayout();
    }

    @After
    public void close() throws Exception {
        if (lifecycle != null) {
            lifecycle.destroy();
        }
        Field controller = AgentRuntimeService.class.getDeclaredField("sessionController");
        controller.setAccessible(true);
        controller.set(null, null);
        if (runtimeState != null) {
            set(runtimeState, "snapshot", previousRuntime);
        }
        if (session != null) {
            session.close();
        }
    }

    @Test
    public void emptyButtonAndSoftKeyboardInputSendEnter() throws Exception {
        ((Button) get(activity, "sendButton")).performClick();
        drainControls();
        assertEquals("empty Send submits a newline", Collections.singletonList("\n"), transport.inputs);
        EditText input = (EditText) get(activity, "inputView");
        input.onEditorAction(EditorInfo.IME_ACTION_SEND);
        drainControls();
        assertEquals(Arrays.asList("\n", "\n"), transport.inputs);
    }

    @Test
    public void systemInsetsPreserveTheTerminalsContentPadding() {
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
            .setSystemWindowInsets(Insets.of(5, 24, 7, 18)).build());
        assertEquals("left inset includes the content margin", 19, root.getPaddingLeft());
        assertEquals("top inset includes the content margin", 42, root.getPaddingTop());
        assertEquals("right inset includes the content margin", 21, root.getPaddingRight());
        assertEquals("bottom inset includes the content margin", 30, root.getPaddingBottom());
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
            .setSystemWindowInsets(Insets.of(5, 24, 7, 18)).build());
        assertEquals("reapplying insets does not accumulate padding", 19, root.getPaddingLeft());
    }

    @Test
    public void hardwareEnterSubmitsExactlyOnceForEachKeyPress() throws Exception {
        EditText input = (EditText) get(activity, "inputView");
        input.requestFocus();
        input.setText("printf once");
        pressEnter(input);
        input.setText("");
        pressEnter(input);
        drainControls();
        assertEquals(Arrays.asList("printf once\n", "\n"), transport.inputs);
        assertEquals("", input.getText().toString());
    }

    @Test
    public void tabCompletesThePendingDraftBeforeSubmittingIt() throws Exception {
        EditText input = (EditText) get(activity, "inputView");
        input.setText("prin");
        ((Button) get(activity, "tabButton")).performClick();
        drainControls();
        assertEquals(Collections.singletonList("prin\t"), transport.inputs);
        assertEquals("draft moves to the PTY without executing it", "", input.getText().toString());
        ((Button) get(activity, "tabButton")).performClick();
        ((Button) get(activity, "sendButton")).performClick();
        drainControls();
        assertEquals(Arrays.asList("prin\t", "\t", "\n"), transport.inputs);
    }

    @Test
    public void outputUpdatesPreserveScrollbackAndResumeFollowingAtTheBottom() throws Exception {
        appendLines(100);
        renderAndLayout();
        ScrollView scroll = (ScrollView) get(activity, "outputScroll");
        assertTrue("history is taller than the viewport", scroll.getChildAt(0).getHeight() > scroll.getHeight());
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        assertTrue("history can be scrolled", scroll.getScrollY() > 0);
        scroll.scrollTo(0, 0);
        appendLines(1);
        renderAndLayout();
        screenshot("terminal-scrollback");
        assertEquals("reading history survives new output", 0, scroll.getScrollY());
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        int previousBottom = scroll.getScrollY();
        EditText input = (EditText) get(activity, "inputView");
        input.requestFocus();
        appendLines(1);
        renderAndLayout();
        assertTrue("following resumes at the bottom", scroll.getScrollY() > previousBottom);
        assertTrue("following output keeps composer focus", input.hasFocus());
    }

    @Test
    public void wideHistoryKeepsItsPhysicalRowsAndDoesNotChangePtyWidth() throws Exception {
        int visibleColumns = (Integer) call("terminalColumns");
        synchronized (get(terminal, "lock")) {
            TerminalOutputBuffer output = (TerminalOutputBuffer) get(terminal, "output");
            output.resize(2, 80);
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < 80; index++) {
                text.append((char) ('a' + index % 26));
            }
            text.append("\r\nnext\r\nprompt");
            output.append(text.toString().getBytes(StandardCharsets.UTF_8));
            set(terminal, "revision", (Long) get(terminal, "revision") + 1L);
        }
        renderAndLayout();
        TextView outputView = (TextView) get(activity, "outputView");
        screenshot("terminal-wide-history");
        assertEquals("stored physical rows do not wrap at Android word boundaries", 3,
            outputView.getLayout().getLineCount());
        assertEquals("PTY columns use the viewport width", visibleColumns, call("terminalColumns"));
        assertTrue("wide history can be read horizontally", outputView.getParent() instanceof HorizontalScrollView);
        HorizontalScrollView horizontal = (HorizontalScrollView) outputView.getParent();
        horizontal.scrollTo(outputView.getWidth(), 0);
        assertTrue("history wider than the viewport stays accessible", horizontal.getScrollX() > 0);
    }

    private static void pressEnter(EditText input) {
        input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    private void appendLines(int count) throws Exception {
        synchronized (get(terminal, "lock")) {
            TerminalOutputBuffer output = (TerminalOutputBuffer) get(terminal, "output");
            for (int index = 0; index < count; index++) {
                output.append("terminal output line\r\n".getBytes(StandardCharsets.UTF_8));
            }
            set(terminal, "revision", (Long) get(terminal, "revision") + 1L);
        }
    }

    private void drainControls() throws Exception {
        ExecutorService controls = (ExecutorService) get(terminal, "controlOperations");
        controls.submit(new Runnable() {
            @Override public void run() { }
        }).get(5L, TimeUnit.SECONDS);
    }

    private void renderAndLayout() throws Exception {
        call("render");
        root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 390, 844);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void screenshot(String name) throws IOException {
        String report = System.getProperty("agentcodi.chatUiReport", "");
        if (report.isEmpty()) {
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File image = new File(report, name + "-api" + android.os.Build.VERSION.SDK_INT + ".png");
        try (FileOutputStream output = new FileOutputStream(image)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    private Object call(String name) throws Exception {
        Method method = TerminalActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(activity);
    }

    private static Object get(Object instance, String name) throws Exception {
        return field(instance, name).get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        field(instance, name).set(instance, value);
    }

    private static Field field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class RecordingTransport implements CodexRpcTransport {
        private static final String CLOSED = "closed";
        private final LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<String>();
        private final List<String> inputs = Collections.synchronizedList(new ArrayList<String>());

        @Override public String readLine(int maximumBytes) throws IOException {
            try {
                String line = incoming.take();
                if (CLOSED.equals(line)) {
                    throw new IOException("Fixture closed");
                }
                return line;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
        }

        @Override public void writeLine(String line, int maximumBytes) {
            Map<String, Object> request = JsonCodec.parseObject(line);
            if ("command/exec/write".equals(request.get("method"))) {
                Map<String, Object> params = JsonCodec.requireObject(request.get("params"), "input");
                inputs.add(new String(Base64.getDecoder().decode((String) params.get("deltaBase64")),
                    StandardCharsets.UTF_8));
            }
            incoming.offer(JsonCodec.stringify(JsonCodec.object("id", request.get("id"),
                "result", JsonCodec.object())));
        }

        @Override public void writeBytes(byte[] line, int length, int maximumBytes) {
            try {
                writeLine(new String(line, 0, length, StandardCharsets.UTF_8), maximumBytes);
            } finally {
                Arrays.fill(line, (byte) 0);
            }
        }

        @Override public void close() {
            incoming.offer(CLOSED);
        }
    }
}
