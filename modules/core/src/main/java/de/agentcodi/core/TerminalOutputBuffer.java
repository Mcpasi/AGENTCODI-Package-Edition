package de.agentcodi.core;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

/** Bounded, plain-text terminal cells and scrollback; not an append-only log. */
public final class TerminalOutputBuffer {
    public static final int MAXIMUM_CHARACTERS = 128 * 1024;
    private static final String OMITTED_PREFIX = "[earlier terminal output omitted]\n";
    private static final int MAXIMUM_DIMENSION = 1000;
    private static final int MAXIMUM_CELL_CHARACTERS = 32;

    private static final int ANSI_NORMAL = 0;
    private static final int ANSI_ESCAPE = 1;
    private static final int ANSI_CSI = 2;
    private static final int ANSI_OSC = 3;
    private static final int ANSI_OSC_ESCAPE = 4;
    private static final int ANSI_CHARSET = 5;
    private static final int MAXIMUM_ANSI_SEQUENCE_CHARACTERS = 4096;

    private final ArrayDeque<Line> history = new ArrayDeque<Line>();
    private final ArrayList<Line> screen = new ArrayList<Line>();
    private final StringBuilder csi = new StringBuilder();
    private byte[] pendingUtf8 = new byte[0];
    private int rows = 24;
    private int columns = 80;
    private int cursorRow;
    // columns means pending autowrap: CR/BS cancel it without adding a row.
    private int cursorColumn;
    private int savedRow;
    private int savedColumn;
    private int storedCharacters;
    private int ansiState;
    private int ansiSequenceCharacters;
    private boolean autoWrap = true;
    private boolean omitted;

    public TerminalOutputBuffer() {
        for (int index = 0; index < rows; index++) {
            screen.add(new Line());
        }
        storedCharacters = rows;
    }

    /** Use the same cell geometry as the remote PTY, including on resize. */
    public void resize(int requestedRows, int requestedColumns) {
        if (requestedRows < 1 || requestedRows > MAXIMUM_DIMENSION
            || requestedColumns < 1 || requestedColumns > MAXIMUM_DIMENSION) {
            throw new IllegalArgumentException("Terminal dimensions are invalid");
        }
        if (requestedRows == rows && requestedColumns == columns) {
            return;
        }
        while (screen.size() > requestedRows) {
            int bottom = screen.size() - 1;
            if (bottom > cursorRow && screen.get(bottom).visibleLength() == 0) {
                storedCharacters -= screen.remove(bottom).size();
            } else {
                history.addLast(screen.remove(0));
                cursorRow = Math.max(0, cursorRow - 1);
                savedRow = Math.max(0, savedRow - 1);
            }
        }
        while (screen.size() < requestedRows) {
            screen.add(new Line());
            storedCharacters++;
        }
        if (requestedColumns < columns) {
            for (Line line : screen) {
                line.truncate(requestedColumns);
                if (line.cells.size() == requestedColumns
                    && cellWidth(line.cells.get(requestedColumns - 1)) == 2) {
                    line.set(requestedColumns - 1, " ");
                }
            }
        }
        rows = requestedRows;
        columns = requestedColumns;
        cursorRow = Math.min(cursorRow, rows - 1);
        cursorColumn = Math.min(cursorColumn, columns - 1);
        trimToLimit();
    }

    public void append(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        byte[] combined = new byte[pendingUtf8.length + bytes.length];
        System.arraycopy(pendingUtf8, 0, combined, 0, pendingUtf8.length);
        System.arraycopy(bytes, 0, combined, pendingUtf8.length, bytes.length);
        Arrays.fill(pendingUtf8, (byte) 0);
        pendingUtf8 = new byte[0];
        try {
            int pendingLength = incompleteUtf8SuffixLength(combined);
            int decodedLength = combined.length - pendingLength;
            if (decodedLength > 0) {
                appendDecoded(new String(combined, 0, decodedLength, StandardCharsets.UTF_8));
            }
            if (pendingLength > 0) {
                pendingUtf8 = Arrays.copyOfRange(combined, decodedLength, combined.length);
            }
        } finally {
            Arrays.fill(combined, (byte) 0);
        }
    }

    public void finish() {
        if (pendingUtf8.length > 0) {
            Arrays.fill(pendingUtf8, (byte) 0);
            pendingUtf8 = new byte[0];
            appendDecoded("\uFFFD");
        }
        resetAnsi();
    }

    public void clear() {
        history.clear();
        for (Line line : screen) {
            line.cells.clear();
            line.softWrapped = false;
        }
        storedCharacters = rows;
        Arrays.fill(pendingUtf8, (byte) 0);
        pendingUtf8 = new byte[0];
        cursorRow = cursorColumn = savedRow = savedColumn = 0;
        autoWrap = true;
        omitted = false;
        resetAnsi();
    }

    public String snapshot() {
        StringBuilder result = new StringBuilder();
        StringBuilder logicalLine = new StringBuilder();
        ArrayList<Integer> wraps = new ArrayList<Integer>();
        if (omitted) {
            result.append(OMITTED_PREFIX);
        }
        for (Line line : history) {
            appendSnapshotLine(result, logicalLine, wraps, line, true);
        }
        int lastRow = cursorRow;
        for (int index = rows - 1; index > lastRow; index--) {
            if (screen.get(index).visibleLength() > 0) {
                lastRow = index;
                break;
            }
        }
        for (int index = 0; index <= lastRow; index++) {
            appendSnapshotLine(result, logicalLine, wraps, screen.get(index), index < lastRow);
        }
        appendRedactedLine(result, logicalLine, wraps);
        return CrashReportFormatter.redactVisibleText(result.toString(), MAXIMUM_CHARACTERS);
    }

    private void appendSnapshotLine(StringBuilder result, StringBuilder logicalLine,
        ArrayList<Integer> wraps, Line line, boolean newline) {
        line.appendTo(logicalLine, line.softWrapped);
        if (line.softWrapped && newline) {
            wraps.add(Integer.valueOf(logicalLine.length()));
        } else {
            appendRedactedLine(result, logicalLine, wraps);
            if (newline) {
                result.append('\n');
            }
        }
    }

    private void appendRedactedLine(StringBuilder result, StringBuilder logicalLine,
        ArrayList<Integer> wraps) {
        String original = logicalLine.toString();
        // Match secrets before reinserting soft wraps, which can split tokens.
        String redacted = CrashReportFormatter.redactSecrets(original);
        if (original.equals(redacted)) {
            int start = 0;
            for (Integer wrap : wraps) {
                appendVisibleSegment(result, original, start, wrap.intValue());
                result.append('\n');
                start = wrap.intValue();
            }
            appendVisibleSegment(result, original, start, original.length());
        } else {
            int column = 0;
            for (int index = 0; index < redacted.length();) {
                int character = redacted.codePointAt(index);
                int width = characterWidth(character);
                if (column + width > columns) {
                    result.append('\n');
                    column = 0;
                }
                result.appendCodePoint(character);
                column += width;
                index += Character.charCount(character);
            }
        }
        logicalLine.setLength(0);
        wraps.clear();
    }

    private static void appendVisibleSegment(StringBuilder result, String value, int start, int end) {
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        result.append(value, start, end);
    }

    private void appendDecoded(String decoded) {
        for (int index = 0; index < decoded.length();) {
            int character = decoded.codePointAt(index);
            index += Character.charCount(character);
            if (consumeAnsi(character)) {
                continue;
            }
            switch (character) {
                case 0x1b: ansiState = ANSI_ESCAPE; break;
                case '\r': cursorColumn = 0; break;
                case '\n':
                case 0x0b:
                case 0x0c: screen.get(cursorRow).softWrapped = false; lineFeed(); break;
                case '\b': cursorColumn = Math.max(0, cursorColumn - 1); break;
                case '\t': cursorColumn = Math.min(columns - 1, (cursorColumn / 8 + 1) * 8); break;
                default:
                    if (character >= 0x20 && character != 0x7f
                        && (character < 0x80 || character > 0x9f)) {
                        draw(character);
                    }
                    break;
            }
        }
        trimToLimit();
    }

    private boolean consumeAnsi(int character) {
        if (ansiState == ANSI_NORMAL) {
            return false;
        }
        if (ansiState == ANSI_ESCAPE) {
            if (character == '[') {
                ansiState = ANSI_CSI;
                csi.setLength(0);
            } else if (character == ']' || character == 'P'
                || character == '^' || character == '_') {
                ansiState = ANSI_OSC;
            } else if (character == '(' || character == ')' || character == '*'
                || character == '+') {
                ansiState = ANSI_CHARSET;
            } else {
                if (character == '7') {
                    saveCursor();
                } else if (character == '8') {
                    restoreCursor();
                } else if (character == 'D') {
                    lineFeed();
                } else if (character == 'E') {
                    cursorColumn = 0;
                    lineFeed();
                }
                resetAnsi();
            }
            ansiSequenceCharacters = 0;
        } else if (ansiState == ANSI_CHARSET) {
            resetAnsi();
        } else if (ansiState == ANSI_CSI) {
            if (character >= 0x40 && character <= 0x7e) {
                applyCsi(character);
                resetAnsi();
            } else if (++ansiSequenceCharacters >= MAXIMUM_ANSI_SEQUENCE_CHARACTERS) {
                resetAnsi();
            } else {
                csi.appendCodePoint(character);
            }
        } else if (ansiState == ANSI_OSC) {
            if (character == 0x07
                || ++ansiSequenceCharacters >= MAXIMUM_ANSI_SEQUENCE_CHARACTERS) {
                resetAnsi();
            } else if (character == 0x1b) {
                ansiState = ANSI_OSC_ESCAPE;
            }
        } else if (ansiState == ANSI_OSC_ESCAPE) {
            if (character == '\\'
                || ++ansiSequenceCharacters >= MAXIMUM_ANSI_SEQUENCE_CHARACTERS) {
                resetAnsi();
            } else {
                ansiState = ANSI_OSC;
            }
        }
        return true;
    }

    private void applyCsi(int command) {
        String parameters = csi.toString();
        if (parameters.startsWith("?")) {
            if ("?7".equals(parameters) && (command == 'h' || command == 'l')) {
                autoWrap = command == 'h';
            }
            return;
        }
        if (!parameters.matches("[0-9;]*")) {
            return;
        }
        String[] values = parameters.split(";", -1);
        int count = Math.max(1, parameter(values, 0, 1));
        switch (command) {
            case 'A': cursorRow = Math.max(0, cursorRow - count); break;
            case 'B': cursorRow = Math.min(rows - 1, cursorRow + count); break;
            case 'C': cursorColumn = Math.min(columns - 1, cursorColumn + count); break;
            case 'D': cursorColumn = Math.max(0, cursorColumn - count); break;
            case 'E': cursorRow = Math.min(rows - 1, cursorRow + count); cursorColumn = 0; break;
            case 'F': cursorRow = Math.max(0, cursorRow - count); cursorColumn = 0; break;
            case 'G': cursorColumn = Math.min(columns - 1, count - 1); break;
            case 'd': cursorRow = Math.min(rows - 1, count - 1); break;
            case 'H':
            case 'f':
                cursorRow = Math.min(rows - 1, count - 1);
                cursorColumn = Math.min(columns - 1, Math.max(1, parameter(values, 1, 1)) - 1);
                break;
            case 'K': eraseLine(parameter(values, 0, 0)); break;
            case 'J': eraseDisplay(parameter(values, 0, 0)); break;
            case 's': saveCursor(); break;
            case 'u': restoreCursor(); break;
            default: break; // Styling and other non-cursor sequences stay invisible.
        }
    }

    private static int parameter(String[] values, int index, int fallback) {
        if (index >= values.length || values[index].isEmpty()) {
            return fallback;
        }
        int value = 0;
        for (int digit = 0; digit < values[index].length(); digit++) {
            value = Math.min(MAXIMUM_DIMENSION, value * 10 + values[index].charAt(digit) - '0');
        }
        return value;
    }

    private void draw(int character) {
        int width = characterWidth(character);
        if (width == 0) {
            int row = cursorRow;
            int column = cursorColumn - 1;
            if (column < 0 && row > 0) {
                row--;
                column = columns - 1;
            }
            if (column >= 0) {
                Line line = screen.get(row);
                if (line.get(column).isEmpty() && column > 0) {
                    column--;
                }
                String previous = line.get(column);
                if (previous.length() + Character.charCount(character) <= MAXIMUM_CELL_CHARACTERS) {
                    line.set(column, Normalizer.normalize(
                        previous + new String(Character.toChars(character)), Normalizer.Form.NFC));
                }
            }
            return;
        }
        width = Math.min(width, columns);
        if (cursorColumn >= columns || cursorColumn + width > columns) {
            if (autoWrap) {
                screen.get(cursorRow).softWrapped = true;
                cursorColumn = 0;
                lineFeed();
            } else {
                cursorColumn = columns - width;
            }
        }
        Line line = screen.get(cursorRow);
        line.clearWideCharacter(cursorColumn);
        if (width == 2) {
            line.clearWideCharacter(cursorColumn + 1);
        }
        line.set(cursorColumn, new String(Character.toChars(character)));
        if (width == 2) {
            line.set(cursorColumn + 1, "");
        }
        cursorColumn += width;
    }

    private void lineFeed() {
        if (cursorRow < rows - 1) {
            cursorRow++;
        } else {
            history.addLast(screen.remove(0));
            screen.add(new Line());
            storedCharacters++;
        }
    }

    private void eraseLine(int mode) {
        Line line = screen.get(cursorRow);
        if (mode == 0) {
            line.erase(cursorColumn, columns);
        } else if (mode == 1) {
            line.erase(0, Math.min(columns, cursorColumn + 1));
        } else if (mode == 2) {
            line.erase(0, columns);
        }
    }

    private void eraseDisplay(int mode) {
        if (mode == 3) {
            while (!history.isEmpty()) {
                storedCharacters -= history.removeFirst().size();
            }
            omitted = false;
            return;
        }
        if (mode > 2) {
            return;
        }
        for (int index = 0; index < rows; index++) {
            if (mode == 2 || (mode == 0 && index > cursorRow)
                || (mode == 1 && index < cursorRow)) {
                screen.get(index).erase(0, columns);
            }
        }
        eraseLine(mode);
    }

    private void saveCursor() {
        savedRow = cursorRow;
        savedColumn = cursorColumn;
    }

    private void restoreCursor() {
        cursorRow = Math.min(rows - 1, savedRow);
        cursorColumn = Math.min(columns - 1, savedColumn);
    }

    private void resetAnsi() {
        ansiState = ANSI_NORMAL;
        ansiSequenceCharacters = 0;
        csi.setLength(0);
    }

    private void trimToLimit() {
        int limit = MAXIMUM_CHARACTERS - OMITTED_PREFIX.length();
        while (storedCharacters > limit && !history.isEmpty()) {
            storedCharacters -= history.removeFirst().size();
            omitted = true;
        }
        // Even the maximum supported 1000x1000 viewport must respect the limit.
        for (int index = 0; storedCharacters > limit && index < rows; index++) {
            if (index != cursorRow) {
                screen.get(index).erase(0, columns);
                omitted = true;
            }
        }
    }

    private static int cellWidth(String cell) {
        return cell.isEmpty() ? 0 : characterWidth(cell.codePointAt(0));
    }

    private static int characterWidth(int character) {
        int type = Character.getType(character);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
            || type == Character.FORMAT) {
            return 0;
        }
        if (character >= 0x1100 && (character <= 0x115f || character == 0x2329
            || character == 0x232a || (character >= 0x2e80 && character <= 0xa4cf && character != 0x303f)
            || (character >= 0xac00 && character <= 0xd7a3)
            || (character >= 0xf900 && character <= 0xfaff)
            || (character >= 0xfe10 && character <= 0xfe19)
            || (character >= 0xfe30 && character <= 0xfe6f)
            || (character >= 0xff00 && character <= 0xff60)
            || (character >= 0xffe0 && character <= 0xffe6)
            || (character >= 0x1f300 && character <= 0x1faff)
            || (character >= 0x20000 && character <= 0x3fffd))) {
            return 2;
        }
        return 1;
    }

    private final class Line {
        private final ArrayList<String> cells = new ArrayList<String>();
        private boolean softWrapped;

        private String get(int column) {
            return column < cells.size() ? cells.get(column) : " ";
        }

        private void set(int column, String value) {
            while (cells.size() <= column) {
                cells.add(" ");
                storedCharacters++;
            }
            storedCharacters += value.length() - cells.get(column).length();
            cells.set(column, value);
        }

        private void clearWideCharacter(int column) {
            if (get(column).isEmpty() && column > 0) {
                set(column - 1, " ");
                set(column, " ");
            } else if (cellWidth(get(column)) == 2) {
                set(column, " ");
                if (column + 1 < columns) {
                    set(column + 1, " ");
                }
            }
        }

        private void truncate(int length) {
            while (cells.size() > length) {
                storedCharacters -= cells.remove(cells.size() - 1).length();
            }
        }

        private void erase(int from, int to) {
            if (from == 0 && to >= cells.size()) {
                softWrapped = false;
            }
            if (from < cells.size()) {
                clearWideCharacter(from);
            }
            if (to < cells.size()) {
                clearWideCharacter(to);
            }
            for (int index = from; index < Math.min(to, cells.size()); index++) {
                set(index, " ");
            }
            truncate(visibleLength());
        }

        private int visibleLength() {
            int length = cells.size();
            while (length > 0 && " ".equals(cells.get(length - 1))) {
                length--;
            }
            return length;
        }

        private int size() {
            int length = 1;
            for (String cell : cells) {
                length += cell.length();
            }
            return length;
        }

        private void appendTo(StringBuilder result, boolean preserveSpaces) {
            int length = preserveSpaces ? cells.size() : visibleLength();
            for (int index = 0; index < length; index++) {
                result.append(cells.get(index));
            }
        }
    }

    private static int incompleteUtf8SuffixLength(byte[] value) {
        if (value.length == 0) {
            return 0;
        }
        int start = value.length - 1;
        int continuations = 0;
        while (start >= 0 && continuations < 3 && (value[start] & 0xc0) == 0x80) {
            continuations++;
            start--;
        }
        if (start < 0) {
            return 0;
        }
        int lead = value[start] & 0xff;
        int expected;
        if ((lead & 0xe0) == 0xc0) {
            expected = 2;
        } else if ((lead & 0xf0) == 0xe0) {
            expected = 3;
        } else if ((lead & 0xf8) == 0xf0) {
            expected = 4;
        } else {
            return 0;
        }
        int available = value.length - start;
        return available < expected ? available : 0;
    }
}
