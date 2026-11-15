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
        highlightsImportedAndCustomJavaTypes();
        highlightsTypeDeclarationsAndConstructors();
        typeNamesPreserveMethodAndVariableRoles();
        typeDetectionRecoversWhileEditingAndStaysDocumentLocal();
        highlightsDeclarationsAndLiteralKeywords();
        highlightsModernAndModuleKeywords();
        commentsAndStringsTakePrecedence();
        unicodeEscapedLineTerminatorsEndLineComments();
        unicodeEscapedLineTerminatorsPreserveMethodLookahead();
        unicodeLineCommentEscapesRespectBackslashEligibility();
        unicodeLineCommentEscapesTolerateIncompleteEdits();
        characterAndStringEscapesKeepTheirDelimiters();
        textBlocksKeepCommentsAndEscapedQuotesLiteral();
        ordinaryStringsRecoverAtPhysicalLineBreaks();
        recognizesJavaNumericLiterals();
        numbersKeepIdentifierAndOperatorBoundaries();
        recognizesAnnotationsAndAnnotationTypeDeclarations();
        recognizesMethodsConstructorsAndReferences();
        qualifiedYieldCallsWithExplicitTypeArgumentsRemainFunctions();
        qualifiedCallsPreserveTypeAnnotationsAndTrivia();
        incompleteQualifiedTypeArgumentsRecover();
        recognizesMethodReferencesWithExplicitTypeArguments();
        methodReferencesKeepNestedTypeArgumentsSeparate();
        methodReferencesPreserveCommentsAndTypeHighlighting();
        methodReferencesPreserveTypeAnnotationsWithArguments();
        methodReferenceAnnotationsPreserveNestedExpressionsAndTokens();
        methodReferenceAnnotationsPreserveTriviaAndMultipleTypeArguments();
        incompleteMethodReferenceAnnotationsRecover();
        incompleteMethodReferenceTypeArgumentsRecover();
        recognizesJavaUnicodeIdentifiersWithoutSplittingKeywords();
        recognizesJavaOperatorsWithoutJavascriptRegexes();
        toleratesEveryEditingPrefixAndRetokenizes();
        standaloneModulePreservesOffsetsAndRejectsMissingInputs();
        cancelsSupersededJavaScans();
        return 35;
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
        token(text, "Runnable", BUILTIN);
        token(text, "run", FUNCTION);
        token(text, "Exception", BUILTIN);
        ranges(text);
    }

    private static void highlightsImportedAndCustomJavaTypes() {
        String text = "package demo;\nimport android.app.Activity;\nimport java.util.List;\n"
            + "import java.util.ArrayList;\npublic class MainActivity extends Activity implements Runnable {\n"
            + "    private final List<WorkspaceEditorDocument> documents = new ArrayList<>();\n"
            + "    public void run() {\n        for (WorkspaceEditorDocument document : documents) {\n"
            + "            document.getText();\n        }\n    }\n}";
        List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
        for (String type : new String[] {"Activity", "List", "ArrayList", "MainActivity", "Runnable", "WorkspaceEditorDocument"}) {
            for (int start = text.indexOf(type); start >= 0; start = text.indexOf(type, start + type.length())) {
                if (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) { continue; }
                tokenAt(tokens, start, start + type.length(), BUILTIN);
            }
        }
        token(tokens, text, "run", FUNCTION);
        token(tokens, text, "getText", FUNCTION);
        plain(text, "documents");
        plain(text, "document");
        ranges(text);
    }

    private static void highlightsTypeDeclarationsAndConstructors() {
        String text = "interface service {} class worker implements service {\n"
            + "worker() {} service create() { return new worker(); }\n"
            + "java.util.Map<Key, java.util.List<Value[]>> entries = new java.util.HashMap<>();\n"
            + "} record Result(Value value) {} enum Mode { READY }\n"
            + "Object factory = Result::new; Object type = Value.class;";
        for (String type : new String[] {"service", "worker", "Map", "Key", "List", "Value", "HashMap", "Result", "Mode"}) {
            token(text, type, BUILTIN);
        }
        token(text, "worker", FUNCTION);
        token(text, "create", FUNCTION);
        plain(text, "entries");
        plain(text, "value");
        ranges(text);
    }

    private static void typeNamesPreserveMethodAndVariableRoles() {
        String text = "class Demo { void DemoMethod() {} void run() {\n"
            + "int Count = 1; String Label = \"List<Demo>\"; Object[] Items = null;\n"
            + "DemoMethod(); Object method = Demo::DemoMethod;\n"
            + "/* Activity List Demo */ @Marker class Nested {} } }";
        for (String name : new String[] {"Count", "Label", "Items"}) { plain(text, name); }
        token(text, "Demo", BUILTIN);
        token(text, "Nested", BUILTIN);
        token(text, "DemoMethod", FUNCTION);
        int method = text.indexOf("::DemoMethod") + 2;
        tokenAt(SyntaxHighlighter.highlight(JAVA, text), method, method + "DemoMethod".length(), FUNCTION);
        token(text, "\"List<Demo>\"", STRING);
        token(text, "/* Activity List Demo */", COMMENT);
        token(text, "@Marker", ATTRIBUTE);
        ranges(text);
    }

    private static void typeDetectionRecoversWhileEditingAndStaysDocumentLocal() {
        String text = "class worker {} worker current; int Count = 1;\n"
            + "Class<?> type = Demo.class; plainValue = 2; List<Value> values = new ArrayList<>();";
        token(text, "worker", BUILTIN);
        plain("worker current;", "worker");
        plain(text, "Count");
        plain(text, "plainValue");
        for (int end = 0; end <= text.length(); end++) { ranges(text.substring(0, end)); }
        for (String prefix : new String[] {"class ", "new java.util.", "List<", "Object[] ", "extends "}) {
            String edited = prefix + "; int Count = 1; receiver.run();";
            token(edited, "int", KEYWORD);
            token(edited, "run", FUNCTION);
            plain(edited, "Count");
            ranges(edited);
        }
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

    private static void unicodeEscapedLineTerminatorsEndLineComments() {
        // JLS 3.2: Unicode escapes are translated before comments are recognized.
        // Build escapes in pieces so javac leaves the test input unchanged.
        String backslash = "\\";
        for (String newline : new String[] {backslash + "u000a", backslash + "u000d",
            backslash + "u000d" + backslash + "u000a", backslash + "uuuu000A"}) {
            String comment = "// comment 🐱 ";
            String text = comment + newline + "int value = 42;";
            List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
            token(tokens, text, "int", KEYWORD);
            tokenAt(tokens, 0, comment.length(), COMMENT);
            int codeStart = comment.length() + newline.length();
            tokenAt(tokens, codeStart, codeStart + "int".length(), KEYWORD);
            int number = text.indexOf("42");
            tokenAt(tokens, number, number + 2, NUMBER);
            plain(text, "value");
            ranges(text);
        }
        // A doubled backslash makes the following escape ineligible for translation.
        String unchanged = "// comment " + backslash + backslash + "u000a int hidden = 42;";
        token(unchanged, unchanged, COMMENT);
        ranges(unchanged);
    }

    private static void unicodeEscapedLineTerminatorsPreserveMethodLookahead() {
        String backslash = "\\";
        for (String newline : new String[] {backslash + "u000a", backslash + "u000d",
            "\r" + backslash + "u000a", backslash + "u000d" + "\n",
            backslash + "uu000d" + backslash + "uuu000a"}) {
            String text = "run // call 🐱 " + newline + "(); Object ref = Factory:: // reference "
                + newline + "<String>create;";
            List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
            token(tokens, text, "run", FUNCTION);
            token(tokens, text, "create", FUNCTION);
            token(tokens, text, "String", BUILTIN);
            token(tokens, text, "// call 🐱 ", COMMENT);
            token(tokens, text, "// reference ", COMMENT);
            int method = text.indexOf("create");
            tokenAt(tokens, method, method + "create".length(), FUNCTION);
            plain(text, "ref");
            ranges(text);
        }
    }

    private static void unicodeLineCommentEscapesRespectBackslashEligibility() {
        String backslash = "\\";
        for (String prefix : new String[] {backslash + backslash, backslash + "u005c",
            backslash + "u005c" + backslash, backslash + "u005c" + backslash + "u005c"}) {
            String comment = "// comment 🐱 " + prefix;
            String text = comment + backslash + "u000a int visible = 1;";
            List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
            token(tokens, text, "int", KEYWORD);
            tokenAt(tokens, 0, comment.length(), COMMENT);
            token(tokens, text, "1", NUMBER);
            plain(text, "visible");
            ranges(text);
        }
        for (String prefix : new String[] {backslash, backslash + backslash + backslash,
            backslash + "u005c" + backslash + backslash}) {
            String text = "// comment " + prefix + backslash + "u000a int hidden = 42;";
            token(text, text, COMMENT);
            ranges(text);
        }
        // A translated backslash is not recursively interpreted as another escape.
        String nonrecursive = "// comment " + backslash + "u005cu000a int hidden = 42;";
        token(nonrecursive, nonrecursive, COMMENT);
        ranges(nonrecursive);
    }

    private static void unicodeLineCommentEscapesTolerateIncompleteEdits() {
        String backslash = "\\";
        String newline = backslash + "uuuu000a";
        for (int end = 0; end < newline.length(); end++) {
            String comment = "// editing " + newline.substring(0, end);
            String text = comment + "\r\nreturn 2;";
            token(text, comment, COMMENT);
            token(text, "return", KEYWORD);
            token(text, "2", NUMBER);
            ranges(text);
        }
        for (String escape : new String[] {"U000a", "u000g", "u000Ｇ", "u０００a",
            "u000b", "u000c", "u2028", "u2029"}) {
            String comment = "// comment " + backslash + escape + " int hidden = 42;";
            String text = comment + "\nreturn 2;";
            token(text, comment, COMMENT);
            token(text, "return", KEYWORD);
            token(text, "2", NUMBER);
            ranges(text);
        }
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

    private static void qualifiedYieldCallsWithExplicitTypeArgumentsRemainFunctions() {
        // A qualified method named yield may have explicit type arguments (JLS 3.8, 15.12).
        for (String arguments : new String[] {"", "<String>", "<java.util.List<String>>",
            "<java.util.Map<String, java.util.List<Integer>>>"}) {
            String text = "Object result = this." + arguments + "yield(); "
                + "int value = switch (1) { default -> { yield (2); } };";
            List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
            int method = text.indexOf("yield()");
            tokenAt(tokens, method, method + "yield".length(), FUNCTION);
            int statement = text.lastIndexOf("yield");
            tokenAt(tokens, statement, statement + "yield".length(), KEYWORD);
            token(tokens, text, "switch", KEYWORD);
            token(tokens, text, "2", NUMBER);
            plain(text, "result");
            plain(text, "value");
            ranges(text);
        }
    }

    private static void qualifiedCallsPreserveTypeAnnotationsAndTrivia() {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            for (String qualifier : new String[] {"this.", "super.", "Factory.", "(receiver)."}) {
                String text = qualifier + " /* before */ <java.util.Map<String, "
                    + "java.util.List<@Hint(value = \">\", enabled = (3 > 2), mask = (1 << 2)) "
                    + "Value[]>>> // after" + newline + "yield /* call */ (); "
                    + "int untouched = switch (1) { default -> { yield (2); } };";
                List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
                int method = text.indexOf("yield");
                tokenAt(tokens, method, method + "yield".length(), FUNCTION);
                int statement = text.lastIndexOf("yield");
                tokenAt(tokens, statement, statement + "yield".length(), KEYWORD);
                token(tokens, text, "String", BUILTIN);
                token(tokens, text, "@Hint", ATTRIBUTE);
                token(tokens, text, "\">\"", STRING);
                token(tokens, text, ">", OPERATOR);
                token(tokens, text, "<<", OPERATOR);
                for (String comment : new String[] {"/* before */", "// after", "/* call */"}) {
                    token(tokens, text, comment, COMMENT);
                }
                token(text, "Value", BUILTIN);
                plain(text, "untouched");
                ranges(text);
            }
        }
    }

    private static void incompleteQualifiedTypeArgumentsRecover() {
        for (String prefix : new String[] {"receiver.<", "receiver.<Value", "receiver.<Value,",
            "receiver.<List<Value>", "receiver.<Value>>", "receiver.<Value>=",
            "receiver.<@Hint(", "receiver.<@Hint(\">\""}) {
            String text = prefix + "\n; int untouched = switch (1) { default -> { yield (2); } }; "
                + "receiver.yield();";
            List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
            int statement = text.indexOf("yield");
            tokenAt(tokens, statement, statement + "yield".length(), KEYWORD);
            int method = text.lastIndexOf("yield");
            tokenAt(tokens, method, method + "yield".length(), FUNCTION);
            token(tokens, text, "int", KEYWORD);
            plain(text, "untouched");
            ranges(text);
            for (int end = 0; end <= prefix.length(); end++) { ranges(prefix.substring(0, end)); }
        }
    }

    private static void recognizesMethodReferencesWithExplicitTypeArguments() {
        String text = "java.util.function.Supplier<java.util.List<String>> factory = "
            + "java.util.Collections::<String>emptyList;";
        token(text, "emptyList", FUNCTION);
        ranges(text);
    }

    private static void methodReferencesKeepNestedTypeArgumentsSeparate() {
        for (String arguments : new String[] {"<Value>", "<java.util.List<Value>>",
            "<java.util.Map<Key, java.util.List<Value>>>", "<Key, Value[]>",
            "<java.util.List<? extends Value[]>>"}) {
            String text = "Factory::" + arguments + "create; int untouched = 1;";
            token(text, "create", FUNCTION);
            token(text, "Value", BUILTIN);
            plain(text, "untouched");
            token(text, "int", KEYWORD);
            ranges(text);
        }
    }

    private static void methodReferencesPreserveCommentsAndTypeHighlighting() {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            String text = "Factory:: /* before */ <String, // inside" + newline
                + "Value> /* after */ create; String ordinary = \"text\";";
            token(text, "create", FUNCTION);
            int type = text.indexOf("<String") + 1;
            tokenAt(SyntaxHighlighter.highlight(JAVA, text), type, type + "String".length(), BUILTIN);
            token(text, "/* before */", COMMENT);
            token(text, "// inside", COMMENT);
            token(text, "/* after */", COMMENT);
            token(text, "\"text\"", STRING);
            token(text, "Value", BUILTIN);
            plain(text, "ordinary");
            ranges(text);
        }
    }

    private static void methodReferencesPreserveTypeAnnotationsWithArguments() {
        // Type-use annotations may have arguments inside a method reference's explicit type arguments.
        String text = "java.util.function.Supplier<java.util.List<String>> factory = "
            + "java.util.Collections::<@TypeHint(\"demo\") String>emptyList; int untouched = 1;";
        List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
        token(tokens, text, "emptyList", FUNCTION);
        token(tokens, text, "@TypeHint", ATTRIBUTE);
        token(tokens, text, "\"demo\"", STRING);
        int type = text.indexOf(" String>emptyList") + 1;
        tokenAt(tokens, type, type + "String".length(), BUILTIN);
        token(tokens, text, "int", KEYWORD);
        token(tokens, text, "1", NUMBER);
        plain(text, "untouched");
        ranges(text);
    }

    private static void methodReferenceAnnotationsPreserveNestedExpressionsAndTokens() {
        String text = "Factory::<java.util.Map<@Hints(value = {@Hint(\"first ; > ( )\"), @Hint(\"second\")}, "
            + "type = String.class, mask = (1 << 2) | (8 >>> 1), enabled = (3 > 2) && (1 < 2), letter = ')') "
            + "String, java.util.List<Value>>>create; int untouched = 1;";
        List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
        token(tokens, text, "create", FUNCTION);
        token(tokens, text, "@Hints", ATTRIBUTE);
        token(tokens, text, "@Hint", ATTRIBUTE);
        token(tokens, text, "\"first ; > ( )\"", STRING);
        token(tokens, text, "\"second\"", STRING);
        token(tokens, text, "')'", STRING);
        token(tokens, text, "class", KEYWORD);
        for (String number : new String[] {"1", "2", "3", "8"}) { token(tokens, text, number, NUMBER); }
        for (String operator : new String[] {"<<", ">>>", ">", "<", "&&"}) {
            token(tokens, text, operator, OPERATOR);
        }
        int type = text.indexOf("String, java.util.List");
        tokenAt(tokens, type, type + "String".length(), BUILTIN);
        token(text, "Value", BUILTIN);
        plain(text, "untouched");
        ranges(text);
    }

    private static void methodReferenceAnnotationsPreserveTriviaAndMultipleTypeArguments() {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            String text = "Factory::<@pkg.Hint /* before */ // opening" + newline
                + "(value = /* literal */ \"demo\", type = String.class) /* after */ Value, "
                + "@Marker @Empty() Other @pkg.Flag (true) []>create; int untouched = 1;";
            List<SyntaxToken> tokens = SyntaxHighlighter.highlight(JAVA, text);
            token(tokens, text, "create", FUNCTION);
            for (String annotation : new String[] {"@pkg.Hint", "@Marker", "@Empty", "@pkg.Flag"}) {
                token(tokens, text, annotation, ATTRIBUTE);
            }
            for (String comment : new String[] {"/* before */", "// opening", "/* literal */", "/* after */"}) {
                token(tokens, text, comment, COMMENT);
            }
            token(tokens, text, "\"demo\"", STRING);
            token(tokens, text, "String", BUILTIN);
            token(tokens, text, "true", KEYWORD);
            token(tokens, text, "int", KEYWORD);
            token(text, "Value", BUILTIN);
            token(text, "Other", BUILTIN);
            plain(text, "untouched");
            ranges(text);
        }
    }

    private static void incompleteMethodReferenceAnnotationsRecover() {
        for (String prefix : new String[] {"Factory::<@Hint(", "Factory::<@Hint(\"unfinished",
            "Factory::<@Hint(\"unfinished\\", "Factory::<@Hint('x", "Factory::<@Hint((1 << 2)",
            "Factory::<@Hints({@Hint(\"x\"),", "Factory::<@Hint(value = @Inner("}) {
            String text = prefix + "\n; int untouched = 1; normal(); Object ref = Other::run;";
            token(text, "int", KEYWORD);
            token(text, "1", NUMBER);
            token(text, "normal", FUNCTION);
            token(text, "run", FUNCTION);
            plain(text, "untouched");
            plain(text, "ref");
            ranges(text);
            for (int end = 0; end <= prefix.length(); end++) { ranges(prefix.substring(0, end)); }
        }
    }

    private static void incompleteMethodReferenceTypeArgumentsRecover() {
        for (String prefix : new String[] {"Factory::<", "Factory::<Value", "Factory::<Value,",
            "Factory::<List<Value>", "Factory::<Value>>", "Factory::<Value>="}) {
            String text = prefix + "; int untouched = 1; normal();";
            token(text, "int", KEYWORD);
            token(text, "normal", FUNCTION);
            plain(text, "untouched");
            ranges(text);
            for (int end = 0; end <= prefix.length(); end++) { ranges(prefix.substring(0, end)); }
        }
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
