package de.agentcodi.editor;

import java.io.IOException;

/** Storage seam for local editing; implementations enforce the workspace boundary. */
public interface WorkspaceEditorStore {
    byte[] read(String relativePath) throws IOException;

    /** Replace the entire file only if it still matches the opened bytes. */
    void replace(String relativePath, byte[] expected, byte[] replacement) throws IOException;
}
