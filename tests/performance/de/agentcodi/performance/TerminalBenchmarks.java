package de.agentcodi.performance;

import de.agentcodi.core.JsonCodec;
import de.agentcodi.core.TerminalOutputBuffer;
import de.agentcodi.core.TerminalSessionSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

final class TerminalBenchmarks {
    private static final String END = "terminal-replay-end";
    private static final byte[] LOG = (Benchmark.text(320 * 1024)
        + "\r\n" + END + "\r\n").getBytes(StandardCharsets.UTF_8);

    static void run(int warmups, int samples) throws Exception {
        for (int chunkBytes : new int[] {256, 4096}) {
            Benchmark.run("terminal.output-" + chunkBytes + "b",
                "JsonCodec.parseObject -> onNotification -> Base64 validation -> terminal cells; no UI",
                JsonCodec.object("chunk_bytes", chunkBytes, "rows", 24, "columns", 80),
                (LOG.length + chunkBytes - 1) / chunkBytes, LOG.length, warmups, samples,
                () -> new OutputReplay(chunkBytes));
        }
        byte[] unicode = ("\u001b[32m进度 café e\u0301 🌍\u001b[0m\r\n".repeat(2048)
            + END + "\r\n").getBytes(StandardCharsets.UTF_8);
        Benchmark.run("terminal.utf8-7b", "TerminalOutputBuffer.append; split UTF-8 and ANSI sequences",
            JsonCodec.object("chunk_bytes", 7, "rows", 24, "columns", 80),
            (unicode.length + 6) / 7, unicode.length, warmups, samples,
            () -> new Utf8Replay(unicode));
        for (boolean full : new boolean[] {false, true}) {
            Benchmark.run("terminal.idle-" + (full ? "full" : "empty"),
                "Two terminalSnapshot calls per unchanged refresh, as in TerminalActivity.refreshTask; no TextView",
                JsonCodec.object("refreshes", 12, "snapshots_per_refresh", 2,
                    "scrollback_input_bytes", full ? LOG.length : 0),
                12, 0, warmups, samples, () -> new IdleReplay(full));
        }
    }

    private static String[] frames(byte[] bytes, int chunkBytes, String processId) {
        List<String> frames = new ArrayList<String>();
        for (int start = 0; start < bytes.length; start += chunkBytes) {
            byte[] chunk = Arrays.copyOfRange(bytes, start, Math.min(start + chunkBytes, bytes.length));
            frames.add(JsonCodec.stringify(JsonCodec.object(
                "method", "command/exec/outputDelta", "params", JsonCodec.object(
                    "processId", processId, "stream", "stdout", "capReached", Boolean.FALSE,
                    "deltaBase64", Base64.getEncoder().encodeToString(chunk)))));
        }
        return frames.toArray(new String[0]);
    }

    private static void verifyOutput(TerminalSessionSnapshot snapshot) {
        Benchmark.check(snapshot.isRunning() && snapshot.getFailure().isEmpty(),
            "Terminal output replay failed: " + snapshot.getFailure());
        Benchmark.check(snapshot.getOutput().contains(END), "Terminal lost the final output");
        Benchmark.check(snapshot.getOutput().length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
            "Terminal scrollback exceeded its bound");
        Benchmark.check(snapshot.getOutput().startsWith("[earlier terminal output omitted]"),
            "Terminal replay did not exercise scrollback eviction");
    }

    private static final class OutputReplay implements Benchmark.Workload {
        private final ReplaySession session = new ReplaySession(true);
        private final String[] frames;

        OutputReplay(int chunkBytes) throws Exception {
            try {
                frames = frames(LOG, chunkBytes, session.peer.processId);
            } catch (Exception | Error error) {
                session.close();
                throw error;
            }
        }

        @Override public void execute(Benchmark.Meter meter) {
            for (String frame : frames) {
                long start = System.nanoTime();
                session.deliver(frame);
                meter.record(start);
            }
            Benchmark.sink = session.controller;
        }

        @Override public void verify() {
            verifyOutput(session.controller.terminalSnapshot());
        }

        @Override public void close() { session.close(); }
    }

    private static final class Utf8Replay implements Benchmark.Workload {
        private final TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        private final byte[][] chunks;

        Utf8Replay(byte[] input) {
            chunks = new byte[(input.length + 6) / 7][];
            for (int index = 0; index < chunks.length; index++) {
                chunks[index] = Arrays.copyOfRange(input, index * 7, Math.min(index * 7 + 7, input.length));
            }
        }

        @Override public void execute(Benchmark.Meter meter) {
            for (byte[] chunk : chunks) {
                long start = System.nanoTime();
                buffer.append(chunk);
                meter.record(start);
            }
            Benchmark.sink = buffer;
        }

        @Override public void verify() {
            buffer.finish();
            String output = buffer.snapshot();
            Benchmark.check(output.contains(END) && output.contains("进度 café é 🌍"),
                "Fragmented UTF-8 replay lost text");
            Benchmark.check(!output.contains("\uFFFD") && !output.contains("\u001b"),
                "Fragmented UTF-8/ANSI replay leaked invalid characters");
        }

        @Override public void close() { buffer.clear(); }
    }

    private static final class IdleReplay implements Benchmark.Workload {
        private final ReplaySession session = new ReplaySession(true);
        private final TerminalSessionSnapshot initial;
        private TerminalSessionSnapshot last;

        IdleReplay(boolean full) throws Exception {
            try {
                if (full) {
                    for (String frame : frames(LOG, 4096, session.peer.processId)) {
                        session.deliver(frame);
                    }
                    verifyOutput(session.controller.terminalSnapshot());
                }
                initial = session.controller.terminalSnapshot();
            } catch (Exception | Error error) {
                session.close();
                throw error;
            }
        }

        @Override public void execute(Benchmark.Meter meter) {
            for (int index = 0; index < 12; index++) {
                long start = System.nanoTime();
                Benchmark.sink = session.controller.terminalSnapshot();
                last = session.controller.terminalSnapshot();
                Benchmark.sink = last;
                meter.record(start);
            }
        }

        @Override public void verify() {
            Benchmark.check(last.getRevision() == initial.getRevision()
                && last.getOutput().equals(initial.getOutput()), "Idle replay changed terminal state");
        }

        @Override public void close() { session.close(); }
    }

    private TerminalBenchmarks() { }
}
