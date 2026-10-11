package de.agentcodi.performance;

import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.JsonCodec;
import java.util.Map;

/** Supplemental replay: actually read the complete visible text after every delta. */
public final class StreamConsumerBenchmark {
    public static void main(String[] arguments) throws Exception {
        int warmups = Integer.parseInt(arguments[0]);
        int samples = Integer.parseInt(arguments[1]);
        for (String kind : new String[] {"assistant", "tool", "reasoning", "reasoning-multipart"}) {
            int characters = ("assistant".equals(kind) ? 64 : 32) * 1024;
            int chunk = "assistant".equals(kind) ? 64 : 128;
            Benchmark.run("consumer." + kind + "-" + characters / 1024 + "k-" + chunk + "b",
                "JsonCodec -> controller delta -> snapshot -> complete text getter on every event; no Android layout",
                JsonCodec.object("kind", kind, "payload_characters", characters,
                    "chunk_characters", chunk, "text_reads_per_delta", 1),
                characters / chunk, characters, warmups, samples,
                () -> new Replay(kind, characters, chunk));
        }
    }

    private static final class Replay implements Benchmark.Workload {
        private final ReplaySession session = new ReplaySession(false);
        private final String kind;
        private final String expected;
        private final String[] frames;
        private String last;

        Replay(String kind, int characters, int chunk) throws Exception {
            this.kind = kind;
            expected = Benchmark.text(characters);
            try {
                session.beginTurn();
                if ("reasoning-multipart".equals(kind)) {
                    session.controller.onNotification("item/reasoning/summaryTextDelta", JsonCodec.object(
                        "threadId", ReplaySession.THREAD, "turnId", ReplaySession.TURN,
                        "itemId", "consumer_item", "summaryIndex", Long.valueOf(0), "delta", "Completed first part."));
                }
                frames = new String[characters / chunk];
                String method = "assistant".equals(kind) ? "item/agentMessage/delta"
                    : "tool".equals(kind) ? "item/commandExecution/outputDelta"
                    : "item/reasoning/summaryTextDelta";
                for (int index = 0; index < frames.length; index++) {
                    Map<String, Object> params = JsonCodec.object(
                        "threadId", ReplaySession.THREAD, "turnId", ReplaySession.TURN,
                        "itemId", "consumer_item", "delta", expected.substring(index * chunk, (index + 1) * chunk));
                    if (kind.startsWith("reasoning")) {
                        params.put("summaryIndex", "reasoning-multipart".equals(kind) ? 1 : 0);
                    }
                    frames[index] = JsonCodec.stringify(JsonCodec.object("method", method, "params", params));
                }
            } catch (Exception | Error error) {
                session.close();
                throw error;
            }
        }

        @Override public void execute(Benchmark.Meter meter) {
            for (String frame : frames) {
                long start = System.nanoTime();
                session.deliver(frame);
                CodexTranscriptItem item = session.controller.snapshot().getTranscriptItems().get(0);
                last = "assistant".equals(kind) ? item.getMessage().getText()
                    : "tool".equals(kind) ? item.getDetail() : item.getSummary();
                Benchmark.sink = last;
                meter.record(start);
            }
        }

        @Override public void verify() {
            String content = "reasoning-multipart".equals(kind)
                ? "Completed first part.\n" + expected : expected;
            Benchmark.check("tool".equals(kind) ? last.endsWith(content) : last.equals(content),
                "Text consumer lost streaming content: " + kind);
            Benchmark.check(session.controller.snapshot().isTurnActive(), "Consumer ended the turn");
        }

        @Override public void close() {
            session.close();
        }
    }
}
