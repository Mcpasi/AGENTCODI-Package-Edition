package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Bounded complete-file access for edits, using the existing no-follow source boundary. */
public final class WorkspaceFileEdit {
    private WorkspaceFileEdit() {
    }

    public static byte[] read(
        File workspace, String relativePath, int maximumBytes, WorkspaceFileAccess.Opener opener
    ) throws IOException {
        try (WorkspaceFileBoundary.OpenedFile opened = open(
            workspace, relativePath, maximumBytes, opener
        )) {
            return readAll(opened.source);
        }
    }

    public static void replace(
        File workspace, String relativePath, byte[] expected, byte[] replacement,
        int maximumBytes, WorkspaceFileAccess.Opener opener
    ) throws IOException {
        if (expected == null || replacement == null || replacement.length > maximumBytes
            || expected.length > maximumBytes) {
            throw new IllegalArgumentException("Editor byte limits are invalid");
        }
        try (WorkspaceFileBoundary.OpenedFile opened = open(
            workspace, relativePath, maximumBytes, opener
        )) {
            if (!Arrays.equals(expected, readAll(opened.source))) {
                throw new FileChangedException();
            }
            opened.source.replace(replacement);
        }
    }

    private static WorkspaceFileBoundary.OpenedFile open(
        File workspace, String relativePath, int maximumBytes, WorkspaceFileAccess.Opener opener
    ) throws IOException {
        String checked = WorkspaceFileBoundary.validateRelativePath(relativePath, 2048);
        if (!checked.equals(relativePath) || relativePath.endsWith("/")
            || relativePath.split("/", -1).length > 64) {
            throw new IOException("Editor path is unsafe");
        }
        return WorkspaceFileBoundary.openRegularFile(
            workspace, new File(workspace, checked).getAbsolutePath(), maximumBytes, opener
        );
    }

    private static byte[] readAll(WorkspaceFileAccess.Source source) throws IOException {
        byte[] bytes = new byte[(int) source.getByteCount()];
        int offset = 0;
        while (offset < bytes.length) {
            int count = source.read(bytes, offset, Math.min(64 * 1024, bytes.length - offset));
            if (count <= 0) {
                throw new IOException("Editor source changed during reading");
            }
            offset += count;
        }
        source.verifyUnchanged();
        return bytes;
    }

    public static final class FileChangedException extends IOException {
        public FileChangedException() { super("Editor source has changed"); }
    }
}
