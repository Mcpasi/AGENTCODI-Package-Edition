package de.agentcodi.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CrashReportFormatter {
    private static final int MAX_REPORT_CHARS = 16 * 1024;
    private static final int MAX_MESSAGE_CHARS = 4 * 1024;
    private static final int MAX_CAUSES = 4;
    private static final int MAX_FRAMES_PER_CAUSE = 48;
    private static final Pattern NAMED_SECRET = Pattern.compile(
        "(?i)([\\\"']?(?:authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|"
            + "(?:openai[_\\s-]*)?api[_\\s-]?key|client[_-]?secret|password)"
            + "[\\\"']?\\s*[:=]\\s*[\\\"']?)"
            + "[^\\\"'\\s,;&}]+"
    );
    private static final Pattern BEARER_SECRET = Pattern.compile(
        "(?i)bearer\\s+[^\\s,;]+"
    );
    private static final Pattern TOKEN_SHAPE = Pattern.compile(
        "\\b(?:cwx_|sk-)[A-Za-z0-9_-]{8,}"
    );
    private static final Pattern OAUTH_QUERY_SECRET = Pattern.compile(
        "(?i)([?&](?:code|state|access_token|refresh_token|id_token)=)[^&\\s]+"
    );
    private static final Pattern JWT_SHAPE = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{8,})?"
    );
    private static final ThreadLocal<RedactionMatchers> MATCHERS =
        new ThreadLocal<RedactionMatchers>() {
            @Override protected RedactionMatchers initialValue() {
                return new RedactionMatchers();
            }
        };

    private CrashReportFormatter() {
    }

    public static String format(String source, Thread thread, Throwable error) {
        StringBuilder report = new StringBuilder(2048);
        append(report, BuildIdentity.summary());
        append(report, "Zeit (Unix ms): " + System.currentTimeMillis());
        append(report, "Quelle: " + redact(source));
        append(report, "Thread: " + redact(thread == null ? "unknown" : thread.getName()));

        if (error == null) {
            append(report, "Fehler: unknown");
            return report.toString();
        }

        Set<Throwable> visited = Collections.newSetFromMap(
            new IdentityHashMap<Throwable, Boolean>()
        );
        Throwable current = error;
        int causeIndex = 0;
        while (current != null
            && causeIndex < MAX_CAUSES
            && visited.add(current)
            && report.length() < MAX_REPORT_CHARS) {
            String prefix = causeIndex == 0 ? "Fehler: " : "Verursacht durch: ";
            append(report, prefix + current.getClass().getName());
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) {
                append(report, "Meldung: " + redact(current.getMessage()));
            }

            StackTraceElement[] frames = current.getStackTrace();
            int frameCount = Math.min(frames.length, MAX_FRAMES_PER_CAUSE);
            for (int index = 0; index < frameCount; index++) {
                append(report, "  at " + redact(frames[index].toString()));
            }
            if (frames.length > frameCount) {
                append(report, "  ... " + (frames.length - frameCount) + " weitere Frames");
            }
            current = current.getCause();
            causeIndex++;
        }
        if (current != null) {
            append(report, "... weitere Ursachen abgeschnitten");
        }
        return report.length() <= MAX_REPORT_CHARS
            ? report.toString()
            : report.substring(0, MAX_REPORT_CHARS);
    }

    public static String redact(String value) {
        return redactVisibleText(value, MAX_MESSAGE_CHARS);
    }

    public static String redactSecrets(String value) {
        if (value == null) {
            return "";
        }
        RedactionMatchers matchers = MATCHERS.get();
        try {
            String redacted = matchers.named.reset(value).replaceAll("$1<redacted>");
            redacted = matchers.bearer.reset(redacted).replaceAll("Bearer <redacted>");
            redacted = matchers.query.reset(redacted).replaceAll("$1<redacted>");
            redacted = matchers.jwt.reset(redacted).replaceAll("<redacted-token>");
            return matchers.token.reset(redacted).replaceAll("<redacted-token>");
        } finally {
            // Reuse only matcher storage, never retain input on a long-lived thread.
            matchers.clear();
        }
    }

    public static String redactVisibleText(String value, int maximumCharacters) {
        if (value == null) {
            return "";
        }
        if (maximumCharacters <= 0) {
            return "";
        }
        String redacted = redactSecrets(value);
        if (redacted.length() <= maximumCharacters) {
            return redacted;
        }
        if (maximumCharacters <= 3) {
            return redacted.substring(0, maximumCharacters);
        }
        return redacted.substring(0, maximumCharacters - 3) + "...";
    }

    /**
     * A redacted stream may share everything before this suffix with older snapshots.
     * Keep every match/prefix that could change on append, plus its preceding code point
     * for word-boundary checks. Opaque matcher regions deliberately retain token prefixes
     * even inside words; the actual redaction still sees their preceding character.
     */
    static int stableRedactedPrefixLength(String value) {
        int end = value.length();
        RedactionMatchers matchers = MATCHERS.get();
        try {
            matchers.reset(value);
            for (int index = 0; index < end; index++) {
                char character = value.charAt(index);
                char lower = character >= 'A' && character <= 'Z'
                    ? (char) (character + ('a' - 'A')) : character;
                if (((lower == 'a' || lower == 'r' || lower == 'i' || lower == 'o'
                    || lower == 'c' || lower == 'p' || character == '\"' || character == '\'')
                        && mayExtend(matchers.named, index, end))
                    || (lower == 'b' && mayExtend(matchers.bearer, index, end))
                    || ((character == '?' || character == '&') && mayExtend(matchers.query, index, end))
                    || (character == 'e' && mayExtend(matchers.jwt, index, end))
                    || ((character == 'c' || character == 's') && mayExtend(matchers.token, index, end))) {
                    return index == 0 ? 0 : value.offsetByCodePoints(index, -1);
                }
            }
            return end == 0 ? 0 : value.offsetByCodePoints(end, -1);
        } finally {
            matchers.clear();
        }
    }

    private static boolean mayExtend(Matcher matcher, int start, int end) {
        matcher.region(start, end).lookingAt();
        return matcher.hitEnd();
    }

    private static final class RedactionMatchers {
        private final Matcher named = NAMED_SECRET.matcher("");
        private final Matcher bearer = BEARER_SECRET.matcher("");
        private final Matcher query = OAUTH_QUERY_SECRET.matcher("");
        private final Matcher jwt = JWT_SHAPE.matcher("");
        private final Matcher token = TOKEN_SHAPE.matcher("");

        private void reset(String value) {
            named.reset(value);
            bearer.reset(value);
            query.reset(value);
            jwt.reset(value);
            token.reset(value);
        }

        private void clear() {
            reset("");
        }
    }

    private static void append(StringBuilder report, String line) {
        if (report.length() >= MAX_REPORT_CHARS) {
            return;
        }
        String safeLine = line == null ? "" : line;
        int remaining = MAX_REPORT_CHARS - report.length();
        if (safeLine.length() + 1 <= remaining) {
            report.append(safeLine).append('\n');
            return;
        }
        int characters = Math.max(0, remaining - 1);
        report.append(safeLine, 0, Math.min(characters, safeLine.length())).append('\n');
    }
}
