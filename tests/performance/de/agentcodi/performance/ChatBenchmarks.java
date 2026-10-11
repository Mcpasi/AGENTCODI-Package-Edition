package de.agentcodi.performance;

import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.JsonCodec;
import java.util.Map;

final class ChatBenchmarks {
    private static final String ITEM = "item_performance";

    static void run(int warmups, int samples) throws Exception {
        for (int characters : new int[] {16 * 1024, 32 * 1024, 64 * 1024}) {
            runStream("assistant", characters, 64, 0, warmups, samples);
        }
        runStream("assistant", 64 * 1024, 4096, 0, warmups, samples);
        runStream("assistant", 64 * 1024, 64, 200, warmups, samples);
        for (String kind : new String[] {"tool", "reasoning"}) {
            for (int characters : new int[] {16 * 1024, 32 * 1024}) {
                runStream(kind, characters, 128, 0, warmups, samples);
            }
        }
    }

    private static void runStream(String kind, int characters, int chunkCharacters, int history,
        int warmups, int samples) throws Exception {
        String id = "chat." + kind + "-" + characters / 1024 + "k-" + chunkCharacters + "b"
            + (history > 0 ? "-history" : "");
        Benchmark.run(id,
            "JsonCodec.parseObject -> CodexSessionController.onNotification -> stream merge/redaction -> published snapshot; no Android layout",
            JsonCodec.object("kind", kind, "payload_characters", characters,
                "chunk_characters", chunkCharacters, "history_items", history),
            characters / chunkCharacters, characters, warmups, samples,
            () -> new StreamReplay(kind, characters, chunkCharacters, history));
    }

    private static final class StreamReplay implements Benchmark.Workload {
        private final ReplaySession session = new ReplaySession(false);
        private final String kind;
        private final String expected;
        private final String[] frames;
        private final int history;
        private final long initialRevision;
        private CodexSessionSnapshot last;

        StreamReplay(String kind, int characters, int chunkCharacters, int history) throws Exception {
            this.kind = kind;
            this.history = history;
            expected = Benchmark.text(characters);
            try {
                for (int index = 0; index < history; index++) {
                    session.controller.onNotification("item/completed", JsonCodec.object(
                        "threadId", ReplaySession.THREAD, "turnId", "turn_history",
                        "item", JsonCodec.object("type", "agentMessage", "id", "history_" + index,
                            "text", Benchmark.text(512))));
                }
                Benchmark.check(session.controller.snapshot().getTranscriptItems().size() == history,
                    "History setup was not accepted");
                session.beginTurn();
                frames = new String[characters / chunkCharacters];
                String method = "assistant".equals(kind) ? "item/agentMessage/delta"
                    : "tool".equals(kind) ? "item/commandExecution/outputDelta"
                    : "item/reasoning/summaryTextDelta";
                for (int index = 0; index < frames.length; index++) {
                    Map<String, Object> params = JsonCodec.object(
                        "threadId", ReplaySession.THREAD, "turnId", ReplaySession.TURN, "itemId", ITEM,
                        "delta", expected.substring(index * chunkCharacters, (index + 1) * chunkCharacters));
                    if ("reasoning".equals(kind)) {
                        params.put("summaryIndex", 0);
                    }
                    frames[index] = JsonCodec.stringify(JsonCodec.object("method", method, "params", params));
                }
                initialRevision = session.controller.snapshot().getRevision();
            } catch (Exception | Error error) {
                session.close();
                throw error;
            }
        }

        @Override public void execute(Benchmark.Meter meter) {
            for (String frame : frames) {
                long start = System.nanoTime();
                session.deliver(frame);
                last = session.controller.snapshot();
                Benchmark.sink = last;
                meter.record(start);
            }
        }

        @Override public void verify() {
            // A later performance fix may coalesce publications; require current content,
            // not one snapshot allocation/revision per delta.
            Benchmark.check(last.getRevision() > initialRevision,
                "Stream replay did not publish a current snapshot");
            Benchmark.check(last.getErrorMessage().isEmpty() && last.isTurnActive(),
                "Stream replay failed or ended early");
            Benchmark.check(last.getTranscriptItems().size() == history + 1,
                "Stream replay dropped history or duplicated items");
            CodexTranscriptItem item = last.getTranscriptItems().get(history);
            Benchmark.check(ITEM.equals(item.getId()) && item.isStreaming(), "Wrong streaming item");
            String output = "assistant".equals(kind) ? item.getMessage().getText()
                : "reasoning".equals(kind) ? item.getSummary() : item.getDetail();
            Benchmark.check("tool".equals(kind) ? output.endsWith(expected) : output.equals(expected),
                "Streaming payload was not reproduced exactly: " + kind);
        }

        @Override public void close() { session.close(); }
    }

    private ChatBenchmarks() { }
}
