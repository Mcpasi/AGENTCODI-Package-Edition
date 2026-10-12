package de.agentcodi.editor.syntax;

import static de.agentcodi.editor.syntax.SyntaxToken.Kind.*;

final class CssLexer {
    static void scan(SyntaxScanner source, int start, int end) {
        int braces = 0;
        for (int cursor = start; cursor < end;) {
            source.checkCancelled(cursor);
            char value = source.text.charAt(cursor);
            int next = cursor + 1;
            if (Character.isWhitespace(value)) { cursor++; continue; }
            if (source.at(cursor, end, "/*")) {
                next = source.until(cursor + 2, end, "*/");
                source.add(cursor, next, COMMENT);
            } else if (value == '\'' || value == '"') {
                next = source.quoted(cursor, end, 1, true, false);
                source.add(cursor, next, STRING);
            } else if (value == '@') {
                next = name(source, next, end);
                source.add(cursor, next, KEYWORD);
            } else if (value == '#' && braces > 0 && next < end
                && Character.digit(source.text.charAt(next), 16) >= 0) {
                while (next < end && Character.digit(source.text.charAt(next), 16) >= 0) { next++; }
                source.add(cursor, next, NUMBER);
            } else if (Character.isDigit(value) || decimalStart(source, cursor, end)
                || ((value == '-' || value == '+') && next < end
                    && (Character.isDigit(source.text.charAt(next)) || decimalStart(source, next, end)))) {
                next = source.number(value == '-' || value == '+' ? cursor + 1 : cursor, end);
                while (next < end && (Character.isLetter(source.text.charAt(next))
                    || source.text.charAt(next) == '%')) { next++; }
                source.add(cursor, next, NUMBER);
            } else if (SyntaxScanner.isIdentifierStart(value) || value == '-' || value == '#' || value == '.') {
                next = name(source, next, end);
                int following = source.space(next, end);
                SyntaxToken.Kind kind = braces == 0 ? TAG : BUILTIN;
                if (braces > 0 && following < end && source.text.charAt(following) == ':') { kind = ATTRIBUTE; }
                else if (following < end && source.text.charAt(following) == '(') { kind = FUNCTION; }
                source.add(cursor, next, kind);
            } else {
                if (value == '{') { braces++; }
                if (value == '}') { braces = Math.max(0, braces - 1); }
                source.add(cursor, next, OPERATOR);
            }
            cursor = next;
        }
    }

    private static int name(SyntaxScanner source, int cursor, int end) {
        while (cursor < end && (SyntaxScanner.isIdentifierPart(source.text.charAt(cursor))
            || source.text.charAt(cursor) == '-')) { source.checkCancelled(cursor++); }
        return cursor;
    }

    private static boolean decimalStart(SyntaxScanner source, int cursor, int end) {
        return cursor + 1 < end && source.text.charAt(cursor) == '.'
            && Character.isDigit(source.text.charAt(cursor + 1));
    }
}
