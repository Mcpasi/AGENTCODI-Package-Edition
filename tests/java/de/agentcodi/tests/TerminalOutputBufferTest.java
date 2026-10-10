package de.agentcodi.tests;

import de.agentcodi.core.TerminalOutputBuffer;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TerminalOutputBufferTest {
    private TerminalOutputBufferTest() {
    }

    public static int run() {
        decodesSplitUtf8AndStripsTerminalControls();
        boundsOutputAndMarksOmission();
        redactsCredentialShapesFromSnapshots();
        finishesIncompleteUtf8Safely();
        removesRemainingControlsAndResetsIncompleteAnsi();
        backspaceMovesWithoutDeletingAndPreservesUnicode();
        carriageReturnOverwritesExistingCells();
        carriageReturnAtFinishDoesNotInsertANewline();
        wrapsAtThePtyMargin();
        cancelsPendingWrapWithCarriageReturnAndBackspace();
        interpretsSplitAnsiCursorSequences();
        erasesLinesAndDisplayWithoutMovingTheCursor();
        tabsAdvanceToCellStops();
        resizingHeightPreservesPromptAndScrollback();
        restoresScreenCellsAfterNarrowingAndWidening();
        restoresWideGlyphsAfterNarrowingAndWidening();
        clearOverwritesScreenHistoryAndHiddenCells();
        erasingAfterResizeDoesNotRestoreHiddenText();
        redactsTokensBeforeClippingTheViewport();
        boundsAndWipesStoredCellsAfterNarrowing();
        resizingWidthClipsTheScreenAndUpdatesTheCursor();
        drawsWideAndCombiningUnicodeCells();
        redactsCredentialsAcrossSoftWraps();
        boundsHistoryAfterRedrawing();
        boundsTheMaximumScreenGeometry();
        clearResetsTheCursorAndRetainsGeometry();
        removesCharsetAndOscSequences();
        boundsCursorParameters();
        savesAndRestoresTheCursor();
        return 29;
    }

    private static void backspaceMovesWithoutDeletingAndPreservesUnicode() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.append("a😀".getBytes(StandardCharsets.UTF_8));
        buffer.append(new byte[] {0x08});
        String afterFirst = buffer.snapshot();
        TestSupport.assertEquals("a😀", afterFirst, "backspace moves without erasing cells");
        TestSupport.assertFalse(
            hasUnpairedSurrogate(afterFirst),
            "backspace leaves no dangling surrogate"
        );
        buffer.append(new byte[] {0x08});
        buffer.append("X".getBytes(StandardCharsets.UTF_8));
        TestSupport.assertEquals("aX", buffer.snapshot(), "overwrite clears the complete wide glyph");

        TerminalOutputBuffer plain = new TerminalOutputBuffer();
        plain.append("ab".getBytes(StandardCharsets.UTF_8));
        plain.append(new byte[] {0x08});
        TestSupport.assertEquals("ab", plain.snapshot(), "backspace alone leaves displayed text intact");
        plain.append("Z".getBytes(StandardCharsets.UTF_8));
        TestSupport.assertEquals("aZ", plain.snapshot(), "text overwrites at the moved cursor");
    }

    private static TerminalOutputBuffer terminal(int rows, int columns) {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.resize(rows, columns);
        return buffer;
    }

    private static void append(TerminalOutputBuffer buffer, String value) {
        buffer.append(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void carriageReturnOverwritesExistingCells() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "12345\rab");
        TestSupport.assertEquals("ab345", buffer.snapshot(), "CR redraw keeps untouched cells");
        append(buffer, "\rxy\u001b[K");
        TestSupport.assertEquals("xy", buffer.snapshot(), "erase removes the stale suffix");
    }

    private static void carriageReturnAtFinishDoesNotInsertANewline() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "abc\r");
        buffer.finish();
        TestSupport.assertEquals("abc", buffer.snapshot(), "CR does not become a log line");
    }

    private static void wrapsAtThePtyMargin() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "12345678X\rQ");
        TestSupport.assertEquals("12345678\nQ", buffer.snapshot(), "CR addresses the wrapped physical row");
    }

    private static void cancelsPendingWrapWithCarriageReturnAndBackspace() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "12345678\rA");
        TestSupport.assertEquals("A2345678", buffer.snapshot(), "CR cancels pending autowrap");
        buffer.clear();
        append(buffer, "12345678\bZ");
        TestSupport.assertEquals("1234567Z", buffer.snapshot(), "BS cancels pending autowrap");
    }

    private static void interpretsSplitAnsiCursorSequences() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "abc\u001b[");
        append(buffer, "2");
        append(buffer, "DX");
        TestSupport.assertEquals("aXc", buffer.snapshot(), "split cursor-left sequence is applied");
        buffer.clear();
        append(buffer, "old\r\nlast\u001b[1A\rnew\u001b[K");
        TestSupport.assertEquals("new\nlast", buffer.snapshot(), "cursor-up edits the earlier screen row");
    }

    private static void erasesLinesAndDisplayWithoutMovingTheCursor() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "12345\r\u001b[3C\u001b[1K");
        TestSupport.assertEquals("    5", buffer.snapshot(), "erase to beginning includes the cursor cell");
        append(buffer, "\u001b[2K");
        TestSupport.assertEquals("", buffer.snapshot(), "erase whole line");
        append(buffer, "\rabc\r\nxyz\u001b[2J");
        TestSupport.assertEquals("\n", buffer.snapshot(), "erase screen preserves cursor row");
        append(buffer, "\u001b[H");
        TestSupport.assertEquals("", buffer.snapshot(), "home addresses the cleared screen");
    }

    private static void tabsAdvanceToCellStops() {
        TerminalOutputBuffer buffer = terminal(3, 12);
        append(buffer, "a\tz");
        TestSupport.assertEquals("a       z", buffer.snapshot(), "tab uses eight-column stops");
    }

    private static void resizingHeightPreservesPromptAndScrollback() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "> ");
        buffer.resize(2, 8);
        TestSupport.assertEquals(">", buffer.snapshot(), "unused rows do not become scrollback");
        append(buffer, "pwd\r\nok\r\n> ");
        buffer.resize(1, 8);
        TestSupport.assertEquals("> pwd\nok\n>", buffer.snapshot(), "height shrink retains output above prompt");
    }

    private static void resizingWidthClipsTheScreenAndUpdatesTheCursor() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "abcdefgh\r\nijklmnop");
        buffer.resize(3, 5);
        append(buffer, "Z");
        TestSupport.assertEquals("abcde\nijklZ", buffer.snapshot(), "narrower PTY uses the new right margin");
        buffer.resize(3, 10);
        append(buffer, "Q");
        TestSupport.assertEquals("abcdefgh\nijklZQop", buffer.snapshot(),
            "wider PTY restores untouched cells and retains edits at the new margin");
    }

    private static void restoresScreenCellsAfterNarrowingAndWidening() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "abcdefgh\r\nijklmnop");
        buffer.resize(3, 5);
        TestSupport.assertEquals("abcde\nijklm", buffer.snapshot(), "narrow viewport hides offscreen cells");
        buffer.resize(3, 8);
        TestSupport.assertEquals("abcdefgh\nijklmnop", buffer.snapshot(), "resize retains both rows' output");
        buffer.resize(3, 3);
        buffer.resize(3, 8);
        TestSupport.assertEquals("abcdefgh\nijklmnop", buffer.snapshot(), "repeated resize retains output");
    }

    private static void restoresWideGlyphsAfterNarrowingAndWidening() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "ab中😀cd");
        buffer.resize(3, 3);
        TestSupport.assertEquals("ab", buffer.snapshot(), "partial CJK glyph is hidden at the right margin");
        buffer.resize(3, 8);
        TestSupport.assertEquals("ab中😀cd", buffer.snapshot(), "full CJK and emoji cells remain stored");
        buffer.resize(3, 5);
        TestSupport.assertEquals("ab中", buffer.snapshot(), "partial emoji is hidden at the right margin");
        buffer.resize(3, 8);
        TestSupport.assertEquals("ab中😀cd", buffer.snapshot(), "emoji survives repeated resize");
        buffer.resize(3, 5);
        append(buffer, "\u001b[5GX");
        buffer.resize(3, 8);
        TestSupport.assertEquals("ab中X cd", buffer.snapshot(), "overwrite clears both halves of the hidden emoji");
    }

    private static void clearOverwritesScreenHistoryAndHiddenCells() {
        TerminalOutputBuffer buffer = terminal(2, 8);
        append(buffer, "history\r\nhidden12\r\nlive😀");
        List<Object> storedCells = new ArrayList<Object>();
        for (String area : new String[] {"history", "screen"}) {
            for (Object line : (Iterable<?>) privateField(buffer, area)) {
                storedCells.addAll((List<?>) privateField(line, "cells"));
            }
        }
        TestSupport.assertTrue(storedCells.size() >= 20, "history, visible and hidden cells captured");
        buffer.resize(2, 4);
        buffer.append(new byte[] {(byte) 0xe2, (byte) 0x82});
        byte[] pending = (byte[]) privateField(buffer, "pendingUtf8");
        buffer.clear();
        TestSupport.assertEquals("", buffer.snapshot(), "clear removes the displayed output");
        // Retain references to the actual old storage: dropping references alone is insufficient.
        for (Object cell : storedCells) {
            char[] characters = cell instanceof char[] ? (char[]) cell : cell.toString().toCharArray();
            for (char character : characters) {
                TestSupport.assertEquals(Character.valueOf('\0'), Character.valueOf(character),
                    "clear overwrites retained screen/history/hidden character storage");
            }
        }
        for (byte value : pending) {
            TestSupport.assertEquals(Byte.valueOf((byte) 0), Byte.valueOf(value), "pending UTF-8 wiped");
        }
        buffer.resize(2, 8);
        TestSupport.assertEquals("", buffer.snapshot(), "widening cannot resurrect cleared cells");
    }

    private static Object privateField(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect terminal character storage", error);
        }
    }

    private static void erasingAfterResizeDoesNotRestoreHiddenText() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "abcdefgh");
        buffer.resize(3, 4);
        append(buffer, "\rxy\u001b[K");
        buffer.resize(3, 8);
        TestSupport.assertEquals("xy", buffer.snapshot(), "erase to end includes retained offscreen cells");
        buffer.clear();
        append(buffer, "abcdefgh");
        buffer.resize(3, 4);
        append(buffer, "\u001b[2K");
        buffer.resize(3, 8);
        TestSupport.assertEquals("", buffer.snapshot(), "whole-line erase includes hidden cells");
        append(buffer, "\rabcdefgh\r\nijklmnop");
        buffer.resize(3, 4);
        append(buffer, "\u001b[2J");
        buffer.resize(3, 8);
        TestSupport.assertEquals("\n", buffer.snapshot(), "whole-screen erase includes hidden cells");
    }

    private static void redactsTokensBeforeClippingTheViewport() {
        TerminalOutputBuffer buffer = terminal(3, 80);
        append(buffer, "sk-exampletoken0123456789");
        buffer.resize(3, 5);
        TestSupport.assertFalse(buffer.snapshot().contains("sk-"), "narrow viewport cannot expose a token prefix");
        TestSupport.assertContains(buffer.snapshot().replace("\n", ""), "<redacted-token>",
            "redaction matches the complete stored token before clipping");
        buffer.resize(3, 80);
        TestSupport.assertEquals("<redacted-token>", buffer.snapshot(), "redaction remains intact after widening");
    }

    private static void boundsAndWipesStoredCellsAfterNarrowing() {
        TerminalOutputBuffer buffer = terminal(2, 1000);
        byte[] original = new byte[2000];
        Arrays.fill(original, (byte) 'a');
        buffer.append(original);
        List<Object> oldStorage = new ArrayList<Object>();
        for (Object line : (Iterable<?>) privateField(buffer, "screen")) {
            oldStorage.addAll((List<?>) privateField(line, "cells"));
        }
        buffer.resize(2, 1);
        byte[] oversized = new byte[TerminalOutputBuffer.MAXIMUM_CHARACTERS * 2];
        Arrays.fill(oversized, (byte) 'x');
        buffer.append(oversized);
        TestSupport.assertTrue(((Integer) privateField(buffer, "storedCharacters")).intValue()
            <= TerminalOutputBuffer.MAXIMUM_CHARACTERS, "retained hidden cells count toward the storage budget");
        TestSupport.assertTrue(buffer.snapshot().length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
            "snapshot remains bounded after narrowing and scrollback trimming");
        for (Object cell : oldStorage) {
            for (char character : (char[]) cell) {
                TestSupport.assertEquals(Character.valueOf('\0'), Character.valueOf(character),
                    "discarded old visible/hidden cells are overwritten before releasing scrollback");
            }
        }
    }

    private static void drawsWideAndCombiningUnicodeCells() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        byte[] bytes = "a中😀e\u0301".getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            buffer.append(new byte[] {value});
        }
        TestSupport.assertEquals("a中😀é", buffer.snapshot(), "wide cells and combining accents survive chunks");
        append(buffer, "\bZ");
        TestSupport.assertEquals("a中😀Z", buffer.snapshot(), "combining character occupies one cell");
        append(buffer, "\r\u001b[2CQ");
        TestSupport.assertEquals("a Q😀Z", buffer.snapshot(), "overwrite of wide continuation clears its glyph");
        TestSupport.assertFalse(hasUnpairedSurrogate(buffer.snapshot()), "no split UTF-16 glyph");
    }

    private static void redactsCredentialsAcrossSoftWraps() {
        TerminalOutputBuffer buffer = terminal(24, 12);
        append(buffer, "authorization=long-private-secret-value\r\nsk-exampletoken0123456789\r\n");
        String joined = buffer.snapshot().replace("\n", "");
        TestSupport.assertFalse(joined.contains("long-private"), "wrapped named secret is hidden");
        TestSupport.assertFalse(joined.contains("exampletoken"), "wrapped token shape is hidden");
        TestSupport.assertContains(joined, "<redacted>", "wrapped named redaction marker");
        TestSupport.assertContains(joined, "<redacted-token>", "wrapped token redaction marker");
    }

    private static void boundsHistoryAfterRedrawing() {
        TerminalOutputBuffer buffer = terminal(2, 16);
        byte[] oversized = new byte[TerminalOutputBuffer.MAXIMUM_CHARACTERS + 4096];
        Arrays.fill(oversized, (byte) 'x');
        buffer.append(oversized);
        append(buffer, "\r\nold\r>\u001b[K");
        TestSupport.assertTrue(buffer.snapshot().endsWith(">"), "redraw still uses the live cursor after trimming");
        TestSupport.assertTrue(buffer.snapshot().length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
            "scrollback and screen stay bounded after redraw");
    }

    private static void boundsTheMaximumScreenGeometry() {
        TerminalOutputBuffer buffer = terminal(1000, 1000);
        byte[] oversized = new byte[TerminalOutputBuffer.MAXIMUM_CHARACTERS * 3];
        Arrays.fill(oversized, (byte) 'x');
        buffer.append(oversized);
        TestSupport.assertTrue(buffer.snapshot().length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
            "maximum geometry respects the character budget");
        TestSupport.assertTrue(buffer.snapshot().startsWith("[earlier terminal output omitted]"),
            "omitted viewport cells are marked");
    }

    private static void clearResetsTheCursorAndRetainsGeometry() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "abcdefghijk\u001b[3");
        buffer.clear();
        append(buffer, "12345678X");
        TestSupport.assertEquals("12345678\nX", buffer.snapshot(), "clear resets cursor/ANSI but keeps PTY width");
    }

    private static void removesCharsetAndOscSequences() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "\u001b(Bplain\u001b]title\u001b\\!");
        TestSupport.assertEquals("plain!", buffer.snapshot(), "charset/title controls are not text");
    }

    private static void boundsCursorParameters() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "#\u001b[999999999999999999999CX\u001b[?25l");
        TestSupport.assertEquals("#      X", buffer.snapshot(), "large cursor counts clamp without overflow");
    }

    private static void savesAndRestoresTheCursor() {
        TerminalOutputBuffer buffer = terminal(3, 8);
        append(buffer, "abc\u001b[sXY\u001b[uZ");
        TestSupport.assertEquals("abcZY", buffer.snapshot(), "restored cursor overwrites its saved cell");
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return true;
            }
        }
        return false;
    }

    private static void decodesSplitUtf8AndStripsTerminalControls() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        byte[] first = new byte[] {'o', 'k', ' ', (byte) 0xe2, (byte) 0x82};
        byte[] second = new byte[] {
            (byte) 0xac,
            0x1b,
            '[',
            '3',
            '1',
            'm',
            '!',
            0x1b,
            '[',
            '0',
            'm',
            '\r',
            '\n'
        };
        buffer.append(first);
        buffer.append(second);
        TestSupport.assertEquals("ok €!\n", buffer.snapshot(), "terminal normalization");
        buffer.clear();
        TestSupport.assertEquals("", buffer.snapshot(), "terminal clear");
    }

    private static void boundsOutputAndMarksOmission() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        byte[] oversized = new byte[TerminalOutputBuffer.MAXIMUM_CHARACTERS + 4096];
        Arrays.fill(oversized, (byte) 'x');
        buffer.append(oversized);
        String snapshot = buffer.snapshot();
        TestSupport.assertTrue(
            snapshot.length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
            "terminal snapshot limit"
        );
        TestSupport.assertTrue(
            snapshot.startsWith("[earlier terminal output omitted]"),
            "terminal omission marker"
        );
    }

    private static void redactsCredentialShapesFromSnapshots() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.append(
            "authorization=secret-value-12345\n".getBytes(StandardCharsets.UTF_8)
        );
        String snapshot = buffer.snapshot();
        TestSupport.assertFalse(snapshot.contains("secret-value-12345"), "terminal secret");
        TestSupport.assertContains(snapshot, "<redacted>", "terminal redaction marker");
    }

    private static void finishesIncompleteUtf8Safely() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.append(new byte[] {(byte) 0xf0, (byte) 0x9f});
        buffer.finish();
        TestSupport.assertEquals("�", buffer.snapshot(), "incomplete UTF-8 replacement");
    }

    private static void removesRemainingControlsAndResetsIncompleteAnsi() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.append(new byte[] {
            'a',
            0x7f,
            (byte) 0xc2,
            (byte) 0x85,
            'b',
            0x1b,
            ']',
            'h',
            'i',
        });
        buffer.finish();
        buffer.append("visible".getBytes(StandardCharsets.UTF_8));
        TestSupport.assertEquals(
            "abvisible",
            buffer.snapshot(),
            "terminal controls and incomplete ANSI state"
        );
    }
}
