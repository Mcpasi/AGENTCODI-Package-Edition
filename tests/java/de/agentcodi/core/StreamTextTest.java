package de.agentcodi.core;

import de.agentcodi.tests.TestSupport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.lang.reflect.Field;
import java.util.regex.Matcher;

public final class StreamTextTest {
    private StreamTextTest() {
    }

    public static int run() {
        matchesWholeTextRedactionAcrossEveryChunkBoundary();
        redactsQuotedPasswordsAcrossEveryChunkBoundary();
        preservesWordBoundariesAndSplitUnicode();
        preservesCompleteUnicodeWhenTruncatingStream();
        truncatesUnicodeSafelyAcrossChunkBoundaries();
        matchesBoundedStreamAndNullRemoval();
        matchesWholeToolSectionFormatting();
        preservesCompleteUnicodeWhenClippingToolSections();
        keepsEarlierSnapshotsImmutableWithoutEagerRendering();
        handlesLongSingleCharacterStreams();
        releasesMatcherInputAfterRedactionAndBoundaryChecks();
        return 11;
    }

    private static void redactsQuotedPasswordsAcrossEveryChunkBoundary() {
        for (char quote : new char[] {'\"', '\''}) {
            char otherQuote = quote == '\"' ? '\'' : '\"';
            String input = "before password=" + quote
                + "fixture-first \\" + quote + " " + otherQuote + " fixture-tail,;&}\nlast\\\\" + quote + " after";
            for (int chunk = 1; chunk <= input.length(); chunk++) {
                for (boolean card : new boolean[] {false, true}) {
                    StreamText stream = StreamText.empty();
                    for (int start = 0; start < input.length(); start += chunk) {
                        int end = Math.min(input.length(), start + chunk);
                        stream = stream.append(input.substring(start, end), 4096, card);
                        assertText(CrashReportFormatter.redactSecrets(input.substring(0, end)), stream,
                            "quoted password chunk " + chunk + " ending at " + end);
                    }
                    assertText("before password=" + quote + "<redacted>" + quote + " after", stream,
                        "the final snapshot hides every password part");
                }
            }
        }
    }

    private static void releasesMatcherInputAfterRedactionAndBoundaryChecks() {
        try {
            Field storage = CrashReportFormatter.class.getDeclaredField("MATCHERS");
            storage.setAccessible(true);
            ThreadLocal<?> local = (ThreadLocal<?>) storage.get(null);
            for (boolean boundary : new boolean[] {false, true}) {
                if (boundary) {
                    CrashReportFormatter.stableRedactedPrefixLength("password=fixture-secret");
                } else {
                    CrashReportFormatter.redactSecrets("password=fixture-secret");
                }
                Object matchers = local.get();
                for (Field field : matchers.getClass().getDeclaredFields()) {
                    if (field.getType() == Matcher.class) {
                        field.setAccessible(true);
                        TestSupport.assertEquals(0, ((Matcher) field.get(matchers)).regionEnd(),
                            "thread-local matcher releases input, including early boundary returns");
                    }
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void matchesWholeTextRedactionAcrossEveryChunkBoundary() {
        String[] fixtures = {
            "before authorization=fixture-secret after",
            "a'AccessToken' : 'fixture-value' z",
            "OPENAI_\n-API\tKEY\r\n=\nfixture-value; after",
            "client-secret=password-fixture refresh_token:fixture-refresh idToken=fixture-id",
            "Bearer\r\nfixture-value, afterwards",
            "https://example.test/?state=fixture-state&code=fixture-code&access_token=fixture-token z",
            "sk-fixture123456789 cwx_fixture123456789 eyJabcdefghijk.abcdefghijklmnop.qrstuvwxyzabcd",
            "apiKey=Bearer next authorization='sk-fixture123456789' password=eyJabcdefghijk.abcdefghijklmnop",
            "password\n\n\t=\n\n\t\"fixture-value\" more text"
        };
        for (String fixture : fixtures) {
            for (int chunk = 1; chunk <= fixture.length(); chunk++) {
                compareChunks(fixture, chunk, 4096, false);
                compareChunks(fixture, chunk, 4096, true);
            }
        }
    }

    private static void preservesWordBoundariesAndSplitUnicode() {
        for (String value : new String[] {
            "ask-fixture123456789 xcwx_fixture123456789 aeyJabcdefghijk.abcdefghijklmnop",
            "é🌍sk-fixture123456789 中文 cwx_fixture123456789 e\u0301\tBearer value",
            "abcsk-fixture123456789 xyzcwx_fixture123456789 eyJabcdefghijk.abcdefghijklmnop.abc",
            "s\nk-abcdefghijk c\nwx_abcdefghijk ey\nJabcdefghijk.abcdefghijklmnop"
        }) {
            for (int chunk = 1; chunk <= 20; chunk++) {
                compareChunks(value, chunk, 4096, false);
            }
        }
    }

    private static void preservesCompleteUnicodeWhenTruncatingStream() {
        String prefix = "before ";
        String marker = "\n… Ausgabe gekürzt …";
        String delta = prefix + "🌍 and enough ordinary text to exceed the stream limit";
        // The retained prefix would end between the emoji's two UTF-16 code units.
        int maximum = prefix.length() + 1 + marker.length();
        StreamText truncated = StreamText.empty().append(delta, maximum, false);
        String text = truncated.text();

        TestSupport.assertTrue(text.length() <= maximum, "truncated output respects the limit");
        TestSupport.assertTrue(text.startsWith(prefix), "text before the emoji is preserved");
        TestSupport.assertTrue(text.endsWith(marker), "truncated output retains its marker");
        TestSupport.assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(text),
            "truncating a stream must not leave half an emoji (an unpaired UTF-16 surrogate)");
    }

    private static void truncatesUnicodeSafelyAcrossChunkBoundaries() {
        String input = "a🌍b😀c ordinary text long enough to require truncation";
        for (boolean removeNulls : new boolean[] {false, true}) {
            for (int maximum = 1; maximum <= input.length() + 1; maximum++) {
                for (int split = 0; split <= input.length(); split++) {
                    String context = "maximum " + maximum + ", split " + split
                        + ", removeNulls " + removeNulls;
                    StreamText first = StreamText.empty().append(
                        input.substring(0, split), maximum, removeNulls);
                    String snapshot = first.text();
                    StreamText result = first.append(input.substring(split), maximum, removeNulls);
                    String text = result.text();

                    TestSupport.assertTrue(text.length() <= maximum, "bounded Unicode: " + context);
                    TestSupport.assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(text),
                        "whole Unicode after truncation: " + context);
                    TestSupport.assertEquals(snapshot, first.text(), "immutable snapshot: " + context);
                    if (input.length() <= maximum) {
                        assertText(input, result, "Unicode that fits is preserved: " + context);
                    } else {
                        TestSupport.assertTrue(result == result.append("😀later", maximum, removeNulls),
                            "truncated output cannot resume in space freed by an omitted emoji: " + context);
                    }
                }
            }
        }
    }

    private static void matchesBoundedStreamAndNullRemoval() {
        Random random = new Random(140014L);
        // Compare legacy BMP behavior here; supplementary characters use the boundary matrix above.
        String[] fragments = {"a", "s", "k-", "cwx_", "eyJ", "abcdefghijk", ".",
            "\n", "\t", "\0", "'", "\"", " ", "Bearer", "password", "=", ":",
            "api", "key", "openai", "-", "_", "accessToken", "?state=", "&code=", "世", "é"};
        for (int maximum : new int[] {1, 3, 8, 19, 33, 128, 1024}) {
            for (boolean card : new boolean[] {false, true}) {
                for (int replay = 0; replay < 60; replay++) {
                    StreamText actual = StreamText.empty();
                    String expected = "";
                    for (int step = 0; step < 60; step++) {
                        String delta = fragments[random.nextInt(fragments.length)];
                        expected = oldAppend(expected, delta, maximum, card);
                        actual = actual.append(delta, maximum, card);
                        if (card) {
                            actual = actual.visibleLimit(maximum);
                        }
                        assertText(expected, actual, "bounded randomized replay");
                    }
                }
            }
        }
    }

    private static void matchesWholeToolSectionFormatting() {
        for (String initial : new String[] {"", "cwd:\n/workspace", "password=", "Bearer ",
            "password=\"", "password='", "password=\"fixture\\",
            "previous\n\nAusgabe:\nold output", "Argumente:\napiKey=fixture-secret"}) {
            for (String label : new String[] {"Ausgabe", "Fortschritt"}) {
                for (int maximum : new int[] {3, 16, 64, 1024}) {
                    String expected = initial;
                    StreamText actual = StreamText.literal(initial);
                    StreamText output = StreamText.empty();
                    for (String delta : new String[] {"prefix ", "api", "Key=", "fixture-value",
                        "\n", "sk-", "fixture123456789", "\nAusgabe:\n", "last"}) {
                        output = output.append(delta, maximum, true).visibleLimit(maximum);
                        expected = oldSection(expected, label, output.text(), maximum);
                        actual = StreamText.section(actual, label, output, maximum);
                        assertText(expected, actual, "tool section and bounds");
                    }
                }
            }
        }
    }

    private static void preservesCompleteUnicodeWhenClippingToolSections() {
        // Match the card-section limit used by CodexSessionController.
        int maximum = 64 * 1024;
        String detail = "Arbeitsverzeichnis:\n/workspace";
        String heading = detail + "\n\nAusgabe:\n";
        char[] padding = new char[maximum - heading.length() - 1];
        Arrays.fill(padding, 'x');
        String prefix = new String(padding);
        String output = prefix + "🌍 trailing output";
        StreamText stream = StreamText.empty().append(output, maximum, true)
            .visibleLimit(maximum);
        TestSupport.assertEquals(output, stream.text(),
            "the complete output fits before the tool-section heading is added");

        // Adding the heading leaves room for only the emoji's first UTF-16 code unit.
        String text = StreamText.section(StreamText.literal(detail), "Ausgabe", stream,
            maximum).text();
        TestSupport.assertTrue(text.length() <= maximum, "tool detail respects the card limit");
        TestSupport.assertTrue(text.startsWith(heading + prefix),
            "tool metadata and output before the emoji are preserved");
        TestSupport.assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(text),
            "clipping a tool section must not leave half an emoji (an unpaired UTF-16 surrogate)");
    }

    private static void keepsEarlierSnapshotsImmutableWithoutEagerRendering() {
        StreamText stream = StreamText.empty();
        List<StreamText> snapshots = new ArrayList<StreamText>();
        List<String> expected = new ArrayList<String>();
        String text = "";
        for (String delta : new String[] {"first\n", "api", "Key=", "fixture", "-secret\n", "last"}) {
            stream = stream.append(delta, 4096, false);
            snapshots.add(stream);
            text = oldAppend(text, delta, 4096, false);
            expected.add(text);
        }
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            assertText(expected.get(index), snapshots.get(index), "old snapshot stays unchanged");
        }
    }

    private static void handlesLongSingleCharacterStreams() {
        StreamText value = StreamText.empty();
        for (int index = 0; index < 128 * 1024; index++) {
            value = value.append("z", 256 * 1024, false);
        }
        TestSupport.assertEquals(128 * 1024, value.length(), "exact accumulated length");
        String rendered = value.text();
        TestSupport.assertEquals(128 * 1024, rendered.length(), "iterative rendering of tiny deltas");
        TestSupport.assertTrue(rendered.charAt(0) == 'z'
            && rendered.charAt(rendered.length() - 1) == 'z', "complete stream");
        TestSupport.assertTrue(rendered == value.text(), "immutable rendered text is reused");
    }

    private static void compareChunks(String value, int chunk, int maximum, boolean card) {
        StreamText actual = StreamText.empty();
        String expected = "";
        for (int start = 0; start < value.length(); start += chunk) {
            String delta = value.substring(start, Math.min(value.length(), start + chunk));
            expected = oldAppend(expected, delta, maximum, card);
            actual = actual.append(delta, maximum, card);
            if (card) {
                actual = actual.visibleLimit(maximum);
            }
            assertText(expected, actual, "chunk size " + chunk + " at " + start);
        }
    }

    private static String oldAppend(String existing, String delta, int maximum, boolean card) {
        String combined = existing;
        if (existing.length() < maximum) {
            int remaining = maximum - existing.length();
            String marker = "\n… Ausgabe gekürzt …";
            combined += delta.length() <= remaining ? delta
                : remaining <= marker.length() ? marker.substring(0, remaining)
                : delta.substring(0, remaining - marker.length()) + marker;
        }
        if (card) {
            combined = combined.replace("\0", "");
            return CrashReportFormatter.redactVisibleText(combined, maximum);
        }
        return CrashReportFormatter.redactSecrets(combined);
    }

    private static String oldSection(String existing, String label, String output, int maximum) {
        int index = existing.indexOf(label + ":\n");
        if (index >= 0) {
            while (index > 0 && existing.charAt(index - 1) == '\n') {
                index--;
            }
            existing = existing.substring(0, index);
        }
        String stream = CrashReportFormatter.redactVisibleText(output, maximum);
        StringBuilder result = new StringBuilder(existing);
        if (!stream.isEmpty() && result.length() < maximum) {
            if (result.length() > 0) {
                result.append("\n\n");
            }
            result.append(label).append(":\n");
            int remaining = Math.max(0, maximum - result.length());
            result.append(stream, 0, Math.min(stream.length(), remaining));
        }
        return CrashReportFormatter.redactVisibleText(result.toString(), maximum);
    }

    private static void assertText(String expected, StreamText actual, String context) {
        TestSupport.assertEquals(expected, actual.text(), context);
        TestSupport.assertEquals(expected.length(), actual.length(), context + " visible length");
    }
}
