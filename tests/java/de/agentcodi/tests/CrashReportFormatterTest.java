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
        redactsQuotedSecretsWithEscapesAndUnfinishedValues();
        redactsWholeQuotedPasswordsInReportsAndChat();
        redactsVisibleTextBeforeTruncation();
        handlesCauseCycles();
        keepsConcurrentRedactionIndependent();
        return 8;
    }

    private static void redactsQuotedSecretsWithEscapesAndUnfinishedValues() {
        for (char quote : new char[] {'\"', '\''}) {
            String prefix = "before client_secret=" + quote;
            char otherQuote = quote == '\"' ? '\'' : '\"';
            String secret = "fixture-first \\" + quote + " " + otherQuote + " fixture-tail,;&}\nlast\\\\";
            String input = prefix + secret + quote + " after";
            String expected = prefix + "<redacted>" + quote + " after";
            TestSupport.assertEquals(expected, CrashReportFormatter.redactSecrets(input),
                "escaped quotes and opposite quote characters cannot end a secret early");
            TestSupport.assertEquals(expected, CrashReportFormatter.redactSecrets(expected),
                "masking a quoted credential twice leaves its context intact");
            TestSupport.assertEquals(prefix + "<redacted>",
                CrashReportFormatter.redactSecrets(prefix + "fixture-first fixture-tail"),
                "an unfinished quoted value stays masked to the end of the input");
            TestSupport.assertEquals(prefix + "<redacted>\\",
                CrashReportFormatter.redactSecrets(prefix + "fixture-first\\"),
                "an unfinished escape retains only its escape state");
        }
        TestSupport.assertEquals("password=\"\" after", CrashReportFormatter.redactSecrets("password=\"\" after"),
            "empty quoted placeholders remain readable");
        StringBuilder longValue = new StringBuilder("password=\"");
        for (int index = 0; index < 64 * 1024; index++) {
            longValue.append('x');
        }
        longValue.append("\" after");
        TestSupport.assertEquals("password=\"<redacted>\" after",
            CrashReportFormatter.redactSecrets(longValue.toString()),
            "long quoted values are masked without exhausting the regex stack");
    }

    private static void redactsWholeQuotedPasswordsInReportsAndChat() {
        String input = "password=\"fixture-first fixture-tail,;&}\" operation=connect";
        String expected = "password=\"<redacted>\" operation=connect";
        String report = CrashReportFormatter.format("fixture", Thread.currentThread(),
            new IllegalStateException(input));
        TestSupport.assertContains(report, "Meldung: " + expected, "crash report masks the whole value");
        TestSupport.assertFalse(report.contains("fixture-tail"), "report retains no password suffix");
        de.agentcodi.core.ChatMessage message = new de.agentcodi.core.ChatMessage(
            "fixture", de.agentcodi.core.ChatMessage.Role.ASSISTANT, input, false);
        TestSupport.assertEquals(expected, message.getText(), "chat masks the whole quoted value");
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
