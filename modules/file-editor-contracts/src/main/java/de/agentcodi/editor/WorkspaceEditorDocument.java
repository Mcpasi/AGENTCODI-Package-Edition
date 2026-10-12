package de.agentcodi.editor;

/** Immutable saved revision. Text excludes an optional UTF-8 byte order mark. */
public final class WorkspaceEditorDocument {
    private final String relativePath;
    private final String text;
    private final byte[] savedBytes;
    private final boolean byteOrderMark;

    public WorkspaceEditorDocument(
        String relativePath, String text, byte[] savedBytes, boolean byteOrderMark
    ) {
        if (relativePath == null || relativePath.isEmpty() || text == null
            || savedBytes == null || savedBytes.length > WorkspaceEditorLimits.MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException("Editor document is invalid");
        }
        this.relativePath = relativePath;
        this.text = text;
        this.savedBytes = savedBytes.clone();
        this.byteOrderMark = byteOrderMark;
    }

    public String getRelativePath() { return relativePath; }
    public String getText() { return text; }
    public byte[] getSavedBytes() { return savedBytes.clone(); }
    public boolean hasByteOrderMark() { return byteOrderMark; }
}
