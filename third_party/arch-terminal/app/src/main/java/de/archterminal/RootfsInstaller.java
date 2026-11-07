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
    interface Progress { void update(String text); }
    private final Context context;
    private final Progress progress;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ArchiveDownload downloader;

    RootfsInstaller(Context context, Progress progress) {
        this.context = context; this.progress = progress;
        downloader = new ArchiveDownload(cancelled, progress::update);
    }
    void cancel() {
        cancelled.set(true);
        Native.cancelExtraction();
        downloader.cancel();
    }
    private void check() throws IOException { if (cancelled.get()) throw new IOException("Installation abgebrochen; Download bleibt fortsetzbar."); }
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
        progress.update("Arch-Archiv wird geprüft …");
        String actual = sha256(archive);
        if (!actual.equals(lock.getString("sha256"))) {
            Files.deleteIfExists(archive.toPath());
            throw new IOException("Arch-Prüfsumme stimmt nicht. Download erneut starten; vorhandenes Arch bleibt erhalten.");
        }
        check();
        File staging = new File(context.getFilesDir(), "arch.installing");
        deleteTree(staging.toPath());
        Files.createDirectory(staging.toPath());
        progress.update("Arch wird entpackt … Das kann einige Minuten dauern.");
        Native.extract(archive.getAbsolutePath(), staging.getAbsolutePath());
        check();
        RootfsConfig.configure(staging);
        Files.write(new File(staging, ".arch-terminal-ready").toPath(), (lock.getString("sha256") + "\n").getBytes(StandardCharsets.UTF_8));
        check();
        File destination = new File(context.getFilesDir(), "arch");
        if (destination.exists()) throw new IOException("Ein Arch-Dateisystem ist bereits vorhanden; es wird nicht überschrieben.");
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
    private static byte[] readSmall(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) { out.write(buffer, 0, count); if (out.size() > 65536) throw new IOException("Manifest zu groß"); }
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
