package de.archterminal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Resumable transfer, independent of Android so interruption paths can be tested on the JVM. */
final class ArchiveDownload {
    interface Progress { void update(long downloaded, long expected); }
    private final AtomicBoolean cancelled;
    private final Progress progress;
    private final Supplier<TerminalLanguage> language;
    private volatile HttpURLConnection active;
    ArchiveDownload(AtomicBoolean cancelled, Progress progress) { this(cancelled, progress, () -> TerminalLanguage.GERMAN); }
    ArchiveDownload(AtomicBoolean cancelled, Progress progress, Supplier<TerminalLanguage> language) {
        this.cancelled = cancelled; this.progress = progress; this.language = language;
    }
    private String text(String german, String english, Object... arguments) { return language.get().text(german, english, arguments); }
    void cancel() {
        cancelled.set(true);
        HttpURLConnection connection = active;
        if (connection != null) new Thread(connection::disconnect, "arch-download-cancel").start();
    }
    private void check() throws IOException {
        if (cancelled.get()) throw new IOException(text("Installation abgebrochen; Download bleibt fortsetzbar.", "Installation cancelled; the download can be resumed."));
    }
    void download(String url, File file, long expected) throws IOException {
        check();
        long offset = file.length();
        if (offset == expected) return;
        if (offset > expected) { Files.delete(file.toPath()); offset = 0; }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        active = connection;
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(0);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            check();
            int status = connection.getResponseCode();
            if (status == 200) offset = 0;
            else if (status != 206) throw new IOException(text("Download HTTP %d; erneut versuchen.", "Download HTTP %d; retry.", status));
            String range = connection.getHeaderField("Content-Range");
            if (status == 206 && (range == null || !range.startsWith("bytes " + offset + "-")))
                throw new IOException(text("Spiegelserver liefert einen falschen Downloadabschnitt.", "Mirror returned an incorrect download range."));
            long total = offset;
            long last = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file, offset > 0)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    check();
                    total += count;
                    if (total > expected) throw new IOException(text("Archiv ist größer als die gepinnte Version.", "Archive exceeds the pinned size."));
                    output.write(buffer, 0, count);
                    long now = System.nanoTime();
                    if (now - last > 500000000L) {
                        progress.update(total, expected);
                        last = now;
                    }
                }
                output.getFD().sync();
            }
            check();
            if (total != expected) throw new IOException(text("Download unterbrochen; mit Erneut versuchen fortsetzen.", "Download interrupted; choose Retry to resume."));
        } finally { connection.disconnect(); active = null; }
    }
}
