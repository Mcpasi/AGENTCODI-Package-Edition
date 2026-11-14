package de.agentcodi.tests;

import de.agentcodi.editor.syntax.SyntaxHighlighter;
import de.agentcodi.editor.syntax.SyntaxLanguage;
import de.agentcodi.editor.syntax.SyntaxToken;
import de.agentcodi.editor.syntax.java.JavaLexer;

import java.util.List;
import java.util.concurrent.CancellationException;

import static de.agentcodi.editor.syntax.SyntaxLanguage.JAVA;
import static de.agentcodi.editor.syntax.SyntaxLanguage.PLAIN;
import static de.agentcodi.editor.syntax.SyntaxToken.Kind.*;

public final class WorkspaceJavaSyntaxTest {
    private WorkspaceJavaSyntaxTest() { }

    public static int run() {
        detectsJavaFileNames();
        highlightsDeclarationsAndLiteralKeywords();
        highlightsModernAndModuleKeywords();
        commentsAndStringsTakePrecedence();
        characterAndStringEscapesKeepTheirDelimiters();
        textBlocksKeepCommentsAndEscapedQuotesLiteral();
        ordinaryStringsRecoverAtPhysicalLineBreaks();
        recognizesJavaNumericLiterals();
        numbersKeepIdentifierAndOperatorBoundaries();
        recognizesAnnotationsAndAnnotationTypeDeclarations();
        recognizesMethodsConstructorsAndReferences();
        recognizesJavaUnicodeIdentifiersWithoutSplittingKeywords();
        recognizesJavaOperatorsWithoutJavascriptRegexes();
        toleratesEveryEditingPrefixAndRetokenizes();
        standaloneModulePreservesOffsetsAndRejectsMissingInputs();
        cancelsSupersededJavaScans();
        return 16;
    }

    private static void detectsJavaFileNames() {
        for (String path : new String[] {"Main.java", "src/Main.JAVA", "C:\\src\\Grüße.Java",
            "/tmp/module-info.java", "package-info.java"}) {
            TestSupport.assertEquals(JAVA, SyntaxLanguage.fromPath(path), "Java file extension: " + path);
            token(SyntaxHighlighter.highlight(SyntaxLanguage.fromPath(path), "class Demo {}"), "class Demo {}", "class", KEYWORD);
        }
        for (String path : new String[] {"java", "Main.jav", "Main.java.bak", "Main.java/", "dir.java/plain",
            "dir.java\\plain", "Main.class", "Main.jar", "Main.kt"}) {
            TestSupport.assertEquals(PLAIN, SyntaxLanguage.fromPath(path), "no Java extension guessing: " + path);
        }
    }

    private static void highlightsDeclarationsAndLiteralKeywords() {
        String text = "package demo; import java.util.List; public abstract class Demo<T> extends Object "
            + "implements Runnable { private static final long n = 2L; volatile boolean ready = true; "
            + "protected synchronized void run() throws Exception { if (ready && n instanceof Long) "
            + "return; else throw new RuntimeException(); } Object empty = null; boolean done = false; }";
        for (String word : new String[] {"package", "import", "public", "abstract", "class", "extends",
            "implements", "private", "static", "final", "long", "volatile", "boolean", "true", "protected",
            "synchronized", "void", "throws", "if", "instanceof", "return", "else", "throw", "new", "null", "false"}) {
            token(text, word, KEYWORD);
        }
        token(text, "Object", BUILTIN);
        plain(text, "Runnable");
        token(text, "run", FUNCTION);
        token(text, "Exception", BUILTIN);
        ranges(text);
    }

    private static void highlightsModernAndModuleKeywords() {
        String text = "sealed interface Shape permits Circle, Polygon {} "
            + "record Circle(double radius) implements Shape {} non-sealed class Polygon implements Shape {} "
            + "var result = switch (shape) { case Circle c when (c.radius() > 0) -> c; default -> { yield (null); } }; "
            + "open module demo { requires transitive java.base; exports demo.api to client; "
            + "opens demo.internal; uses demo.Service; provides demo.Service with demo.ServiceImpl; }";
        for (String word : new String[] {"sealed", "interface", "permits", "record", "double", "non-sealed",
            "var", "switch", "case", "when", "default", "yield", "open", "module", "requires", "transitive",
            "exports", "to", "opens", "uses", "provides", "with"}) {
            token(text, word, KEYWORD);
        }
        ranges(text);
    }

    private static void commentsAndStringsTakePrecedence() {
        String text = "/** @Override public class Demo { \"text\" 123 } */\r\n"
            + "String url = \"https://example.test/*x*/\"; // return 'x'\r"
            + "/* outer /* nested-looking */ int count = 1; // last";
        token(text, "/** @Override public class Demo { \"text\" 123 } */", COMMENT);
        token(text, "\"https://example.test/*x*/\"", STRING);
        token(text, "// return 'x'", COMMENT);
        token(text, "/* outer /* nested-looking */", COMMENT);
        token(text, "int", KEYWORD);
        token(text, "// last", COMMENT);
        ranges(text);
    }

    private static void characterAndStringEscapesKeepTheirDelimiters() {
        for (String literal : new String[] {"'x'", "'\\''", "'\\\\'", "'\\n'", "'\\141'", "'界'",
            "\"a\\\"//b\"", "\"escaped \\\\\"", "\"\\u0041\"",
            "\"literal \\\\u0022 and /* text */\""}) {
            String text = "String value = " + literal + "; return;";
            token(text, literal, STRING);
            token(text, "return", KEYWORD);
            ranges(text);
        }
    }

    private static void textBlocksKeepCommentsAndEscapedQuotesLiteral() {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            String block = "\"\"\" \t" + newline + "<tag> // public 123 /* comment */" + newline
                + "\\\"\"\" still inside" + newline + "\"\"\"";
            String text = "String markup = " + block + "; return 42;";
            token(text, block, STRING);
            token(text, "return", KEYWORD);
            token(text, "42", NUMBER);
            ranges(text);
            String open = "\"\"\"" + newline + "not closed // class Demo {}";
            token(open, open, STRING);
        }
        // Three quotes on one line are an empty string followed by another string.
        String ordinary = "\"\"\"single line\"; return;";
        token(ordinary, "\"\"", STRING);
        token(ordinary, "\"single line\"", STRING);
        token(ordinary, "return", KEYWORD);
    }

    private static void ordinaryStringsRecoverAtPhysicalLineBreaks() {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            for (String quote : new String[] {"\"", "'"}) {
                for (String ending : new String[] {"", "\\"}) {
                    String literal = quote + "unfinished" + ending;
                    String text = literal + newline + "return 9;";
                    token(text, literal, STRING);
                    token(text, "return", KEYWORD);
                    token(text, "9", NUMBER);
                    ranges(text);
                }
            }
        }
    }

    private static void recognizesJavaNumericLiterals() {
        for (String number : new String[] {"0", "42", "1_000", "1__000L", "42l", "077", "0_7L",
            "0b10_01", "0B1__0L", "0xCA_FE", "0XffL", "0x1e2", "0x1f", ".5", ".5F", "1.",
            "1.e+2", "1.25e-3", "6.022E23D", "1e1_0f", "10f", "08d", "0x1.fp+3", "0X.8P-1F",
            "0x1p4", "0x1.p2D", "0xCA_FE.8p+1_0d"}) {
            String text = "double value = " + number + "; return;";
            token(text, number, NUMBER);
            token(text, "return", KEYWORD);
            ranges(text);
        }
    }

    private static void numbersKeepIdentifierAndOperatorBoundaries() {
        String text = "int value123 = -42L + 0xFF + 0b10; double exponent = 1e-2 + .5; "
            + "object.method2(); int returnValue = 3;";
        token(text, "42L", NUMBER);
        token(text, "0xFF", NUMBER);
        token(text, "0b10", NUMBER);
        token(text, "1e-2", NUMBER);
        token(text, ".5", NUMBER);
        token(text, "-", OPERATOR);
        token(text, "+", OPERATOR);
        token(text, "method2", FUNCTION);
        plain(text, "value123");
        plain(text, "returnValue");
        // Incomplete or foreign numeric suffixes must not consume surrounding operators/names.
        for (String edit : new String[] {"0x", "0b", "1e+", "0x1p-", "12_", "0x_FF", "09", "1n", "1.0L"}) {
            ranges(edit + "; return 2;");
            token(edit + "; return 2;", "return", KEYWORD);
        }
        token("0x1f + 2", "0x1f", NUMBER);
        token("1f + 2", "1f", NUMBER);
    }

    private static void recognizesAnnotationsAndAnnotationTypeDeclarations() {
        String text = "@Override public void run() {} @java.lang.SuppressWarnings(\"unused\") class Demo {} "
            + "public @interface Marker { String value() default \"x\"; } @Marker(\"demo\") class Other {}";
        token(text, "@Override", ATTRIBUTE);
        token(text, "@java.lang.SuppressWarnings", ATTRIBUTE);
        token(text, "@Marker", ATTRIBUTE);
        token(text, "\"unused\"", STRING);
        token(text, "interface", KEYWORD);
        int at = text.indexOf("@interface");
        tokenAt(SyntaxHighlighter.highlight(JAVA, text), at, at + 1, OPERATOR);
        token(text, "value", FUNCTION);
        ranges(text);
    }

    private static void recognizesMethodsConstructorsAndReferences() {
        String text = "Demo /* declaration */ () {} void run // method\n () { "
            + "new Demo(); String value = new String(\"x\"); System.out.println(value); "
            + "obj.record(); obj.yield(); obj.open(); obj.module; "
            + "Runnable action = this::run; Object parser = Integer::parseInt; "
            + "Object factory = Demo::new; Object accessor = obj::record; }";
        for (String word : new String[] {"Demo", "run", "println", "record", "yield", "open", "parseInt"}) {
            token(text, word, FUNCTION);
        }
        for (String word : new String[] {"String", "System", "Integer"}) { token(text, word, BUILTIN); }
        plain(text, "module");
        int referenced = text.indexOf("::record") + 2;
        tokenAt(SyntaxHighlighter.highlight(JAVA, text), referenced, referenced + "record".length(), FUNCTION);
        ranges(text);
    }

    private static void recognizesJavaUnicodeIdentifiersWithoutSplittingKeywords() {
        String text = "int className = 1; int £class = 2; int 𐐀class = 3; int $return = 4; "
            + "int return値 = 5; void Grüße() {} @注釈 class 世界 {} "
            + "String message = \"世界 🐱\";\r\nreturn;";
        for (String word : new String[] {"className", "£class", "𐐀class", "$return", "return値"}) { plain(text, word); }
        token(text, "Grüße", FUNCTION);
        token(text, "@注釈", ATTRIBUTE);
        token(text, "\"世界 🐱\"", STRING);
        int tail = text.lastIndexOf("return");
        tokenAt(SyntaxHighlighter.highlight(JAVA, text), tail, tail + 6, KEYWORD);
        ranges(text);
    }

    private static void recognizesJavaOperatorsWithoutJavascriptRegexes() {
        String text = "int result = value / 2 / 3; result >>>= 1; result <<= 2; result >>= 1; "
            + "result += 1; boolean ready = result != 0 && result <= 3 || result >= 5; "
            + "items.stream().map(x -> x * 2); Object ref = this::run; void accept(String... args) {}";
        for (String operator : new String[] {"/", ">>>=", "<<=", ">>=", "+=", "!=", "&&", "<=", "||", ">=", "->", "::", "..."}) {
            token(text, operator, OPERATOR);
        }
        TestSupport.assertTrue(SyntaxHighlighter.highlight(JAVA, text).stream().noneMatch(t -> t.getKind() == STRING),
            "Java division is never a JavaScript regular expression");
        ranges(text);
    }

    private static void toleratesEveryEditingPrefixAndRetokenizes() {
        String source = "@Override record Demo(String name) { String text = \"\"\"\nhello \\\"\"\"\n\"\"\"; "
            + "double number = 0x1.fp-3; /* comment */ void run() { System.out.println('x'); } }";
        for (int end = 0; end <= source.length(); end++) { ranges(source.substring(0, end)); }
        token("/* return 1", "/* return 1", COMMENT);
        token("return 1", "return", KEYWORD);
        List<SyntaxToken> before = SyntaxHighlighter.highlight(JAVA, "return 1");
        SyntaxHighlighter.highlight(JAVA, "/* return 1");
        token(before, "return 1", "return", KEYWORD);
        TestSupport.expectThrows(UnsupportedOperationException.class, () -> before.clear(), "immutable Java results");
        TestSupport.assertTrue(SyntaxHighlighter.highlight(JAVA, "").isEmpty(), "empty Java file");
    }

    private static void standaloneModulePreservesOffsetsAndRejectsMissingInputs() {
        String text = "String 世界 = \"🐱\"; return 123L;";
        List<SyntaxToken> expected = SyntaxHighlighter.highlight(JAVA, text);
        final int[] count = {0};
        JavaLexer.scan(text, (start, end, kind) -> {
            SyntaxToken token = expected.get(count[0]++);
            TestSupport.assertEquals(token.getStart(), start, "standalone UTF-16 start");
            TestSupport.assertEquals(token.getEnd(), end, "standalone UTF-16 end");
            TestSupport.assertEquals(token.getKind().name(), kind.name(), "editor adapter maps each token kind");
        });
        TestSupport.assertEquals(expected.size(), count[0], "all standalone tokens adapted");
        TestSupport.expectThrows(IllegalArgumentException.class,
            () -> JavaLexer.scan(null, (start, end, kind) -> { }), "missing Java source");
        TestSupport.expectThrows(IllegalArgumentException.class, () -> JavaLexer.scan("", null), "missing Java token sink");
        TestSupport.expectThrows(IllegalArgumentException.class, () -> SyntaxHighlighter.highlight(JAVA, null), "missing editor source");
    }

    private static void cancelsSupersededJavaScans() {
        Thread.currentThread().interrupt();
        try {
            TestSupport.expectThrows(CancellationException.class,
                () -> SyntaxHighlighter.highlight(JAVA, "public class Demo {}"), "cancel Java background scan");
        } finally { Thread.interrupted(); }
        try {
            TestSupport.expectThrows(CancellationException.class,
                () -> JavaLexer.scan("return /* long comment */", (start, end, kind) -> Thread.currentThread().interrupt()),
                "observe cancellation between Java tokens");
        } finally { Thread.interrupted(); }
        token("return 1", "return", KEYWORD);
    }

    private static void token(String text, String value, SyntaxToken.Kind kind) {
        token(SyntaxHighlighter.highlight(JAVA, text), text, value, kind);
    }

    private static void token(List<SyntaxToken> tokens, String text, String value, SyntaxToken.Kind kind) {
        for (SyntaxToken token : tokens) {
            if (token.getKind() == kind && text.substring(token.getStart(), token.getEnd()).equals(value)) { return; }
        }
        throw new AssertionError("Java missing " + kind + " token: " + value + " in " + text);
    }

    private static void tokenAt(List<SyntaxToken> tokens, int start, int end, SyntaxToken.Kind kind) {
        for (SyntaxToken token : tokens) {
            if (token.getStart() == start && token.getEnd() == end && token.getKind() == kind) { return; }
        }
        throw new AssertionError("Java missing " + kind + " range " + start + ":" + end);
    }

    private static void plain(String text, String value) {
        int start = text.indexOf(value);
        TestSupport.assertTrue(start >= 0, "plain fragment exists: " + value);
        for (SyntaxToken token : SyntaxHighlighter.highlight(JAVA, text)) {
            TestSupport.assertFalse(token.getStart() < start + value.length() && token.getEnd() > start,
                "Java identifier remains plain: " + value);
        }
    }

    private static void ranges(String text) {
        int end = 0;
        for (SyntaxToken token : SyntaxHighlighter.highlight(JAVA, text)) {
            TestSupport.assertTrue(token.getStart() >= end && token.getEnd() > token.getStart()
                && token.getEnd() <= text.length(), "Java ranges stay ordered, non-overlapping and within UTF-16 source");
            end = token.getEnd();
        }
    }
}
