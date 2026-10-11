package de.agentcodi.tests;

import de.agentcodi.core.CrashReportFormatter;
import java.util.concurrent.atomic.AtomicReference;

public final class CrashReportFormatterTest {
    private CrashReportFormatterTest() {
    }

    public static int run() {
        formatsBoundedStackTrace();
        redactsNamedAndBearerSecrets();
        redactsJsonOauthAndJwtSecrets();
        redactsVisibleTextBeforeTruncation();
        handlesCauseCycles();
        keepsConcurrentRedactionIndependent();
        return 6;
    }

    private static void keepsConcurrentRedactionIndependent() {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread[] workers = new Thread[6];
        for (int index = 0; index < workers.length; index++) {
            final int worker = index;
            workers[index] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int iteration = 0; iteration < 300; iteration++) {
                            String prefix = "worker-" + worker + "-" + iteration + " ";
                            String output = CrashReportFormatter.redactSecrets(prefix
                                + "password=fixture-secret Bearer fixture-token &code=fixture-code");
                            TestSupport.assertEquals(prefix
                                + "password=<redacted> Bearer <redacted> &code=<redacted>",
                                output, "thread-local redaction remains independent");
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }
            });
            workers[index].setDaemon(true);
            workers[index].start();
        }
        for (Thread worker : workers) {
            try {
                worker.join(5000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            TestSupport.assertFalse(worker.isAlive(), "redaction worker finishes");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void formatsBoundedStackTrace() {
        IllegalStateException error = new IllegalStateException("startup failed");
        String report = CrashReportFormatter.format("activity", Thread.currentThread(), error);
        TestSupport.assertContains(report, "Quelle: activity", "source");
        TestSupport.assertContains(
            report,
            "Fehler: java.lang.IllegalStateException",
            "exception class"
        );
        TestSupport.assertContains(report, "Meldung: startup failed", "message");
        TestSupport.assertTrue(report.length() <= 16 * 1024, "bounded report");
    }

    private static void redactsNamedAndBearerSecrets() {
        String redacted = CrashReportFormatter.redact(
            "authorization=example-value Bearer another-example password=not-for-logs "
                + "OpenAI API Key: sk-fixture123456789"
        );
        TestSupport.assertFalse(redacted.contains("example-value"), "named secret removed");
        TestSupport.assertFalse(redacted.contains("another-example"), "bearer secret removed");
        TestSupport.assertFalse(redacted.contains("not-for-logs"), "password removed");
        TestSupport.assertFalse(redacted.contains("sk-fixture123456789"), "spaced key removed");
        TestSupport.assertContains(redacted, "<redacted>", "redaction marker");
    }

    private static void handlesCauseCycles() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        String report = CrashReportFormatter.format("cycle", Thread.currentThread(), first);
        TestSupport.assertTrue(report.length() <= 16 * 1024, "cycle remains bounded");
    }

    private static void redactsJsonOauthAndJwtSecrets() {
        String redacted = CrashReportFormatter.redact(
            "{\"accessToken\":\"temporary-value\"} "
                + "https://auth.openai.com/callback?state=temporary-state&code=temporary-code "
                + "eyJabcdefghijk.abcdefghijklmnop.qrstuvwxyzabcd"
        );
        TestSupport.assertFalse(redacted.contains("temporary-value"), "JSON token removed");
        TestSupport.assertFalse(redacted.contains("temporary-state"), "OAuth state removed");
        TestSupport.assertFalse(redacted.contains("temporary-code"), "OAuth code removed");
        TestSupport.assertFalse(redacted.contains("eyJabcdefghijk"), "JWT removed");
    }

    private static void redactsVisibleTextBeforeTruncation() {
        String redacted = CrashReportFormatter.redactVisibleText(
            "prefix apiKey=sk-secretfixture12345 suffix",
            200
        );
        TestSupport.assertContains(redacted, "<redacted>", "visible redaction marker");
        TestSupport.assertFalse(
            redacted.contains("sk-secretfixture12345"),
            "visible secret removed"
        );
        String truncated = CrashReportFormatter.redactVisibleText(
            "0123456789abcdef",
            8
        );
        TestSupport.assertEquals("01234...", truncated, "visible text limit is exact");
    }
}
