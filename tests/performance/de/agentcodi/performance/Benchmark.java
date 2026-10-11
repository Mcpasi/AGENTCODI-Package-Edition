package de.agentcodi.performance;

import com.sun.management.ThreadMXBean;
import de.agentcodi.core.JsonCodec;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Benchmark {
    interface Workload extends AutoCloseable {
        void execute(Meter meter) throws Exception;
        void verify() throws Exception;
        @Override void close() throws Exception;
    }

    interface Factory {
        Workload create() throws Exception;
    }

    // Retain observable results so the JIT cannot discard work.
    static volatile Object sink;
    private static final ThreadMXBean THREADS =
        (ThreadMXBean) ManagementFactory.getThreadMXBean();

    static final class Meter {
        private final long[] events;
        private int count;

        Meter(int operations) {
            events = new long[operations];
        }

        void record(long start) {
            events[count++] = System.nanoTime() - start;
        }

        Map<String, Object> percentiles() {
            check(count == events.length, "Replay did not process every event");
            long[] sorted = events.clone();
            Arrays.sort(sorted);
            return JsonCodec.object(
                "p50_ns", percentile(sorted, 0.50),
                "p95_ns", percentile(sorted, 0.95),
                "p99_ns", percentile(sorted, 0.99),
                "max_ns", sorted[sorted.length - 1]
            );
        }

        private static long percentile(long[] values, double fraction) {
            return values[(int) Math.ceil(values.length * fraction) - 1];
        }
    }

    static void run(String id, String boundary, Map<String, Object> parameters,
        int operations, long inputBytes, int warmups, int samples, Factory factory)
        throws Exception {
        check(THREADS.isThreadAllocatedMemorySupported(), "Allocation counters unavailable");
        THREADS.setThreadAllocatedMemoryEnabled(true);
        check(THREADS.isCurrentThreadCpuTimeSupported(), "CPU counters unavailable");
        THREADS.setThreadCpuTimeEnabled(true);
        long threadId = Thread.currentThread().getId();
        List<Object> measurements = new ArrayList<Object>();
        for (int iteration = -warmups; iteration < samples; iteration++) {
            // Fixture construction, RPC handshake, validation and teardown are untimed.
            try (Workload workload = factory.create()) {
                Meter meter = new Meter(operations);
                long[] gcBefore = gc();
                long allocated = THREADS.getThreadAllocatedBytes(threadId);
                long cpu = THREADS.getCurrentThreadCpuTime();
                long start = System.nanoTime();
                workload.execute(meter);
                long elapsed = System.nanoTime() - start;
                cpu = THREADS.getCurrentThreadCpuTime() - cpu;
                allocated = THREADS.getThreadAllocatedBytes(threadId) - allocated;
                long[] gcAfter = gc();
                workload.verify();
                if (iteration >= 0) {
                    Map<String, Object> measurement = new LinkedHashMap<String, Object>();
                    measurement.put("elapsed_ns", elapsed);
                    measurement.put("cpu_ns", cpu);
                    measurement.put("allocated_bytes", allocated);
                    measurement.put("gc_count", gcAfter[0] - gcBefore[0]);
                    measurement.put("gc_ms", gcAfter[1] - gcBefore[1]);
                    measurement.put("event_latency", meter.percentiles());
                    measurements.add(measurement);
                }
            }
        }
        System.out.println(JsonCodec.stringify(JsonCodec.object(
            "id", id, "boundary", boundary, "parameters", parameters,
            "operations", operations, "input_bytes", inputBytes,
            "warmups", warmups, "samples", measurements
        )));
    }

    private static long[] gc() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, collector.getCollectionCount());
            time += Math.max(0, collector.getCollectionTime());
        }
        return new long[] {count, time};
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static String text(int characters) {
        String seed = "Build step 0042: reading workspace/src/Main.java; status ok.\n";
        StringBuilder output = new StringBuilder(characters);
        while (output.length() < characters) {
            output.append(seed, 0, Math.min(seed.length(), characters - output.length()));
        }
        return output.toString();
    }

    private Benchmark() { }
}
