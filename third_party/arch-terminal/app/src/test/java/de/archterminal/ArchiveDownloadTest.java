package de.archterminal;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ArchiveDownloadTest {
    private static final class Server implements AutoCloseable {
        final ServerSocket listener;
        final Thread thread;
        volatile String request = "";
        volatile Throwable failure;
        Server(String response) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000); // Test harness only.
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) request += line + "\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Throwable error) { failure = error; }
            }, "download-test-server");
            thread.start();
        }
        String url() { return "http://127.0.0.1:" + listener.getLocalPort() + "/rootfs.tar.gz"; }
        public void close() throws Exception {
            thread.join(2000);
            listener.close();
            assertFalse("test server hung", thread.isAlive());
            if (failure != null) throw new AssertionError(failure);
        }
    }
    private ArchiveDownload downloader() { return new ArchiveDownload(new AtomicBoolean(), (downloaded, expected) -> {}); }
    private File file(String content) throws Exception {
        File file = Files.createTempFile("arch-transfer-test", ".part").toFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.US_ASCII));
        file.deleteOnExit();
        return file;
    }
    private String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII); }

    @Test public void resumesExactlyAtExistingPrefix() throws Exception {
        File file = file("abc");
        try (Server server = new Server("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 3-5/6\r\nConnection: close\r\n\r\ndef")) {
            downloader().download(server.url(), file, 6);
            assertEquals("abcdef", read(file));
            assertTrue(server.request.contains("Range: bytes=3-"));
        }
    }
    @Test public void replacesPrefixWhenServerIgnoresRange() throws Exception {
        File file = file("old");
        try (Server server = new Server("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nabcdef")) {
            downloader().download(server.url(), file, 6);
            assertEquals("abcdef", read(file));
        }
    }
    @Test public void interruptedBodyCanBeResumedOnRetry() throws Exception {
        File file = file("");
        try (Server server = new Server("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nabc")) {
            try { downloader().download(server.url(), file, 6); fail("short download accepted"); }
            catch (IOException expected) { assertEquals("abc", read(file)); }
        }
        try (Server server = new Server("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 3-5/6\r\nConnection: close\r\n\r\ndef")) {
            downloader().download(server.url(), file, 6);
            assertEquals("abcdef", read(file));
        }
    }
    @Test public void wrongRangeCannotCorruptPrefix() throws Exception {
        File file = file("abc");
        try (Server server = new Server("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 0-2/6\r\nConnection: close\r\n\r\ndef")) {
            try { downloader().download(server.url(), file, 6); fail("wrong range accepted"); }
            catch (IOException expected) { assertEquals("abc", read(file)); }
        }
    }
    @Test public void cancellationPreservesPartialDownload() throws Exception {
        File file = file("abc");
        ArchiveDownload transfer = new ArchiveDownload(new AtomicBoolean(true), (downloaded, expected) -> {});
        try { transfer.download("http://127.0.0.1:9/unreachable", file, 6); fail("cancelled transfer started"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("abgebrochen")); assertEquals("abc", read(file)); }
    }
    @Test public void downloadFailureUsesTheCurrentLanguage() throws Exception {
        File file = file("abc");
        java.util.concurrent.atomic.AtomicReference<TerminalLanguage> language = new java.util.concurrent.atomic.AtomicReference<>(TerminalLanguage.GERMAN);
        ArchiveDownload transfer = new ArchiveDownload(new AtomicBoolean(true), (downloaded, expected) -> {}, language::get);
        language.set(TerminalLanguage.ENGLISH);
        try { transfer.download("http://127.0.0.1:9/unreachable", file, 6); fail("cancelled transfer started"); }
        catch (IOException expected) { assertEquals("Installation cancelled; the download can be resumed.", expected.getMessage()); }
        assertEquals("abc", read(file));
    }
    @Test public void progressReportsBytesForLocalizedPresentation() throws Exception {
        File file = file("");
        long[] reported = {0, 0};
        ArchiveDownload transfer = new ArchiveDownload(new AtomicBoolean(), (downloaded, expected) -> { reported[0] = downloaded; reported[1] = expected; });
        try (Server server = new Server("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nabcdef")) {
            transfer.download(server.url(), file, 6);
            assertArrayEquals(new long[]{6, 6}, reported);
        }
    }
}
