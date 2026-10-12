package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.widget.Toast;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.editor.WorkspaceEditorSession;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Editor lifecycle, unsaved-change navigation and asynchronous persistence. */
final class WorkspaceEditorController {
    interface Saver {
        WorkspaceEditorDocument save(WorkspaceEditorDocument document, String text) throws IOException;
    }

    private final Activity activity;
    private final WorkspaceEditorView view;
    private final Executor operations;
    private final Handler handler;
    private final Saver saver;
    private final Runnable stateChanged;
    private WorkspaceEditorSession session;
    private AlertDialog leaveDialog;
    private boolean saving;
    private boolean externalBusy;
    private boolean destroyed;
    private int failure;

    WorkspaceEditorController(Activity activity, WorkspaceEditorView view, Executor operations,
        Handler handler, Saver saver, Runnable stateChanged, final Runnable export) {
        this.activity = activity;
        this.view = view;
        this.operations = operations;
        this.handler = handler;
        this.saver = saver;
        this.stateChanged = stateChanged;
        view.setActions(new WorkspaceEditorView.Actions() {
            @Override public void onTextChanged(String text) {
                if (session != null) {
                    session.setText(text);
                    failure = 0;
                    renderState();
                }
            }
            @Override public void onSave() { save(null); }
            @Override public void onExport() {
                if (session != null && !session.isDirty() && !saving && !externalBusy) {
                    export.run();
                }
            }
        });
    }

    boolean isOpen() { return session != null; }
    boolean isSaving() { return saving; }

    void open(WorkspaceEditorDocument document) {
        close();
        session = new WorkspaceEditorSession(document);
        view.bind(document);
        renderState();
    }

    void close() {
        if (leaveDialog != null) { leaveDialog.dismiss(); leaveDialog = null; }
        session = null;
        failure = 0;
        view.clear();
    }

    void destroy() { destroyed = true; close(); }

    void setExternalBusy(boolean busy) { externalBusy = busy; renderState(); }

    void requestLeave(final Runnable continuation) {
        if (saving || externalBusy || destroyed) { return; }
        if (session == null || !session.isDirty()) {
            continuation.run();
            return;
        }
        if (leaveDialog != null && leaveDialog.isShowing()) { return; }
        leaveDialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.editor_unsaved_title)
            .setMessage(R.string.editor_unsaved_message)
            .setPositiveButton(R.string.editor_save_and_continue, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { save(continuation); }
            })
            .setNegativeButton(R.string.editor_discard, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { continuation.run(); }
            })
            .setNeutralButton(android.R.string.cancel, null)
            .show();
    }

    private void save(final Runnable afterSave) {
        if (session == null || saving || externalBusy || !session.isDirty() || destroyed) { return; }
        final WorkspaceEditorSession selected = session;
        final String text = selected.getText();
        saving = true;
        failure = 0;
        renderState();
        stateChanged.run();
        try {
            operations.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final WorkspaceEditorDocument saved = saver.save(selected.getDocument(), text);
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (destroyed || selected != session) { return; }
                                selected.acceptSaved(saved);
                                saving = false;
                                renderState();
                                stateChanged.run();
                                Toast.makeText(activity, R.string.editor_saved, Toast.LENGTH_SHORT).show();
                                if (afterSave != null && !selected.isDirty()) { afterSave.run(); }
                            }
                        });
                    } catch (IOException | RuntimeException error) {
                        final int message = errorMessage(error);
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (destroyed || selected != session) { return; }
                                saveFailed(message);
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            saveFailed(R.string.editor_save_failed);
        }
    }

    private void saveFailed(int message) {
        saving = false;
        failure = message;
        renderState();
        stateChanged.run();
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static int errorMessage(Throwable error) {
        if (error instanceof WorkspaceEditorException) {
            WorkspaceEditorException.Reason reason = ((WorkspaceEditorException) error).getReason();
            if (reason == WorkspaceEditorException.Reason.FILE_CHANGED) { return R.string.editor_conflict; }
            if (reason == WorkspaceEditorException.Reason.TOO_LARGE) { return R.string.editor_too_large; }
            if (reason == WorkspaceEditorException.Reason.UNSUPPORTED_TEXT) { return R.string.editor_invalid_text; }
        }
        return R.string.editor_save_failed;
    }

    private void renderState() {
        if (session == null) { return; }
        int status = saving ? R.string.editor_saving : externalBusy ? R.string.editor_working
            : failure != 0 ? failure : session.isDirty() ? R.string.editor_unsaved : R.string.editor_saved;
        view.showState(status, failure != 0, session.isDirty(), saving || externalBusy);
    }
}
