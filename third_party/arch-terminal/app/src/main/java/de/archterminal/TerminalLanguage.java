package de.archterminal;

import java.util.Locale;

/** Stable preference values also passed to the guest's managed shell messages. */
enum TerminalLanguage {
    GERMAN("de", Locale.GERMAN), ENGLISH("en", Locale.ENGLISH);

    final String code;
    final Locale locale;
    TerminalLanguage(String code, Locale locale) { this.code = code; this.locale = locale; }

    static TerminalLanguage fromPreference(String value) {
        return "en".equals(value) ? ENGLISH : GERMAN;
    }

    // The downloader is Android-independent and can also run in host tests.
    String text(String german, String english, Object... arguments) {
        return String.format(locale, this == ENGLISH ? english : german, arguments);
    }
}
