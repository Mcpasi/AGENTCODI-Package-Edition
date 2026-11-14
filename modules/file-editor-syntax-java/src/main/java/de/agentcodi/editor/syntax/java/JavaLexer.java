package de.agentcodi.editor.syntax.java;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Stateless Java highlighting, independent of Android and the editor's token model. */
public final class JavaLexer {
    public enum Kind { COMMENT, KEYWORD, STRING, NUMBER, OPERATOR, FUNCTION, BUILTIN, ATTRIBUTE }

    public interface TokenSink {
        void add(int start, int end, Kind kind);
    }

    private static final Set<String> KEYWORDS = words(
        "abstract assert boolean break byte case catch char class const continue default do double "
        + "else enum extends final finally float for goto if implements import instanceof int interface "
        + "long native new package private protected public return short static strictfp super switch "
        + "synchronized this throw throws transient try void volatile while _ true false null");
    private static final Set<String> CONTEXTUAL = words(
        "exports module open opens permits provides record requires sealed to transitive uses var when with yield");
    private static final Set<String> BUILTINS = words(
        "Boolean Byte Character Class Double Enum Exception Float Integer Long Math Number Object "
        + "Record RuntimeException Short String StringBuffer StringBuilder System Thread Throwable Void");
    private static final String[] OPERATORS = {
        ">>>=", "<<=", ">>=", ">>>", "...", "==", "<=", ">=", "!=", "&&", "||", "++", "--",
        "<<", ">>", "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", "->", "::"
    };

    private final String text;
    private final int end;
    private final TokenSink sink;

    private JavaLexer(String text, TokenSink sink) {
        this.text = text;
        this.end = text.length();
        this.sink = sink;
    }

    /** Emits ordered, non-overlapping UTF-16 ranges without modifying the source. */
    public static void scan(String text, TokenSink sink) {
        if (text == null || sink == null) { throw new IllegalArgumentException(); }
        new JavaLexer(text, sink).scan();
    }

    private void scan() {
        boolean member = false;
        boolean methodReference = false;
        for (int cursor = 0; cursor < end;) {
            checkCancelled();
            char value = text.charAt(cursor);
            int next = cursor + 1;
            if (Character.isWhitespace(value)) { cursor++; continue; }
            if (at(cursor, "//")) {
                next = lineEnd(next + 1);
                sink.add(cursor, next, Kind.COMMENT);
            } else if (at(cursor, "/*")) {
                next = commentEnd(next + 1);
                sink.add(cursor, next, Kind.COMMENT);
            } else if (value == '\'' || value == '"') {
                next = quoted(cursor, value == '"' && textBlock(cursor) ? 3 : 1);
                sink.add(cursor, next, Kind.STRING);
                member = methodReference = false;
            } else if (digit(value, 10) || (value == '.' && next < end && digit(text.charAt(next), 10))) {
                next = number(cursor);
                sink.add(cursor, next, Kind.NUMBER);
                member = methodReference = false;
            } else if (value == '@' && next < end && Character.isJavaIdentifierStart(text.codePointAt(next))
                && !text.substring(next, identifier(next)).equals("interface")) {
                next = identifier(next);
                while (next + 1 < end && text.charAt(next) == '.'
                    && Character.isJavaIdentifierStart(text.codePointAt(next + 1))) {
                    next = identifier(next + 1);
                }
                sink.add(cursor, next, Kind.ATTRIBUTE);
                member = methodReference = false;
            } else if (Character.isJavaIdentifierStart(text.codePointAt(cursor))) {
                next = identifier(cursor);
                String word = text.substring(cursor, next);
                int following = trivia(next);
                boolean call = following < end && text.charAt(following) == '(';
                if (KEYWORDS.contains(word)) { sink.add(cursor, next, Kind.KEYWORD); }
                else if (methodReference) { sink.add(cursor, next, Kind.FUNCTION); }
                else if (!member && word.equals("non") && at(next, "-sealed")
                    && (next + 7 == end || !Character.isJavaIdentifierPart(text.codePointAt(next + 7)))) {
                    next += 7;
                    sink.add(cursor, next, Kind.KEYWORD);
                } else if (!member && (!call || word.equals("yield") || word.equals("when")) && CONTEXTUAL.contains(word)) {
                    sink.add(cursor, next, Kind.KEYWORD);
                } else if (BUILTINS.contains(word)) { sink.add(cursor, next, Kind.BUILTIN); }
                else if (call) { sink.add(cursor, next, Kind.FUNCTION); }
                member = methodReference = false;
            } else {
                for (String operator : OPERATORS) {
                    if (at(cursor, operator)) { next = cursor + operator.length(); break; }
                }
                sink.add(cursor, next, Kind.OPERATOR);
                member = value == '.' && next == cursor + 1;
                methodReference = at(cursor, "::");
            }
            cursor = next;
        }
    }

    private int identifier(int cursor) {
        while (cursor < end && Character.isJavaIdentifierPart(text.codePointAt(cursor))) {
            checkCancelled();
            cursor += Character.charCount(text.codePointAt(cursor));
        }
        return cursor;
    }

    private int lineEnd(int cursor) {
        while (cursor < end && text.charAt(cursor) != '\r' && text.charAt(cursor) != '\n') {
            checkCancelled();
            cursor++;
        }
        return cursor;
    }

    private int commentEnd(int cursor) {
        while (cursor < end) {
            checkCancelled();
            if (at(cursor, "*/")) { return cursor + 2; }
            cursor++;
        }
        return end;
    }

    private int trivia(int cursor) {
        while (cursor < end) {
            checkCancelled();
            if (Character.isWhitespace(text.charAt(cursor))) { cursor++; }
            else if (at(cursor, "/*")) { cursor = commentEnd(cursor + 2); }
            else if (at(cursor, "//")) { cursor = lineEnd(cursor + 2); }
            else { break; }
        }
        return cursor;
    }

    private boolean textBlock(int start) {
        if (!at(start, "\"\"\"")) { return false; }
        int cursor = start + 3;
        while (cursor < end && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t'
            || text.charAt(cursor) == '\f')) { checkCancelled(); cursor++; }
        return cursor < end && (text.charAt(cursor) == '\r' || text.charAt(cursor) == '\n');
    }

    private int quoted(int start, int delimiter) {
        char quote = text.charAt(start);
        for (int cursor = start + delimiter; cursor < end;) {
            checkCancelled();
            char value = text.charAt(cursor);
            if (delimiter == 1 && (value == '\r' || value == '\n')) { return cursor; }
            if (value == '\\') {
                cursor++;
                // Only text blocks allow a backslash followed by a physical line break.
                if (cursor < end && (delimiter == 3 || (text.charAt(cursor) != '\r'
                    && text.charAt(cursor) != '\n'))) { cursor++; }
            } else if (value == quote && (delimiter == 1 || at(cursor, "\"\"\""))) {
                return cursor + delimiter;
            } else { cursor++; }
        }
        return end;
    }

    private int number(int start) {
        if (at(start, "0x") || at(start, "0X")) {
            int integer = digits(start + 2, 16);
            int cursor = integer;
            boolean mantissa = integer > start + 2;
            if (cursor < end && text.charAt(cursor) == '.') {
                cursor = digits(cursor + 1, 16);
                mantissa |= cursor > integer + 1;
            }
            int exponent = exponent(cursor, 'p', 'P');
            if (mantissa && exponent > cursor) { return suffix(exponent, "fFdD"); }
            return integer > start + 2 ? suffix(integer, "lL") : start + 1;
        }
        if (at(start, "0b") || at(start, "0B")) {
            int cursor = digits(start + 2, 2);
            return cursor > start + 2 ? suffix(cursor, "lL") : start + 1;
        }
        int cursor = digits(start, 10);
        boolean floating = cursor < end && text.charAt(cursor) == '.';
        if (floating) { cursor = digits(cursor + 1, 10); }
        int exponent = exponent(cursor, 'e', 'E');
        floating |= exponent > cursor;
        cursor = exponent;
        int suffixed = suffix(cursor, "fFdD");
        if (floating || suffixed > cursor) { return suffixed; }
        if (text.charAt(start) == '0') { cursor = digits(start, 8); }
        return suffix(cursor, "lL");
    }

    /** Underscores belong to a number only when surrounded by digits. */
    private int digits(int start, int radix) {
        int cursor = start;
        int lastDigit = start;
        while (cursor < end) {
            checkCancelled();
            char value = text.charAt(cursor);
            if (digit(value, radix)) { lastDigit = ++cursor; }
            else if (value == '_' && cursor > start) { cursor++; }
            else { break; }
        }
        return lastDigit;
    }

    private int exponent(int cursor, char lower, char upper) {
        if (cursor >= end || (text.charAt(cursor) != lower && text.charAt(cursor) != upper)) { return cursor; }
        int start = cursor + 1;
        if (start < end && (text.charAt(start) == '+' || text.charAt(start) == '-')) { start++; }
        int next = digits(start, 10);
        return next > start ? next : cursor;
    }

    private int suffix(int cursor, String suffixes) {
        return cursor < end && suffixes.indexOf(text.charAt(cursor)) >= 0 ? cursor + 1 : cursor;
    }

    private boolean at(int cursor, String value) { return text.startsWith(value, cursor); }

    private static boolean digit(char value, int radix) {
        return (value >= '0' && value <= '9' && value - '0' < radix)
            || (radix == 16 && ((value >= 'a' && value <= 'f') || (value >= 'A' && value <= 'F')));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) { throw new CancellationException(); }
    }

    private static Set<String> words(String text) { return new HashSet<>(Arrays.asList(text.split(" "))); }
}
