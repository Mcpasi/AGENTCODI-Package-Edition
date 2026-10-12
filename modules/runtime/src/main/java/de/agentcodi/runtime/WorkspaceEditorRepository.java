package de.agentcodi.runtime;

import android.content.Context;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.editor.WorkspaceEditorLimits;
import de.agentcodi.editor.WorkspaceEditorStore;
import de.agentcodi.editor.client.WorkspaceFileEditor;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceFileEdit;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.File;
import java.io.IOException;

/** Local editor facade. It exposes only the workspace, never installed package roots. */
public final class WorkspaceEditorRepository {
    private final WorkspaceFileEditor editor;

    private WorkspaceEditorRepository(WorkspaceEditorStore store) {
        editor = new WorkspaceFileEditor(store);
    }

    public static WorkspaceEditorRepository create(Context context) throws IOException {
        final File workspace = WorkspaceLayout.create(context.getFilesDir()).getWorkspace();
        final WorkspaceFileAccess.Opener opener = NativeWorkspaceFileAccess.opener();
        return new WorkspaceEditorRepository(new WorkspaceEditorStore() {
            @Override
            public byte[] read(String relativePath) throws IOException {
                return WorkspaceFileEdit.read(
                    workspace, relativePath, WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, opener
                );
            }

            @Override
            public void replace(String relativePath, byte[] expected, byte[] replacement)
                throws IOException {
                try {
                    WorkspaceFileEdit.replace(workspace, relativePath, expected, replacement,
                        WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, opener);
                } catch (WorkspaceFileEdit.FileChangedException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_CHANGED);
                }
            }
        });
    }

    public WorkspaceEditorDocument open(String relativePath) throws IOException {
        return editor.open(relativePath);
    }

    public WorkspaceEditorDocument save(WorkspaceEditorDocument document, String text)
        throws IOException {
        return editor.save(document, text);
    }
}
