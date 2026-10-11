package de.agentcodi.performance;

import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Only the remote RPC peer is a fixture; all client state changes use production code. */
final class ReplaySession implements AutoCloseable {
    static final String THREAD = "thread_performance";
    static final String TURN = "turn_performance";
    final CodexSessionController controller;
    final Peer peer = new Peer();

    ReplaySession(boolean terminal) throws Exception {
        controller = new CodexSessionController(peer, "/performance/workspace", null,
            terminal ? "/performance/bin/shell" : null);
        try {
            controller.start();
            if (terminal) {
                controller.startTerminal(24, 80);
                await(() -> controller.terminalSnapshot().isRunning(), "terminal start");
            } else {
                controller.startNewThread();
                await(() -> THREAD.equals(controller.snapshot().getActiveThreadId())
                    && !controller.snapshot().isOperationActive(), "thread start");
            }
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    void beginTurn() {
        controller.onNotification("turn/started", JsonCodec.object(
            "threadId", THREAD, "turn", JsonCodec.object("id", TURN, "status", "inProgress")));
        Benchmark.check(controller.snapshot().isTurnActive(), "Turn was not accepted");
    }

    void deliver(String frame) {
        Map<String, Object> value = JsonCodec.parseObject(frame);
        controller.onNotification(JsonCodec.requireString(value.get("method"), "method"),
            JsonCodec.requireObject(value.get("params"), "params"));
    }

    private static void await(BooleanSupplier condition, String operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("Replay setup timed out: " + operation);
            }
            Thread.sleep(1);
        }
    }

    @Override public void close() {
        controller.close();
    }

    static final class Peer implements CodexRpcTransport {
        private final LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<String>();
        private volatile boolean closed;
        volatile String processId;

        @Override public String readLine(int maximumBytes) throws IOException {
            try {
                String line = incoming.take();
                if (line.isEmpty()) {
                    return null;
                }
                if (line.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
                    throw new IOException("Fixture exceeds incoming frame limit");
                }
                return line;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Replay peer interrupted", error);
            }
        }

        @Override public void writeLine(String line, int maximumBytes) throws IOException {
            if (closed) {
                throw new IOException("Replay peer closed");
            }
            Map<String, Object> request = JsonCodec.parseObject(line);
            String method = JsonCodec.optionalString(request.get("method"));
            if (!request.containsKey("id")) {
                return;
            }
            Map<String, Object> params = JsonCodec.optionalObject(request.get("params"));
            Object result;
            switch (method) {
                case "initialize":
                    result = JsonCodec.object("userAgent", "performance-replay/1");
                    break;
                case "permissionProfile/list":
                    result = JsonCodec.object("data", JsonCodec.array(JsonCodec.object(
                        "id", ":danger-full-access", "allowed", Boolean.TRUE)), "nextCursor", null);
                    break;
                case "model/list":
                    result = JsonCodec.object("data", JsonCodec.array(JsonCodec.object(
                        "id", "performance-model", "model", "performance-model",
                        "displayName", "Replay", "defaultReasoningEffort", "low",
                        "isDefault", Boolean.TRUE, "supportedReasoningEfforts", JsonCodec.array(
                            JsonCodec.object("reasoningEffort", "low", "description", "Replay"))
                    )), "nextCursor", null);
                    break;
                case "account/read":
                    result = JsonCodec.object("account", null, "requiresOpenaiAuth", Boolean.FALSE);
                    break;
                case "thread/list":
                    result = JsonCodec.object("data", JsonCodec.array(), "nextCursor", null);
                    break;
                case "thread/start":
                    result = JsonCodec.object("thread", JsonCodec.object(
                        "id", THREAD, "name", "Performance replay",
                        "modelProvider", "agentcodi-openai-http"),
                        "activePermissionProfile", JsonCodec.object("id", params.get("permissions")),
                        "model", params.get("model"));
                    break;
                case "command/exec":
                    processId = JsonCodec.requireString(params.get("processId"), "processId");
                    // Keep the streaming RPC open; closing the owner releases it.
                    return;
                default:
                    throw new IOException("Unexpected replay RPC: " + method);
            }
            incoming.add(JsonCodec.stringify(JsonCodec.object("id", request.get("id"),
                "result", result)));
        }

        @Override public void writeBytes(byte[] line, int length, int maximumBytes) throws IOException {
            try {
                writeLine(new String(line, 0, length, StandardCharsets.UTF_8), maximumBytes);
            } finally {
                Arrays.fill(line, (byte) 0);
            }
        }

        @Override public void close() {
            closed = true;
            incoming.offer("");
        }
    }
}
