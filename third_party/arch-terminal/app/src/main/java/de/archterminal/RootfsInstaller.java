package de.archterminal;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** A failed install leaves a resumable archive and never replaces a working rootfs. */
final class RootfsInstaller {
    interface Progress { void update(int resource, Object... arguments); }
    private final Context context;
    private final Progress progress;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ArchiveDownload downloader;

    RootfsInstaller(Context context, Progress progress) {
        this.context = context; this.progress = progress;
        downloader = new ArchiveDownload(cancelled, (downloaded, expected) -> progress.update(R.string.download_progress,
            downloaded / 1048576.0, expected / 1048576.0, 100.0 * downloaded / expected), () -> AppLanguage.get(context));
    }
    void cancel() {
        cancelled.set(true);
        Native.cancelExtraction();
        downloader.cancel();
    }
    private String text(int resource) { return AppLanguage.text(context, resource); }
    private void check() throws IOException { if (cancelled.get()) throw new IOException(text(R.string.installation_cancelled)); }
    static boolean installed(Context context) { return new File(context.getFilesDir(), "arch/.arch-terminal-ready").isFile(); }

    void install() throws Exception {
        check();
        Native.resetExtractionCancellation();
        check();
        JSONObject lock;
        try (InputStream input = context.getAssets().open("rootfs.lock.json")) {
            lock = new JSONObject(new String(readSmall(input), StandardCharsets.UTF_8));
        }
        File archive = new File(context.getFilesDir(), "arch-rootfs.tar.gz.part");
        long expected = lock.getLong("size");
        if (archive.length() != expected) downloader.download(lock.getString("url"), archive, expected);
        check();
        progress.update(R.string.checking_archive);
        String actual = sha256(archive);
        if (!actual.equals(lock.getString("sha256"))) {
            Files.deleteIfExists(archive.toPath());
            throw new IOException(text(R.string.checksum_failed));
        }
        check();
        File staging = new File(context.getFilesDir(), "arch.installing");
        deleteTree(staging.toPath());
        Files.createDirectory(staging.toPath());
        progress.update(R.string.extracting_archive);
        Native.extract(archive.getAbsolutePath(), staging.getAbsolutePath());
        check();
        RootfsConfig.configure(staging);
        Files.write(new File(staging, ".arch-terminal-ready").toPath(), (lock.getString("sha256") + "\n").getBytes(StandardCharsets.UTF_8));
        check();
        File destination = new File(context.getFilesDir(), "arch");
        if (destination.exists()) throw new IOException(text(R.string.rootfs_exists));
        Files.move(staging.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(archive.toPath());
    }
    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) { check(); digest.update(buffer, 0, count); }
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private byte[] readSmall(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) { out.write(buffer, 0, count); if (out.size() > 65536) throw new IOException(text(R.string.manifest_too_large)); }
        return out.toByteArray();
    }
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                // Archives contain read-only directories. A failed staging install must remain removable.
                if (!directory.toFile().setWritable(true, true)) throw new IOException("Installationsverzeichnis nicht löschbar: " + directory);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
