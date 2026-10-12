package de.agentcodi.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.core.UiLanguage;
import de.agentcodi.freedom.AndroidFreedomSupport;

/** Voluntary statement of support, reachable from Settings without starting Codex. */
public final class AndroidFreedomActivity extends Activity {
    private UiTheme theme;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = new UiTheme(this);
        setContentView(buildContent());
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(theme.page);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(theme.dp(18), theme.dp(20), theme.dp(18), theme.dp(36));
        scroll.addView(page, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout card = theme.card();
        page.addView(card, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = theme.iconButton(
            R.drawable.ic_chat_back, getString(R.string.android_freedom_back)
        );
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)));
        TextView title = heading(R.string.android_freedom_title, 20);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        titleParams.setMarginStart(theme.dp(12));
        titleParams.setMarginEnd(theme.dp(8));
        topBar.addView(title, titleParams);
        topBar.addView(icon(R.drawable.ic_android_freedom_shield),
            new LinearLayout.LayoutParams(theme.dp(24), theme.dp(24)));
        card.addView(topBar);

        ImageView phone = icon(R.drawable.ic_android_freedom_phone);
        LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(
            theme.dp(48), theme.dp(48)
        );
        phoneParams.gravity = Gravity.CENTER_HORIZONTAL;
        phoneParams.topMargin = theme.dp(32);
        card.addView(phone, phoneParams);

        TextView name = heading(R.string.android_freedom_name, 30);
        name.setGravity(Gravity.CENTER);
        theme.addWithTopMargin(card, name, 16);

        TextView tagline = theme.text(
            getString(R.string.android_freedom_tagline), 17, theme.secondary
        );
        tagline.setGravity(Gravity.CENTER);
        tagline.setLineSpacing(0.0f, 1.25f);
        theme.addWithTopMargin(card, tagline, 12);

        View divider = new View(this);
        divider.setBackgroundColor(theme.border);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(1)
        );
        dividerParams.topMargin = theme.dp(28);
        card.addView(divider, dividerParams);

        theme.addWithTopMargin(card,
            theme.body(getString(R.string.android_freedom_statement)), 24);
        theme.addWithTopMargin(card,
            theme.body(getString(R.string.android_freedom_initiative)), 14);

        LinearLayout explanation = new LinearLayout(this);
        explanation.setOrientation(LinearLayout.VERTICAL);
        explanation.setPadding(theme.dp(16), theme.dp(16), theme.dp(16), theme.dp(16));
        explanation.setBackground(theme.background(
            theme.tintedSurface(theme.accent, theme.dark ? 0.12f : 0.06f),
            android.graphics.Color.TRANSPARENT, 14
        ));
        LinearLayout explanationHeading = new LinearLayout(this);
        explanationHeading.setGravity(Gravity.CENTER_VERTICAL);
        explanationHeading.addView(icon(R.drawable.ic_android_freedom_info),
            new LinearLayout.LayoutParams(theme.dp(22), theme.dp(22)));
        LinearLayout.LayoutParams explanationTitleParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        explanationTitleParams.setMarginStart(theme.dp(10));
        explanationHeading.addView(heading(R.string.android_freedom_why_title, 18),
            explanationTitleParams);
        explanation.addView(explanationHeading);
        theme.addWithTopMargin(explanation,
            theme.body(getString(R.string.android_freedom_why_body)), 12);
        theme.addWithTopMargin(card, explanation, 20);

        Button learnMore = theme.secondaryButton(getString(R.string.android_freedom_learn_more));
        android.graphics.drawable.Drawable externalLink = getDrawable(
            R.drawable.ic_android_freedom_external_link
        ).mutate();
        externalLink.setTint(theme.dark ? 0xFF99F6E4 : theme.accent);
        externalLink.setBounds(0, 0, theme.dp(20), theme.dp(20));
        learnMore.setCompoundDrawablesRelative(null, null, externalLink, null);
        learnMore.setCompoundDrawablePadding(theme.dp(10));
        learnMore.setPadding(theme.dp(18), theme.dp(10), theme.dp(18), theme.dp(10));
        learnMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openWebsite();
            }
        });
        theme.addWithTopMargin(card, learnMore, 18);

        TextView disclaimer = theme.text(
            getString(R.string.android_freedom_disclaimer), 13, theme.secondary
        );
        disclaimer.setGravity(Gravity.CENTER);
        disclaimer.setLineSpacing(0.0f, 1.25f);
        theme.addWithTopMargin(card, disclaimer, 18);
        return scroll;
    }

    private TextView heading(int stringResource, int sizeSp) {
        TextView view = theme.text(getString(stringResource), sizeSp, theme.primary);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setAccessibilityHeading(true);
        return view;
    }

    private ImageView icon(int drawableResource) {
        ImageView view = new ImageView(this);
        view.setImageResource(drawableResource);
        view.setColorFilter(theme.accent);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private void openWebsite() {
        UiLanguage language = UiLanguage.fromLanguageTag(
            getResources().getConfiguration().getLocales().get(0).toLanguageTag()
        );
        Intent browser = new Intent(Intent.ACTION_VIEW,
            Uri.parse(AndroidFreedomSupport.websiteForLanguage(language)));
        browser.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.android_freedom_website_unavailable,
                Toast.LENGTH_LONG).show();
        }
    }
}
