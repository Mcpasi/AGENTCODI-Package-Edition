package de.agentcodi.app;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/** Local-only companion measurement; existing core replay sources stay unchanged. */
public final class ChatMarkdownBenchmark {
    private static volatile ChatMarkdown.Document sink;
    private static final int ITERATIONS = 20;

    public static void main(String[] arguments) {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        String[] names = {"plain", "markdown", "unfinished"};
        String[] seeds = {
            "Eine normale Antwort mit mehreren Zeilen und Unicode 👋 世界.\n\n",
            "## Ergebnis\n\n**Fertig** mit *Text*, `code` und [Doku](https://example.com).\n\n"
                + "- Schritt eins\n- Schritt zwei\n\n```java\n  value();\n```\n\n"
                + "| Name | Wert |\n| --- | --- |\n| **A** | B |\n\n",
            "<<<<[partial **text _more ~ &unknown; \\ [ ] ( ) ` "
        };
        for (int workload = 0; workload < names.length; workload++) {
            StringBuilder repeated = new StringBuilder();
            while (repeated.length() < 65536) { repeated.append(seeds[workload]); }
            String source = repeated.substring(0, 65536);
            for (int warmup = 0; warmup < 2; warmup++) { execute(source); }
            for (int sample = 0; sample < 5; sample++) {
                long thread = Thread.currentThread().getId();
                long allocated = memory.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                execute(source);
                double milliseconds = (System.nanoTime() - start) / 1000000.0 / ITERATIONS;
                long bytes = (memory.getThreadAllocatedBytes(thread) - allocated) / ITERATIONS;
                for (ChatMarkdown.Span span : sink.spans) {
                    if (span.start < 0 || span.end < span.start || span.end > sink.text.length()) {
                        throw new AssertionError("Invalid projected span");
                    }
                }
                System.out.println("{\"workload\":\"" + names[workload] + "\",\"sample\":" + sample
                    + ",\"input_characters\":65536,\"iterations\":" + ITERATIONS
                    + ",\"ms_per_parse\":" + milliseconds + ",\"allocated_bytes_per_parse\":" + bytes
                    + ",\"output_characters\":" + sink.text.length() + ",\"spans\":" + sink.spans.size() + "}");
            }
        }
    }

    private static void execute(String source) {
        for (int iteration = 0; iteration < ITERATIONS; iteration++) { sink = ChatMarkdown.parse(source); }
    }
}
