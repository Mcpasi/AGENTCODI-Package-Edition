package de.archterminal;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalLanguageTest {
    @Test public void storedCodesRoundTrip() {
        for (TerminalLanguage language : TerminalLanguage.values())
            assertEquals(language, TerminalLanguage.fromPreference(language.code));
    }
    @Test public void absentOrInvalidPreferencePreservesThePreviousDefault() {
        for (String value : new String[]{null, "", "fr", "en_US", "en; exit"})
            assertEquals(TerminalLanguage.GERMAN, TerminalLanguage.fromPreference(value));
    }
}
