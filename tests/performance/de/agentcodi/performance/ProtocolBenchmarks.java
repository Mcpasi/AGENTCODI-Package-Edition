package de.agentcodi.performance;

import de.agentcodi.core.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class ProtocolBenchmarks {
    static void run(int warmups, int samples) throws Exception {
        for (int[] size : new int[][] {{16, 4096, 12}, {240, 4096, 4}, {240, 16384, 2}}) {
            int items = size[0];
            int characters = size[1];
            int iterations = size[2];
            String text = Benchmark.text(characters - 12) + "\n引号 \" 🌍";
            String item = JsonCodec.stringify(JsonCodec.object(
                "id", "history_item", "type", "agentMessage", "text", text));
            StringBuilder frame = new StringBuilder("{\"id\":42,\"result\":{\"thread\":{\"id\":\"thread_performance\",\"turns\":[{\"id\":\"turn_history\",\"items\":[");
            for (int index = 0; index < items; index++) {
                if (index > 0) { frame.append(','); }
                frame.append(item);
            }
            String payload = frame.append("]}]}}}").toString();
            Benchmark.run("protocol.history-" + items + "x" + characters,
                "JsonCodec.parseObject on incoming thread history with escaped text; transport/image compaction excluded",
                JsonCodec.object("items", items, "characters_per_item", text.length(),
                    "frame_bytes", payload.getBytes(StandardCharsets.UTF_8).length),
                iterations, (long) payload.getBytes(StandardCharsets.UTF_8).length * iterations,
                warmups, samples, () -> new HistoryReplay(payload, items, text, iterations));
        }
    }

    private static final class HistoryReplay implements Benchmark.Workload {
        private final String payload;
        private final int items;
        private final String expected;
        private final int iterations;
        private Map<String, Object> last;

        HistoryReplay(String payload, int items, String expected, int iterations) {
            this.payload = payload;
            this.items = items;
            this.expected = expected;
            this.iterations = iterations;
        }

        @Override public void execute(Benchmark.Meter meter) {
            for (int index = 0; index < iterations; index++) {
                long start = System.nanoTime();
                last = JsonCodec.parseObject(payload);
                Benchmark.sink = last;
                meter.record(start);
            }
        }

        @Override public void verify() {
            Map<String, Object> result = JsonCodec.requireObject(last.get("result"), "result");
            Map<String, Object> thread = JsonCodec.requireObject(result.get("thread"), "thread");
            List<Object> turns = JsonCodec.requireArray(thread.get("turns"), "turns");
            List<Object> parsed = JsonCodec.requireArray(
                JsonCodec.requireObject(turns.get(0), "turn").get("items"), "items");
            Benchmark.check(parsed.size() == items, "Parser dropped history items");
            for (Object item : parsed) {
                Benchmark.check(expected.equals(JsonCodec.requireObject(item, "item").get("text")),
                    "Parser corrupted escaped/unicode history text");
            }
        }

        @Override public void close() { last = null; }
    }

    private ProtocolBenchmarks() { }
}
