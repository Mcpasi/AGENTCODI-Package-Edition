package de.agentcodi.app;

import android.content.Context;
import android.widget.EditText;

/** Reports scrolling and cursor movement to decorations without owning editing state. */
final class WorkspaceCodeInput extends EditText {
    private Runnable viewportChanged;

    WorkspaceCodeInput(Context context) { super(context); }
    void setViewportChanged(Runnable callback) { viewportChanged = callback; }

    private void notifyViewportChanged() {
        if (viewportChanged != null) { viewportChanged.run(); }
    }

    @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        notifyViewportChanged();
    }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        notifyViewportChanged();
    }
}
