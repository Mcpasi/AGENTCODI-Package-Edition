package de.agentcodi.app;

import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import de.agentcodi.core.UiLanguage;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class, qualifiers = "en-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AndroidFreedomUiTest {
    private ActivityController<AndroidFreedomActivity> lifecycle;
    private AndroidFreedomActivity activity;
    private ScrollView scroll;

    @After public void close() {
        if (lifecycle != null) {
            lifecycle.destroy();
        }
    }

    @Test public void settingsOpensTheInternalPageWithoutLaunchingABrowser() {
        selectLanguage(UiLanguage.ENGLISH);
        ActivityController<SettingsActivity> settings =
            Robolectric.buildActivity(SettingsActivity.class).create();
        try {
            Button open = (Button) findText(settings.get().findViewById(android.R.id.content),
                settings.get().getString(R.string.android_freedom_settings_open));
            assertNotNull("Settings contains the support entry", open);
            open.performClick();
            Intent destination = Shadows.shadowOf(settings.get()).getNextStartedActivity();
            assertEquals(AndroidFreedomActivity.class.getName(), destination.getComponent().getClassName());
            ActivityInfo info = settings.get().getPackageManager().getActivityInfo(
                destination.getComponent(), 0);
            assertFalse("support page is internal", info.exported);
            assertNull(Shadows.shadowOf(settings.get()).getNextStartedActivity());
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            throw new AssertionError("Support Activity must be registered", missing);
        } finally {
            settings.destroy();
        }
    }

    @Test public void translatesThePageAndOpensOfficialLinksInAllThreeLanguages() throws Exception {
        UiLanguage[] languages = {
            UiLanguage.ENGLISH, UiLanguage.GERMAN, UiLanguage.SIMPLIFIED_CHINESE
        };
        String[] titles = {"About Android freedom", "Über Android-Freiheit", "关于 Android 自由"};
        String[] urls = {
            "https://keepandroidopen.org/", "https://keepandroidopen.org/de/",
            "https://keepandroidopen.org/zh-CN/"
        };
        for (int index = 0; index < languages.length; index++) {
            selectLanguage(languages[index]);
            open();
            assertNotNull(findText(scroll, titles[index]));
            assertNotNull(findText(scroll, activity.getString(R.string.android_freedom_statement)));
            assertNotNull(findText(scroll, activity.getString(R.string.android_freedom_initiative)));
            assertNotNull(findText(scroll, activity.getString(R.string.android_freedom_why_body)));
            assertNotNull(findText(scroll, activity.getString(R.string.android_freedom_disclaimer)));
            assertNull("Opening the page is local", Shadows.shadowOf(activity).getNextStartedActivity());
            assertNull("Opening the page does not start a runtime service",
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            screenshot("android-freedom-" + languages[index].getLanguageTag());
            learnMore().performClick();
            Intent browser = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, browser.getAction());
            assertTrue(browser.hasCategory(Intent.CATEGORY_BROWSABLE));
            assertEquals(urls[index], browser.getDataString());
            assertNull("Use the user's browser", browser.getComponent());
            lifecycle.destroy();
            lifecycle = null;
        }
    }

    @Test public void missingBrowserShowsALocalizedMessageAndKeepsThePageOpen() {
        selectLanguage(UiLanguage.GERMAN);
        open();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        learnMore().performClick();
        assertEquals(activity.getString(R.string.android_freedom_website_unavailable),
            ShadowToast.getTextOfLatestToast());
        assertFalse(activity.isFinishing());
    }

    @Test public void toolbarBackReturnsToSettings() {
        open();
        ImageButton back = findBack(scroll);
        assertNotNull(back);
        assertEquals(activity.getString(R.string.android_freedom_back), back.getContentDescription());
        back.performClick();
        assertTrue(activity.isFinishing());
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-night-mdpi")
    public void narrowDarkPhoneKeepsLargeTextAndBothControlsAccessible() throws Exception {
        selectLanguage(UiLanguage.GERMAN);
        RuntimeEnvironment.setFontScale(1.5f);
        open();
        layout(320, 640);
        assertTrue(new UiTheme(activity).dark);
        assertTrue("Content scrolls at large font sizes", scroll.getChildAt(0).getHeight() > scroll.getHeight());
        assertTextFits(scroll);
        assertTrue(findBack(scroll).getHeight() >= 48);
        assertTrue(learnMore().getHeight() >= 48);
        assertTrue(learnMore().getWidth() <= 320);
        Rect button = new Rect();
        learnMore().getDrawingRect(button);
        scroll.offsetDescendantRectToMyCoords(learnMore(), button);
        scroll.scrollTo(0, button.bottom - scroll.getHeight() + 18);
        assertTrue("Website button can be scrolled into view", learnMore().getGlobalVisibleRect(button));
        assertEquals(learnMore().getHeight(), button.height());
        screenshot("android-freedom-de-narrow-large-text-dark");
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-night-mdpi")
    public void darkThemeAndRecreationPreserveTheSelectedLanguage() throws Exception {
        selectLanguage(UiLanguage.SIMPLIFIED_CHINESE);
        open();
        assertTrue(new UiTheme(activity).dark);
        lifecycle.recreate();
        activity = lifecycle.get();
        scroll = (ScrollView) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        layout(390, 844);
        assertNotNull(findText(scroll, "关于 Android 自由"));
        assertNotNull(findText(scroll, "了解更多"));
        screenshot("android-freedom-zh-Hans-dark");
    }

    private void open() {
        lifecycle = Robolectric.buildActivity(AndroidFreedomActivity.class).setup().visible();
        activity = lifecycle.get();
        scroll = (ScrollView) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        layout(390, 844);
    }

    private void layout(int width, int height) {
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, width, height);
    }

    private Button learnMore() {
        return (Button) findText(scroll, activity.getString(R.string.android_freedom_learn_more));
    }

    private static void selectLanguage(UiLanguage language) {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(UiLanguage.PREFERENCE_FILE, Context.MODE_PRIVATE).edit()
            .putString(UiLanguage.PREFERENCE_KEY, language.getPreferenceValue()).commit();
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager.class).setApplicationLocales(
                LocaleList.forLanguageTags(language.getLanguageTag()));
        }
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int index = 0; index < parent.getChildCount(); index++) {
                TextView found = findText(parent.getChildAt(index), text);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private static ImageButton findBack(View view) {
        if (view instanceof ImageButton) { return (ImageButton) view; }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int index = 0; index < parent.getChildCount(); index++) {
                ImageButton found = findBack(parent.getChildAt(index));
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private static void assertTextFits(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull(text.getLayout());
            for (int line = 0; line < text.getLineCount(); line++) {
                assertEquals("Text must remain readable: " + text.getText(),
                    0, text.getLayout().getEllipsisCount(line));
                // Visible glyphs exclude the trailing spaces that getLineWidth includes.
                assertTrue("Text must wrap within its bounds: " + text.getText(),
                    text.getLayout().getLineMax(line) <= text.getWidth()
                        - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int index = 0; index < parent.getChildCount(); index++) {
                assertTextFits(parent.getChildAt(index));
            }
        }
    }

    private void screenshot(String name) throws Exception {
        String destination = System.getProperty("agentcodi.chatUiReport");
        if (destination == null || destination.isEmpty()) { return; }
        View page = scroll.getChildAt(0);
        Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(new UiTheme(activity).page);
        page.draw(canvas);
        File image = new File(destination, name + "-api" + Build.VERSION.SDK_INT + ".png");
        try (FileOutputStream output = new FileOutputStream(image)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
