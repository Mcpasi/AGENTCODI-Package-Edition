package de.agentcodi.editor.syntax;

import de.agentcodi.editor.syntax.java.JavaLexer;

import java.util.Collections;
import java.util.List;

/** Pure, stateless language dispatch. Safe to run off the Android UI thread. */
public final class SyntaxHighlighter {
    private SyntaxHighlighter() { }

    public static List<SyntaxToken> highlight(SyntaxLanguage language, String text) {
        if (language == null || text == null) { throw new IllegalArgumentException(); }
        if (language == SyntaxLanguage.PLAIN || text.isEmpty()) { return Collections.emptyList(); }
        SyntaxScanner source = new SyntaxScanner(text);
        switch (language) {
            case JAVASCRIPT: JavaScriptLexer.scan(source, 0, text.length()); break;
            case HTML: HtmlLexer.scan(source, 0, text.length()); break;
            case CSS: CssLexer.scan(source, 0, text.length()); break;
            case PYTHON: PythonLexer.scan(source, 0, text.length()); break;
            case JAVA:
                JavaLexer.scan(text, (start, end, kind) -> source.add(start, end, SyntaxToken.Kind.valueOf(kind.name())));
                break;
            default: break;
        }
        return Collections.unmodifiableList(source.tokens);
    }
}
