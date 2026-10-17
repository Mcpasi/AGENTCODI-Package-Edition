package de.agentcodi.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.runtime.WorkspaceImageExporter;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceLayout;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class, qualifiers = "w320dp-h640dp-mdpi",
    shadows = ChatImageUiTest.WorkspaceAccessShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ChatImageUiTest {
    private ActivityController<Activity> lifecycle;
    private Activity activity;
    private LinearLayout root;
    private LinearLayout messages;
    private TranscriptScrollView scroll;
    private ChatTranscriptController transcript;
    private ExecutorService operations;
    private File workspace;

    @Before public void create() throws Exception {
        lifecycle = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = lifecycle.get();
        UiTheme theme = new UiTheme(activity);
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.page);
        scroll = new TranscriptScrollView(activity);
        messages = new LinearLayout(activity);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(12, 12, 12, 12);
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView newOutput = new TextView(activity);
        root.addView(newOutput);
        activity.setContentView(root);
        transcript = new ChatTranscriptController(activity, theme, new Handler(Looper.getMainLooper()),
            scroll, messages, newOutput);
        operations = (ExecutorService) get(transcript, "imageOperations");
        workspace = WorkspaceLayout.create(activity.getFilesDir()).getWorkspace();
    }

    @After public void close() {
        transcript.close();
        lifecycle.destroy();
    }

    @Test public void generatedImageIsVisibleWithCollapsedDetailsAndStillExports() throws Exception {
        File file = png("generated.png", 800, 400);
        CodexTranscriptItem item = image(file, "imageGeneration", false);
        render(item);
        drain();
        ImageView preview = preview();
        assertTrue("image appears without expanding tool details", preview.isShown());
        assertEquals(ImageView.ScaleType.FIT_CENTER, preview.getScaleType());
        assertTrue(preview.getWidth() > 0 && preview.getWidth() <= 320);
        assertEquals(preview.getWidth() / 2.0, preview.getHeight(), 1.0);
        assertEquals(activity.getString(R.string.image_preview_description, file.getName()),
            preview.getContentDescription());
        Object drawable = preview.getDrawable();
        render(item);
        drain();
        assertSame("unchanged transcript reuses the decoded image", drawable, preview.getDrawable());
        View export = (View) get(row(), "imageAction");
        assertTrue(export.isShown());
        export.performClick();
        drain();
        Intent picker = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.getAction());
        assertEquals("image/png", picker.getType());
        assertEquals(file.getName(), picker.getStringExtra(Intent.EXTRA_TITLE));
        assertSame("export does not remove the preview", drawable, preview.getDrawable());
        screenshot("chat-image-light");
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-night-mdpi")
    public void imageViewAlsoRendersInTheNarrowDarkChat() throws Exception {
        render(image(png("viewed.png", 300, 600), "imageView", false));
        drain();
        assertTrue(preview().isShown());
        assertTrue(preview().getHeight() <= 480);
        assertEquals(2.0, preview().getDrawable().getIntrinsicHeight()
            / (double) preview().getDrawable().getIntrinsicWidth(), 0.01);
        screenshot("chat-image-dark");
    }

    @Test public void samplesLargeImagesBeforeDecoding() throws Exception {
        File file = png("large.png", 4096, 2048);
        render(image(file, "imageGeneration", false));
        drain();
        Bitmap decoded = ((BitmapDrawable) preview().getDrawable()).getBitmap();
        assertEquals(1024, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test public void unavailablePreviewKeepsTheValidatedExportAction() throws Exception {
        File file = new File(workspace, "broken.jpg");
        Files.write(file.toPath(), new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0});
        render(image(file, "imageGeneration", false));
        drain();
        assertNull(preview().getDrawable());
        assertEquals(View.GONE, preview().getVisibility());
        View export = (View) get(row(), "imageAction");
        assertTrue(export.isEnabled());
        assertEquals(activity.getString(R.string.image_export), export.getContentDescription());
        assertTrue(((TextView) get(row(), "imageStatus")).getText().toString()
            .contains(activity.getString(R.string.image_preview_unavailable)));
    }

    @Test public void rejectsOutsidePathsAndSymlinksAndAllowsRetryAfterMissingFile() throws Exception {
        File outside = new File(activity.getFilesDir(), "outside.png");
        Files.copy(png("inside.png", 100, 100).toPath(), outside.toPath());
        File link = new File(workspace, "link.png");
        Files.createSymbolicLink(link.toPath(), outside.toPath());
        File missing = new File(workspace, "missing.png");
        for (File file : Arrays.asList(outside, link, missing)) {
            render(image(file, "imageGeneration", false));
            drain();
            assertNull(preview().getDrawable());
            assertEquals(activity.getString(R.string.image_path_recheck),
                ((View) get(row(), "imageAction")).getContentDescription());
            try {
                WorkspaceImageExporter.preview(activity, file.getAbsolutePath());
                fail("unsafe or missing image must not be decoded");
            } catch (IOException expected) {
                // The host opener exercises the production workspace boundary and PNG validation.
            }
        }
        png("missing.png", 100, 100);
        ((View) get(row(), "imageAction")).performClick();
        drain();
        assertTrue(preview().isShown());
    }

    @Test public void streamingAndRemovedPathsClearPreviewsAndIgnorePendingResults() throws Exception {
        File file = png("stream.png", 200, 100);
        render(image(file, "imageGeneration", true));
        drain();
        assertNull(preview().getDrawable());
        render(image(file, "imageGeneration", false));
        // Finish the background load, but replace the row before its main-thread callback.
        awaitWorker();
        CodexTranscriptItem withoutPath = CodexTranscriptItem.card("image", CodexTranscriptItem.Kind.TOOL,
            "imageGeneration", "Image generation", "", "", "completed", false);
        render(withoutPath);
        drain();
        assertNull(preview().getDrawable());
        render(image(file, "imageGeneration", false));
        drain();
        ImageView previous = preview();
        assertNotNull(previous.getDrawable());
        transcript.renderTranscript("other", Collections.<CodexTranscriptItem>emptyList());
        assertNull("switching threads releases the old image", previous.getDrawable());
        render(image(file, "imageGeneration", false));
        previous = preview();
        awaitWorker();
        transcript.close();
        ShadowLooper.idleMainLooper();
        assertNull("a completed load cannot repopulate a closed chat", previous.getDrawable());
    }

    @Test public void loadingImagePreservesTheReadingPositionAndFollowingEnd() throws Exception {
        List<CodexTranscriptItem> items = new ArrayList<CodexTranscriptItem>();
        for (int index = 0; index < 20; index++) {
            items.add(CodexTranscriptItem.message(new ChatMessage("m" + index, ChatMessage.Role.ASSISTANT,
                "Message " + index + "\nLine one\nLine two\nLine three", false)));
        }
        items.add(image(png("scroll.png", 600, 400), "imageGeneration", false));
        transcript.renderTranscript("images", items);
        layout();
        scroll.scrollTo(0, messages.getChildAt(5).getTop() + 10);
        int position = scroll.getScrollY();
        drain();
        assertEquals("async image layout preserves older messages", position, scroll.getScrollY());
        transcript.renderTranscript("new-thread", items);
        layout();
        drain();
        assertEquals(messages.getBottom() - scroll.getHeight(), scroll.getScrollY());
    }

    private void render(CodexTranscriptItem item) {
        transcript.renderTranscript("images", Collections.singletonList(item));
        layout();
    }

    private static CodexTranscriptItem image(File file, String type, boolean streaming) {
        return CodexTranscriptItem.card("image", CodexTranscriptItem.Kind.TOOL, type,
            "Image generation", "", "Technical details", streaming ? "inProgress" : "completed", streaming)
            .withReportedImagePath(file.getAbsolutePath());
    }

    private File png(String name, int width, int height) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(20, 120, 170));
        File file = new File(workspace, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private Object row() throws Exception {
        return ((List<?>) get(transcript, "renderedTranscriptRows")).get(0);
    }

    private ImageView preview() throws Exception {
        return (ImageView) get(row(), "imagePreview");
    }

    private void awaitWorker() throws Exception {
        operations.submit(new Runnable() { @Override public void run() {} }).get(20, TimeUnit.SECONDS);
    }

    private void drain() throws Exception {
        awaitWorker();
        ShadowLooper.idleMainLooper();
        layout();
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 640);
        scroll.getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private void screenshot(String name) throws IOException {
        String directory = System.getProperty("agentcodi.chatUiReport", "");
        if (directory.isEmpty()) {
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory,
                name + "-api" + android.os.Build.VERSION.SDK_INT + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            bitmap.recycle();
        }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Replace only JNI file opening; retain the real snapshot, validation, decoder and UI. */
    @Implements(className = "de.agentcodi.runtime.NativeWorkspaceFileAccess", isInAndroidSdk = false)
    public static final class WorkspaceAccessShadow {
        @Implementation protected static WorkspaceFileAccess.Opener opener() {
            return WorkspaceFileAccess.secureNioOpener();
        }
    }
}
