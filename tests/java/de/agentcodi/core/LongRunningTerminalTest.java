package de.agentcodi.core;

import de.agentcodi.tests.TestSupport;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Replays the command/exec timeout and capture rules without a 30-minute sleep. */
public final class LongRunningTerminalTest {
    private LongRunningTerminalTest() { }

    public static int run() throws Exception {
        survivesFormerServerDeadline();
        waitsForExitWithoutClientDeadline();
        streamsBeyondFormerCaptureLimits();
        acceptsCoalescedServerOutput();
        closesPendingCommandOnTransportFailure();
        preservesFiniteAdministrativeAndStreamingTimeouts();
        rejectsOversizedOutputChunks();
        completesNaturallyAndReleasesThePendingRpc();
        interruptsTheUnlimitedClientWait();
        return 9;
    }

    public static void survivesFormerServerDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            // Use the real outbound request, not a shortened production timeout.
            fixture.transport.advanceElapsed(31L * 60L * 1000L);
            fixture.awaitReaderBarrier();
            TestSupport.assertTrue(fixture.terminal.snapshot().isRunning(),
                "terminal survives the former 30-minute server deadline");
            TestSupport.assertEquals(Boolean.TRUE,
                fixture.transport.commandParams.get("disableTimeout"),
                "disable the server default as well as the explicit timeout");
            TestSupport.assertFalse(fixture.transport.commandParams.containsKey("timeoutMs"),
                "protocol forbids timeoutMs together with disableTimeout");
            fixture.stop();
        }
    }

    public static void waitsForExitWithoutClientDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            await(new Condition() {
                @Override public boolean isTrue() {
                    Thread.State state = fixture.transport.commandThread.getState();
                    return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
                }
            }, "terminal RPC reaches its response wait");
            TestSupport.assertEquals(Thread.State.WAITING,
                fixture.transport.commandThread.getState(),
                "terminal waits for exit or cancellation without a client time limit");
            fixture.stop();
        }
    }

    public static void streamsBeyondFormerCaptureLimits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            byte[] chunk = new byte[32 * 1024];
            Arrays.fill(chunk, (byte) 'x');
            for (int index = 79; index < chunk.length; index += 80) {
                chunk[index] = '\n';
            }
            // More than 8 MiB per stream and 16 MiB in total; capture remains rolling.
            for (int index = 0; index < 576; index++) {
                fixture.transport.output(index % 2 == 0 ? "stdout" : "stderr", chunk);
            }
            byte[] marker = "\r\nlong-build-finished ✓\r\n".getBytes(StandardCharsets.UTF_8);
            fixture.transport.output("stdout", marker);
            TerminalSessionSnapshot snapshot = fixture.terminal.snapshot();
            TestSupport.assertTrue(snapshot.isRunning(), "build output must not terminate the PTY after 18 MiB");
            TestSupport.assertTrue(snapshot.getFailure().isEmpty(), "no cumulative output failure");
            TestSupport.assertTrue(snapshot.getOutput().contains("long-build-finished ✓"),
                "fresh output is still visible after 18 MiB");
            TestSupport.assertTrue(snapshot.getOutput().contains("[earlier terminal output omitted]"),
                "older display output rolls out as before");
            TestSupport.assertTrue(snapshot.getOutput().length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
                "unlimited session output does not create unlimited display storage");
            TestSupport.assertEquals(Boolean.TRUE,
                fixture.transport.commandParams.get("disableOutputCap"), "unlimited streaming capture");
            TestSupport.assertFalse(fixture.transport.commandParams.containsKey("outputBytesCap"),
                "protocol forbids outputBytesCap together with disableOutputCap");
            fixture.stop();
        }
    }

    public static void closesPendingCommandOnTransportFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.transport.close();
            await(new Condition() {
                @Override public boolean isTrue() {
                    return !fixture.terminal.snapshot().isRunning()
                        && fixture.transport.commandThread.getState() != Thread.State.TIMED_WAITING;
                }
            }, "transport failure releases the terminal command");
            TestSupport.assertFalse(fixture.terminal.snapshot().getFailure().isEmpty(),
                "a disconnected terminal still reports its failure");
        }
    }

    public static void acceptsCoalescedServerOutput() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            // The unchanged v0.156.1 server emitted chunks of 73,025 bytes in
            // the native output replay. Its 64 KiB coalescing target is a hint.
            byte[] chunk = new byte[73_025];
            Arrays.fill(chunk, (byte) 'x');
            fixture.transport.output("stdout", chunk);
            TestSupport.assertTrue(fixture.terminal.snapshot().isRunning(),
                "a valid coalesced server chunk above 64 KiB does not kill the PTY");
            TestSupport.assertTrue(fixture.terminal.snapshot().getFailure().isEmpty(),
                "coalesced output is accepted without a protocol failure");
            fixture.stop();
        }
    }

    public static void preservesFiniteAdministrativeAndStreamingTimeouts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TestSupport.expectThrows(IllegalArgumentException.class, new TestSupport.ThrowingRunnable() {
                @Override public void run() throws Exception {
                    fixture.client.request("account/read", JsonCodec.object(), 0L);
                }
            }, "administrative RPCs cannot wait indefinitely");
            for (final Map<String, Object> params : Arrays.asList(
                    JsonCodec.object(), JsonCodec.object("tty", Boolean.TRUE),
                    JsonCodec.object("disableTimeout", Boolean.TRUE))) {
                TestSupport.expectThrows(IllegalArgumentException.class, new TestSupport.ThrowingRunnable() {
                    @Override public void run() throws Exception {
                        fixture.client.requestStreaming("command/exec", params, 0L, null);
                    }
                }, "unlimited wait requires an interactive PTY with its server deadline disabled");
            }
            TestSupport.expectThrows(IllegalArgumentException.class, new TestSupport.ThrowingRunnable() {
                @Override public void run() throws Exception {
                    fixture.client.requestStreaming("account/read",
                        JsonCodec.object("tty", Boolean.TRUE, "disableTimeout", Boolean.TRUE), 0L, null);
                }
            }, "unlimited wait cannot be used for an unrelated RPC");
            TestSupport.expectThrows(java.util.concurrent.TimeoutException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override public void run() throws Exception {
                        fixture.client.requestStreaming("fixture/unanswered", JsonCodec.object(), 30L, null);
                    }
                }, "finite streaming RPCs retain their timeout");
        }
    }

    public static void rejectsOversizedOutputChunks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.transport.output("stdout", new byte[(64 + 8) * 1024 + 1]);
            TestSupport.assertFalse(fixture.terminal.snapshot().isRunning(),
                "chunks beyond the server's coalescing bound still fail closed");
            TestSupport.assertTrue(fixture.terminal.snapshot().getFailure().contains("limit"),
                "oversized output is still reported");
            await(new Condition() {
                @Override public boolean isTrue() { return fixture.transport.terminations == 1; }
            }, "protocol failure still terminates its process");
        }
    }

    public static void completesNaturallyAndReleasesThePendingRpc() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.transport.complete(7L);
            await(new Condition() {
                @Override public boolean isTrue() { return fixture.terminal.snapshot().getExitCode() == 7; }
            }, "natural exit completes the unlimited RPC");
            TestSupport.assertFalse(fixture.terminal.snapshot().isRunning(), "exited terminal is stopped");
            TestSupport.assertTrue(fixture.terminal.snapshot().getFailure().isEmpty(), "exit code remains authoritative");
            java.lang.reflect.Field pending = CodexAppServerClient.class.getDeclaredField("pending");
            pending.setAccessible(true);
            TestSupport.assertTrue(((Map<?, ?>) pending.get(fixture.client)).isEmpty(),
                "completed terminal releases its pending RPC state");
        }
    }

    public static void interruptsTheUnlimitedClientWait() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.transport.commandThread.interrupt();
            await(new Condition() {
                @Override public boolean isTrue() {
                    return !fixture.terminal.snapshot().isRunning() && fixture.transport.terminations == 1;
                }
            }, "interruption releases the wait and terminates the PTY");
            TestSupport.assertFalse(fixture.terminal.snapshot().getFailure().isEmpty(),
                "interrupted waits still report their cause");
        }
    }

    private interface Condition { boolean isTrue(); }

    private static void await(Condition condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.isTrue() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        TestSupport.assertTrue(condition.isTrue(), message);
    }

    private static final class Fixture implements AutoCloseable, CodexAppServerClient.Listener {
        final Transport transport = new Transport();
        final CodexAppServerClient client = new CodexAppServerClient(transport, this);
        final CodexTerminalSession terminal = new CodexTerminalSession(client,
            "/private/workspace", "/private/lib/libagentcodi-shell.so", ":danger-full-access");
        volatile boolean barrier;

        Fixture() throws Exception {
            transport.terminal = terminal;
            client.start();
            client.initialize(JsonCodec.object(), 1_000L);
        }

        void start() throws Exception {
            terminal.start(24, 80);
            await(new Condition() {
                @Override public boolean isTrue() {
                    return terminal.snapshot().isRunning() && transport.commandParams != null;
                }
            }, "terminal starts");
        }

        void stop() throws Exception {
            terminal.stop();
            await(new Condition() {
                @Override public boolean isTrue() {
                    return terminal.snapshot().getExitCode() != Integer.MIN_VALUE;
                }
            }, "explicit Stop still completes an unlimited terminal");
            TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(transport.terminations),
                "one explicit process termination");
        }

        void awaitReaderBarrier() throws Exception {
            transport.incoming.add(JsonCodec.stringify(JsonCodec.object(
                "method", "fixture/barrier", "params", JsonCodec.object())));
            await(new Condition() {
                @Override public boolean isTrue() { return barrier; }
            }, "reader consumes the virtual deadline event");
            // The RPC completion and reader notification execute on separate threads.
            if (transport.completed) {
                await(new Condition() {
                    @Override public boolean isTrue() {
                        return terminal.snapshot().getExitCode() != Integer.MIN_VALUE;
                    }
                }, "timed-out command completion is projected");
            }
        }

        @Override public boolean onServerRequest(long id, String method, Map<String, Object> params) {
            return false;
        }
        @Override public void onNotification(String method, Map<String, Object> params) {
            if ("fixture/barrier".equals(method)) { barrier = true; }
            else { terminal.onNotification(method, params); }
        }
        @Override public void onTransportClosed(Throwable error) { terminal.onTransportClosed(error); }
        @Override public void close() { terminal.close(); client.close(); }
    }

    private static final class Transport implements CodexRpcTransport {
        final LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<String>();
        CodexTerminalSession terminal;
        volatile Map<String, Object> commandParams;
        volatile Thread commandThread;
        volatile boolean completed;
        volatile int terminations;
        private Object commandId;
        private long stdoutBytes;
        private long stderrBytes;
        private volatile boolean closed;

        @Override public String readLine(int maximumBytes) throws IOException {
            try {
                String line = incoming.take();
                if (closed) { throw new EOFException("fixture transport closed"); }
                return line;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("fixture reader interrupted", error);
            }
        }

        @Override public void writeLine(String line, int maximumBytes) {
            Map<String, Object> message = JsonCodec.requireObject(JsonCodec.parse(line), "RPC");
            String method = JsonCodec.optionalString(message.get("method"));
            if ("initialize".equals(method)) {
                response(message.get("id"), JsonCodec.object());
            } else if ("command/exec".equals(method)) {
                commandId = message.get("id");
                commandThread = Thread.currentThread();
                commandParams = JsonCodec.requireObject(message.get("params"), "command params");
            } else if ("command/exec/terminate".equals(method)) {
                terminations++;
                response(message.get("id"), JsonCodec.object());
                complete(0L);
            } else if (!"initialized".equals(method) && !"fixture/unanswered".equals(method)) {
                response(message.get("id"), Collections.<String, Object>emptyMap());
            }
        }

        @Override public void writeBytes(byte[] line, int length, int maximumBytes) {
            try { writeLine(new String(line, 0, length, StandardCharsets.UTF_8), maximumBytes); }
            finally { Arrays.fill(line, (byte) 0); }
        }

        void advanceElapsed(long elapsed) {
            if (!Boolean.TRUE.equals(commandParams.get("disableTimeout"))) {
                long timeout = JsonCodec.longValue(commandParams.get("timeoutMs"), 10_000L);
                if (elapsed >= timeout) { complete(124L); }
            }
        }

        void output(String stream, byte[] bytes) {
            if ("stdout".equals(stream)) { stdoutBytes += bytes.length; }
            else { stderrBytes += bytes.length; }
            long observed = "stdout".equals(stream) ? stdoutBytes : stderrBytes;
            boolean capped = !Boolean.TRUE.equals(commandParams.get("disableOutputCap"))
                && observed >= JsonCodec.longValue(commandParams.get("outputBytesCap"), 1024L * 1024L);
            terminal.onNotification(CodexTerminalSession.OUTPUT_DELTA_METHOD, JsonCodec.object(
                "processId", commandParams.get("processId"), "stream", stream,
                "deltaBase64", Base64.getEncoder().encodeToString(bytes), "capReached", Boolean.valueOf(capped)));
        }

        private void complete(long exitCode) {
            completed = true;
            response(commandId, JsonCodec.object("exitCode", Long.valueOf(exitCode), "stdout", "", "stderr", ""));
        }
        private void response(Object id, Map<String, Object> result) {
            incoming.add(JsonCodec.stringify(JsonCodec.object("id", id, "result", result)));
        }
        @Override public void close() { closed = true; incoming.offer(""); }
    }
}
