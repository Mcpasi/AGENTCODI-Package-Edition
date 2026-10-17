package de.agentcodi.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import de.agentcodi.storage.WorkspaceExportTransaction;
import de.agentcodi.storage.WorkspaceImageFile;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public final class WorkspaceImageExporter {
    public static final long MAXIMUM_IMAGE_BYTES = 64L * 1024L * 1024L;
    private static final long MAXIMUM_PREVIEW_BYTES = 16L * 1024L * 1024L;
    private static final int MAXIMUM_IMAGE_EDGE = 16384;
    private static final int PREVIEW_EDGE = 1024;

    private WorkspaceImageExporter() {
    }

    public static ImageExport inspect(Context context, String sourcePath) throws IOException {
        WorkspaceImageFile image = inspectImage(context, sourcePath);
        return new ImageExport(
            image.getDisplayName(),
            image.getMimeType(),
            image.getByteCount()
        );
    }

    /** Decode a bounded snapshot through the same file boundary used for export. */
    public static Bitmap preview(Context context, String sourcePath) throws IOException {
        WorkspaceLayout layout = layout(context);
        PreviewBytes snapshot = new PreviewBytes();
        Bitmap bitmap = null;
        try {
            WorkspaceImageFile.copyTo(
                layout.getWorkspace(), sourcePath, MAXIMUM_PREVIEW_BYTES,
                snapshot, NativeWorkspaceFileAccess.opener()
            );
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(snapshot.bytes(), 0, snapshot.size(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > MAXIMUM_IMAGE_EDGE || bounds.outHeight > MAXIMUM_IMAGE_EDGE) {
                throw new IOException("Workspace image dimensions are outside the preview limit");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while ((Math.max(bounds.outWidth, bounds.outHeight) + options.inSampleSize - 1)
                / options.inSampleSize > PREVIEW_EDGE) {
                options.inSampleSize *= 2;
            }
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(snapshot.bytes(), 0, snapshot.size(), options);
            if (bitmap == null || bitmap.getWidth() > PREVIEW_EDGE || bitmap.getHeight() > PREVIEW_EDGE) {
                throw new IOException("Workspace image could not be decoded safely");
            }
            Bitmap result = bitmap;
            bitmap = null;
            return result;
        } finally {
            snapshot.clear();
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static final class PreviewBytes extends ByteArrayOutputStream {
        byte[] bytes() {
            return buf;
        }

        void clear() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    public static ImageExport export(
        final Context context,
        final String sourcePath,
        Uri destination
    ) throws IOException {
        if (destination == null || !"content".equalsIgnoreCase(destination.getScheme())) {
            throw new IllegalArgumentException("destination must be an Android content URI");
        }
        requireContext(context);
        WorkspaceImageFile image = WorkspaceExportTransaction.execute(
            new AndroidDocumentExportDestination(
                context.getContentResolver(),
                destination
            ),
            new WorkspaceExportTransaction.Preparation<WorkspaceLayout>() {
                @Override
                public WorkspaceLayout prepare() throws IOException {
                    WorkspaceLayout preparedLayout = layout(context);
                    WorkspaceImageFile.inspect(
                        preparedLayout.getWorkspace(),
                        sourcePath,
                        MAXIMUM_IMAGE_BYTES,
                        NativeWorkspaceFileAccess.opener()
                    );
                    return preparedLayout;
                }
            },
            new WorkspaceExportTransaction.Writer<
                WorkspaceLayout,
                WorkspaceImageFile
            >() {
                @Override
                public WorkspaceImageFile write(
                    WorkspaceLayout preparedLayout,
                    OutputStream destinationStream
                ) throws IOException {
                    return WorkspaceImageFile.copyTo(
                        preparedLayout.getWorkspace(),
                        sourcePath,
                        MAXIMUM_IMAGE_BYTES,
                        destinationStream,
                        NativeWorkspaceFileAccess.opener()
                    );
                }
            }
        );
        return new ImageExport(
            image.getDisplayName(),
            image.getMimeType(),
            image.getByteCount()
        );
    }

    private static WorkspaceImageFile inspectImage(Context context, String sourcePath)
        throws IOException {
        WorkspaceLayout layout = layout(context);
        return WorkspaceImageFile.inspect(
            layout.getWorkspace(),
            sourcePath,
            MAXIMUM_IMAGE_BYTES,
            NativeWorkspaceFileAccess.opener()
        );
    }

    private static WorkspaceLayout layout(Context context) throws IOException {
        requireContext(context);
        return WorkspaceLayout.create(context.getFilesDir());
    }

    private static void requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
    }

    public static final class ImageExport {
        private final String displayName;
        private final String mimeType;
        private final long byteCount;

        private ImageExport(String displayName, String mimeType, long byteCount) {
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.byteCount = byteCount;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getByteCount() {
            return byteCount;
        }
    }
}
