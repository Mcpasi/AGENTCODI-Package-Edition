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
    private static final Set<String> TYPE_CONTEXT = words(
        "class enum interface record extends implements permits throws instanceof new");
    private static final Set<String> PRIMITIVES = words("boolean byte char double float int long short");
    private static final String[] OPERATORS = {
        ">>>=", "<<=", ">>=", ">>>", "...", "==", "<=", ">=", "!=", "&&", "||", "++", "--",
        "<<", ">>", "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", "->", "::"
    };

    private final String text;
    private final int end;
    private final TokenSink sink;

    private JavaLexer(String text, TokenSink sink) {
        this.text = unicodeLineTerminators(text);
        this.end = text.length();
        this.sink = sink;
    }

    /** Emits ordered, non-overlapping UTF-16 ranges without modifying the source. */
    public static void scan(String text, TokenSink sink) {
        if (text == null || sink == null) { throw new IllegalArgumentException(); }
        new JavaLexer(text, sink).scan();
    }

    /** Recognizes escaped CR/LF in a same-length scan view, preserving raw UTF-16 offsets. */
    private static String unicodeLineTerminators(String source) {
        char[] view = null;
        int backslashes = 0;
        boolean translated = false;
        for (int cursor = source.indexOf('\\'); cursor >= 0 && cursor < source.length();) {
            checkCancelled();
            char value = source.charAt(cursor);
            int next = cursor + 1;
            boolean unicode = false;
            if (value == '\\' && (translated || (backslashes & 1) == 0)) {
                int digits = next;
                while (digits < source.length() && source.charAt(digits) == 'u') {
                    checkCancelled();
                    digits++;
                }
                if (digits > next && digits <= source.length() - 4) {
                    int decoded = 0;
                    int offset = digits;
                    while (offset < digits + 4 && digit(source.charAt(offset), 16)) {
                        decoded = decoded * 16 + Character.digit(source.charAt(offset++), 16);
                    }
                    if (offset == digits + 4) {
                        value = (char) decoded;
                        next = offset;
                        unicode = true;
                    }
                }
            }
            if (unicode && (value == '\r' || value == '\n')) {
                if (view == null) { view = source.toCharArray(); }
                view[cursor] = value;
                Arrays.fill(view, cursor + 1, next, ' ');
            }
            // JLS 3.3: eligibility also depends on backslashes produced by Unicode escapes.
            backslashes = value == '\\' ? backslashes + 1 : 0;
            translated = unicode;
            cursor = next;
        }
        return view == null ? source : new String(view);
    }

    private void scan() {
        boolean member = false;
        boolean typePending = false;
        boolean variablePending = false;
        Set<String> typeNames = new HashSet<>(BUILTINS);
        // -1: no method context; 0: method name expected; positive: nested type arguments.
        int methodNameDepth = -1;
        boolean annotationArgumentsPending = false;
        int annotationParentheses = 0;
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
                member = false;
                typePending = variablePending = false;
                if (annotationParentheses == 0) { methodNameDepth = -1; }
            } else if (digit(value, 10) || (value == '.' && next < end && digit(text.charAt(next), 10))) {
                next = number(cursor);
                sink.add(cursor, next, Kind.NUMBER);
                member = false;
                typePending = variablePending = false;
                if (annotationParentheses == 0) { methodNameDepth = -1; }
            } else if (value == '@' && next < end && Character.isJavaIdentifierStart(text.codePointAt(next))
                && !text.substring(next, identifier(next)).equals("interface")) {
                next = identifier(next);
                while (next + 1 < end && text.charAt(next) == '.'
                    && Character.isJavaIdentifierStart(text.codePointAt(next + 1))) {
                    next = identifier(next + 1);
                }
                sink.add(cursor, next, Kind.ATTRIBUTE);
                member = false;
                if (methodNameDepth == 0) { methodNameDepth = -1; }
                if (methodNameDepth > 0 && annotationParentheses == 0) {
                    int following = trivia(next);
                    annotationArgumentsPending = following < end && text.charAt(following) == '(';
                }
            } else if (Character.isJavaIdentifierStart(text.codePointAt(cursor))) {
                next = identifier(cursor);
                String word = text.substring(cursor, next);
                int following = trivia(next);
                boolean call = following < end && text.charAt(following) == '(';
                boolean qualifier = typePending && following < end && text.charAt(following) == '.';
                boolean type = false;
                if (KEYWORDS.contains(word)) { sink.add(cursor, next, Kind.KEYWORD); }
                else if (methodNameDepth == 0) { sink.add(cursor, next, Kind.FUNCTION); }
                else if (!member && word.equals("non") && at(next, "-sealed")
                    && (next + 7 == end || !Character.isJavaIdentifierPart(text.codePointAt(next + 7)))) {
                    next += 7;
                    sink.add(cursor, next, Kind.KEYWORD);
                } else if (!member && (!call || word.equals("yield") || word.equals("when")) && CONTEXTUAL.contains(word)) {
                    sink.add(cursor, next, Kind.KEYWORD);
                } else if (!variablePending && (BUILTINS.contains(word) || (typePending && !qualifier))) {
                    type = true;
                    sink.add(cursor, next, Kind.BUILTIN);
                } else if (call) { sink.add(cursor, next, Kind.FUNCTION); }
                else if (!variablePending && (typeNames.contains(word) || Character.isUpperCase(word.codePointAt(0))
                    || Character.isTitleCase(word.codePointAt(0)))) {
                    // Java type names conventionally start with a capital, including imported,
                    // qualified and generic types outside the small java.lang builtin list.
                    type = true;
                    sink.add(cursor, next, Kind.BUILTIN);
                }
                if (type) { typeNames.add(word); }
                typePending = qualifier || (!member && TYPE_CONTEXT.contains(word));
                variablePending = type || PRIMITIVES.contains(word);
                member = false;
                if (methodNameDepth == 0) { methodNameDepth = -1; }
            } else {
                for (String operator : OPERATORS) {
                    if (at(cursor, operator)) { next = cursor + operator.length(); break; }
                }
                sink.add(cursor, next, Kind.OPERATOR);
                if (annotationArgumentsPending && value == '(') {
                    annotationArgumentsPending = false;
                    annotationParentheses = 1;
                } else if (annotationParentheses > 0) {
                    // Annotation expressions must not change the surrounding type-argument depth.
                    if (value == '(') { annotationParentheses++; }
                    else if (value == ')') { annotationParentheses--; }
                    else if (value == ';') {
                        // Recover at a statement boundary while an annotation is still being edited.
                        annotationParentheses = 0;
                        methodNameDepth = -1;
                    }
                } else if (at(cursor, "::")) { methodNameDepth = 0; }
                else if ((member || methodNameDepth >= 0) && value == '<' && next == cursor + 1) {
                    // Explicit type arguments follow either a qualifier's dot or a method reference.
                    methodNameDepth = Math.max(0, methodNameDepth) + 1;
                } else if (methodNameDepth > 0 && value == '>' && text.charAt(next - 1) == '>') {
                    // >> and >>> close nested type arguments before the method name.
                    methodNameDepth = Math.max(-1, methodNameDepth - (next - cursor));
                } else if (methodNameDepth <= 0 || ".,?[]".indexOf(value) < 0) {
                    methodNameDepth = -1;
                }
                member = value == '.' && next == cursor + 1;
                if (!member) { typePending = false; }
                if (value != '[' && value != ']') { variablePending = false; }
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
