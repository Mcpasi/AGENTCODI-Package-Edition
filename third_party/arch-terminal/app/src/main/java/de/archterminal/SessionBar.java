package de.archterminal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import com.termux.terminal.TerminalSession;
import java.util.ArrayList;
import java.util.List;

/** Session navigation stays visible independently of the optional extra-key row. */
@SuppressLint("ViewConstructor") // Created in Java with the host's session actions, never inflated from XML.
final class SessionBar extends LinearLayout {
    interface Listener {
        void select(SessionManager.Entry<TerminalSession> entry);
        void close(SessionManager.Entry<TerminalSession> entry);
        void create();
    }

    private final HorizontalScrollView scroll;
    private final LinearLayout tabs;
    private final Button add;
    private final Listener listener;
    private List<SessionManager.Entry<TerminalSession>> rendered = new ArrayList<>();
    private SessionManager.Entry<TerminalSession> previousSelection;

    SessionBar(Context context, Listener listener) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(Color.rgb(17, 24, 32));
        scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(context);
        scroll.addView(tabs);
        addView(scroll, new LayoutParams(0, -1, 1));
        add = button("+");
        add.setContentDescription(context.getString(R.string.new_session));
        add.setOnClickListener(view -> listener.create());
        addView(add, new LayoutParams(dp(48), -1));
        this.listener = listener;
    }

    void render(List<SessionManager.Entry<TerminalSession>> entries, SessionManager.Entry<TerminalSession> selected, boolean canCreate) {
        if (!rendered.equals(entries)) {
            rendered = new ArrayList<>(entries);
            tabs.removeAllViews();
            for (SessionManager.Entry<TerminalSession> entry : entries) {
                Button tab = button("");
                tab.setMaxWidth(dp(240));
                tab.setSingleLine(true);
                tab.setEllipsize(TextUtils.TruncateAt.END);
                tab.setOnClickListener(view -> listener.select(entry));
                tab.setOnLongClickListener(view -> { listener.close(entry); return true; });
                tabs.addView(tab, new LayoutParams(-2, -1));
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            SessionManager.Entry<TerminalSession> entry = entries.get(index);
            Button tab = (Button) tabs.getChildAt(index);
            String title = entry.terminal.getTitle();
            String label = "Terminal " + entry.number;
            if (title != null && !title.trim().isEmpty()) label += " · " + title;
            if (entry.closing()) label += " · " + getContext().getString(R.string.session_closing);
            else if (!entry.terminal.isRunning()) label += " · " + getContext().getString(R.string.session_ended);
            if (!TextUtils.equals(tab.getText(), label)) tab.setText(label);
            tab.setContentDescription(label + ". Zum Schließen lange drücken.");
            tab.setSelected(entry == selected);
            tab.setTextColor(entry == selected ? Color.CYAN : Color.LTGRAY);
            if (entry == selected && previousSelection != selected) {
                // Layout may still reflect the old tab count when a terminal has just been added.
                tab.post(() -> scroll.smoothScrollTo(tab.getLeft(), 0));
            }
        }
        previousSelection = selected;
        add.setEnabled(canCreate);
    }

    private Button button(String label) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setFocusable(false);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextColor(Color.LTGRAY);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
