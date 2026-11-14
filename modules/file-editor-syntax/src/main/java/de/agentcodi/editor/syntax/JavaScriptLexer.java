package de.agentcodi.editor.syntax;

import java.util.ArrayDeque;
import java.util.Set;

import static de.agentcodi.editor.syntax.SyntaxToken.Kind.*;

final class JavaScriptLexer {
    private static final Set<String> KEYWORDS = SyntaxScanner.words(
        "async await break case catch class const continue debugger default delete do else export extends "
        + "false finally for function if import in instanceof let new null of return static super switch "
        + "this throw true try typeof var void while with yield");
    private static final Set<String> BUILTINS = SyntaxScanner.words(
        "Array BigInt Boolean Date Error Infinity JSON Map Math NaN Number Object Promise RegExp Set "
        + "String Symbol WeakMap WeakSet console document globalThis undefined window");
    private static final Set<String> CONTROL_CONDITIONS = SyntaxScanner.words("if while for with switch catch");
    private static final Set<String> CONTROL_BLOCK_PREFIXES = SyntaxScanner.words("else do try finally catch");

    private static final class Brace {
        final boolean statement;
        final int parentheses;

        Brace(boolean statement, int parentheses) {
            this.statement = statement;
            this.parentheses = parentheses;
        }
    }

    static void scan(SyntaxScanner source, int start, int end) { code(source, start, end, false, 0); }

    private static int code(SyntaxScanner source, int start, int end, boolean interpolation, int depth) {
        boolean expression = true;
        boolean statement = !interpolation;
        boolean functionDeclaration = false;
        boolean member = false;
        boolean controlCondition = false;
        boolean controlBlock = false;
        ArrayDeque<Boolean> parentheses = new ArrayDeque<>();
        ArrayDeque<Brace> braces = new ArrayDeque<>();
        int cursor = start;
        while (cursor < end) {
            source.checkCancelled(cursor);
            int next = cursor + 1;
            char value = source.text.charAt(cursor);
            if (Character.isWhitespace(value)) { cursor++; continue; }
            if (source.at(cursor, end, "//") || (cursor == 0 && source.at(cursor, end, "#!"))) {
                next = source.lineEnd(cursor, end);
                source.add(cursor, next, COMMENT);
            } else if (source.at(cursor, end, "/*")) {
                next = source.until(cursor + 2, end, "*/");
                source.add(cursor, next, COMMENT);
            } else if (value == '\'' || value == '"') {
                next = source.quoted(cursor, end, 1, false, false);
                source.add(cursor, next, STRING);
                expression = false;
                statement = functionDeclaration = false;
                member = controlCondition = controlBlock = false;
            } else if (value == '`') {
                next = template(source, cursor, end, depth);
                expression = false;
                statement = functionDeclaration = false;
                member = controlCondition = controlBlock = false;
            } else if (value == '/' && expression && (next = regexEnd(source, cursor, end)) > cursor + 1) {
                source.add(cursor, next, STRING);
                expression = false;
                statement = functionDeclaration = false;
                member = controlCondition = controlBlock = false;
            } else if (Character.isDigit(value) || (value == '.' && next < end
                && Character.isDigit(source.text.charAt(next)))) {
                next = source.number(cursor, end);
                if (next < end && source.text.charAt(next) == 'n') { next++; }
                source.add(cursor, next, NUMBER);
                expression = false;
                statement = functionDeclaration = false;
                member = controlCondition = controlBlock = false;
            } else if (SyntaxScanner.isIdentifierStart(value)) {
                next = source.identifier(next, end);
                String word = source.text.substring(cursor, next);
                if (!member && word.equals("function")) { functionDeclaration = statement; }
                if (!member && KEYWORDS.contains(word)) {
                    source.add(cursor, next, KEYWORD);
                    expression = !(word.equals("true") || word.equals("false") || word.equals("null")
                        || word.equals("this") || word.equals("super"));
                    controlCondition = CONTROL_CONDITIONS.contains(word)
                        || (controlCondition && word.equals("await"));
                } else {
                    int following = source.space(next, end);
                    if (!member && BUILTINS.contains(word)) { source.add(cursor, next, BUILTIN); }
                    else if (following < end && source.text.charAt(following) == '(') {
                        source.add(cursor, next, FUNCTION);
                    }
                    expression = false;
                    controlCondition = false;
                }
                controlBlock = !member && CONTROL_BLOCK_PREFIXES.contains(word);
                // Keep declaration prefixes across async/export/default, names and trivia.
                statement = controlBlock || (!member && statement
                    && (word.equals("async") || word.equals("export") || word.equals("default")));
                member = false;
            } else {
                if (value == '}' && interpolation && braces.isEmpty()) { return cursor; }
                source.add(cursor, cursor + 1, OPERATOR);
                expression = value != ')' && value != ']' && value != '}';
                // Closing a control block or function declaration permits a regex statement.
                // Object literals and function/arrow expressions still permit division.
                if (value == '{') { braces.addLast(new Brace(controlBlock, parentheses.size())); }
                if (value == '}' && !braces.isEmpty()) { expression = braces.removeLast().statement; }
                // Declaration parameters, like control conditions, precede a statement body.
                // Calls and grouped values still end an expression and permit division.
                if (value == '(') { parentheses.addLast(controlCondition || functionDeclaration); }
                if (value == ')' && !parentheses.isEmpty()) { expression = parentheses.removeLast(); }
                controlBlock = value == ')' && expression;
                int statementParentheses = braces.isEmpty() ? 0 : braces.peekLast().parentheses;
                // A semicolon inside for (...) does not begin a declaration.
                statement = value == '{' || ((value == ')' || value == '}') && expression)
                    || (value == ';' && parentheses.size() == statementParentheses);
                functionDeclaration = functionDeclaration && value == '*';
                member = value == '.';
                controlCondition = false;
                // Postfix ++/-- must not turn a following division into a regex.
                if (source.at(cursor, end, "...")) {
                    source.add(cursor + 1, cursor + 3, OPERATOR);
                    next = cursor + 3;
                    member = false;
                } else if ((value == '+' || value == '-') && source.at(cursor, end, "" + value + value)) {
                    source.add(cursor + 1, cursor + 2, OPERATOR);
                    next = cursor + 2;
                    expression = false;
                } else { next = cursor + 1; }
            }
            cursor = next;
        }
        return cursor;
    }

    private static int template(SyntaxScanner source, int start, int end, int depth) {
        int chunk = start;
        int cursor = start + 1;
        while (cursor < end) {
            source.checkCancelled(cursor);
            char value = source.text.charAt(cursor);
            if (value == '\\') { cursor = Math.min(end, cursor + 2); continue; }
            if (value == '`') { source.add(chunk, cursor + 1, STRING); return cursor + 1; }
            if (source.at(cursor, end, "${") && depth < 64) {
                source.add(chunk, cursor, STRING);
                source.add(cursor, cursor + 2, OPERATOR);
                cursor = code(source, cursor + 2, end, true, depth + 1);
                if (cursor == end) { return end; }
                source.add(cursor, cursor + 1, OPERATOR);
                chunk = ++cursor;
            } else { cursor++; }
        }
        source.add(chunk, end, STRING);
        return end;
    }

    private static int regexEnd(SyntaxScanner source, int start, int end) {
        boolean characterClass = false;
        for (int cursor = start + 1; cursor < end; cursor++) {
            source.checkCancelled(cursor);
            char value = source.text.charAt(cursor);
            if (value == '\n' || value == '\r') { break; }
            if (value == '\\') { cursor++; continue; }
            if (value == '[') { characterClass = true; }
            if (value == ']') { characterClass = false; }
            if (value == '/' && !characterClass) {
                return source.identifier(cursor + 1, end);
            }
        }
        return start + 1;
    }
}
