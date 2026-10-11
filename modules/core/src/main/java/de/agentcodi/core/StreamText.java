package de.agentcodi.core;

import java.util.ArrayDeque;

/** Immutable redacted segments; appending never copies the settled stream prefix. */
final class StreamText {
    private static final String TRUNCATION = "\n… Ausgabe gekürzt …";
    private static final int SEGMENT_CHARACTERS = 128;
    private static final StreamText EMPTY = literal("");

    private final Node prefix;
    private final String tail;
    private final int length;
    private final String sectionMarker;
    private final String sectionBase;
    private final int sectionOutputLength;
    private final String appendedDelta;
    private final int previousLength;
    private volatile String appendBase;
    private volatile String materialized;
    private volatile Boolean settledBeforeNewline;

    private StreamText(Node prefix, String tail, String materialized,
        String sectionMarker, String sectionBase) {
        this(prefix, tail, materialized, sectionMarker, sectionBase, -1, null, null, -1);
    }

    private StreamText(Node prefix, String tail, String materialized,
        String sectionMarker, String sectionBase, int sectionOutputLength,
        String appendBase, String appendedDelta, int previousLength) {
        this.prefix = prefix;
        this.tail = tail;
        this.length = size(prefix) + tail.length();
        this.materialized = materialized;
        this.sectionMarker = sectionMarker;
        this.sectionBase = sectionBase;
        this.sectionOutputLength = sectionOutputLength;
        this.appendBase = appendBase;
        this.appendedDelta = appendedDelta;
        this.previousLength = previousLength;
    }

    static StreamText empty() {
        return EMPTY;
    }

    static StreamText literal(String value) {
        String text = value == null ? "" : value;
        return new StreamText(null, text, text, "", "");
    }

    static StreamText redacted(String value) {
        return literal(CrashReportFormatter.redactSecrets(value));
    }

    int length() {
        return length;
    }

    boolean isEmpty() {
        return length == 0;
    }

    boolean settlesBeforeNewline() {
        Boolean result = settledBeforeNewline;
        if (result == null) {
            String boundary = tail + "\n";
            result = Boolean.valueOf(CrashReportFormatter.stableRedactedPrefixLength(boundary)
                == boundary.length() - 1);
            settledBeforeNewline = result;
        }
        return result.booleanValue();
    }

    static StreamText joinSettled(StreamText left, StreamText right) {
        if (left.isEmpty()) {
            return right;
        }
        return new StreamText(concat(concat(left.node(), leaf("\n")), right.prefix),
            right.tail, null, "", "");
    }

    StreamText append(String delta, int maximumCharacters, boolean removeNulls) {
        if (length >= maximumCharacters || delta.isEmpty()) {
            return this;
        }
        int remaining = maximumCharacters - length;
        String addition = delta;
        if (delta.length() > remaining) {
            addition = remaining <= TRUNCATION.length()
                ? TRUNCATION.substring(0, remaining)
                : delta.substring(0, remaining - TRUNCATION.length()) + TRUNCATION;
        }
        // Match the existing ordering: bound the incoming text, remove NULs, then redact.
        if (removeNulls && addition.indexOf('\0') >= 0) {
            addition = addition.replace("\0", "");
        }
        String combined = tail + addition;
        String safe = CrashReportFormatter.redactSecrets(combined);
        int settled = CrashReportFormatter.stableRedactedPrefixLength(safe);
        Node nextPrefix = appendSettled(prefix, safe.substring(0, settled));
        boolean appendOnly = safe.equals(combined);
        return new StreamText(nextPrefix, safe.substring(settled), null, "", "", -1,
            appendOnly ? materialized : null, appendOnly ? addition : null, length);
    }

    StreamText visibleLimit(int maximumCharacters) {
        if (length <= maximumCharacters) {
            return this;
        }
        if (maximumCharacters <= 3) {
            return take(Math.max(0, maximumCharacters));
        }
        return new StreamText(concat(take(maximumCharacters - 3).node(), leaf("...")),
            "", null, "", "");
    }

    /** Format a tool section without flattening its growing output on every delta. */
    static StreamText section(StreamText existing, String label, StreamText stream,
        int maximumCharacters) {
        String marker = label + ":\n";
        String base;
        if (marker.equals(existing.sectionMarker)) {
            base = existing.sectionBase;
        } else {
            base = existing.text();
            int index = base.indexOf(marker);
            if (index >= 0) {
                while (index > 0 && base.charAt(index - 1) == '\n') {
                    index--;
                }
                base = base.substring(0, index);
            }
        }
        if (stream.isEmpty() || base.length() >= maximumCharacters) {
            return literal(CrashReportFormatter.redactVisibleText(base, maximumCharacters));
        }
        String heading = base + (base.isEmpty() ? "" : "\n\n") + marker;
        if (heading.length() + stream.length() > maximumCharacters) {
            // Clipping can cut a redaction marker; the original formatter redacts that
            // clipped value again before adding its ellipsis. Preserve that exact order.
            String output = stream.text();
            int remaining = Math.max(0, maximumCharacters - heading.length());
            return literal(CrashReportFormatter.redactVisibleText(
                heading + output.substring(0, Math.min(output.length(), remaining)),
                maximumCharacters));
        }
        // The fixed German label followed by LF settles any pending secret in the base.
        String safeHeading = CrashReportFormatter.redactSecrets(heading);
        boolean appendOnly = marker.equals(existing.sectionMarker)
            && existing.sectionOutputLength == stream.previousLength
            && stream.appendedDelta != null;
        StreamText result = new StreamText(concat(leaf(safeHeading), stream.node()),
            "", null, safeHeading.contains(marker) ? marker : "", base, stream.length(),
            appendOnly ? existing.materialized : null,
            appendOnly ? stream.appendedDelta : null, existing.length());
        return result.visibleLimit(maximumCharacters);
    }

    String text() {
        String result = materialized;
        if (result == null) {
            String base = appendBase;
            if (base != null && appendedDelta != null
                && previousLength + appendedDelta.length() == length) {
                result = base + appendedDelta;
            } else {
                StringBuilder builder = new StringBuilder(length);
                ArrayDeque<Node> pending = new ArrayDeque<Node>();
                if (prefix != null) {
                    pending.push(prefix);
                }
                while (!pending.isEmpty()) {
                    Node node = pending.pop();
                    if (node.value != null) {
                        builder.append(node.value);
                    } else {
                        pending.push(node.right);
                        pending.push(node.left);
                    }
                }
                result = builder.append(tail).toString();
            }
            materialized = result;
            appendBase = null;
        }
        return result;
    }

    private StreamText take(int characters) {
        if (characters >= length) {
            return this;
        }
        Node current = node();
        Node retained = null;
        int remaining = characters;
        while (remaining > 0 && current != null) {
            if (current.value != null) {
                retained = concat(retained, leaf(current.value.substring(0, remaining)));
                break;
            }
            int leftCharacters = current.left.length;
            if (remaining <= leftCharacters) {
                current = current.left;
            } else {
                retained = concat(retained, current.left);
                remaining -= leftCharacters;
                current = current.right;
            }
        }
        return new StreamText(retained, "", null, "", "");
    }

    private Node node() {
        return concat(prefix, leaf(tail));
    }

    private static int size(Node value) {
        return value == null ? 0 : value.length;
    }

    private static Node leaf(String value) {
        return value.isEmpty() ? null : new Node(value, null, null);
    }

    private static Node concat(Node left, Node right) {
        return left == null ? right : right == null ? left : new Node(null, left, right);
    }

    private static Node appendSettled(Node prefix, String addition) {
        if (addition.isEmpty()) {
            return prefix;
        }
        Node last = prefix == null || prefix.value != null ? prefix : prefix.right;
        // Coalesce tiny deltas to bound node overhead, sharing ordinary chunks directly.
        if (addition.length() <= SEGMENT_CHARACTERS / 4 && last != null && last.value != null
            && last.length + addition.length() <= SEGMENT_CHARACTERS) {
            Node combined = leaf(last.value + addition);
            return prefix.value != null ? combined : concat(prefix.left, combined);
        }
        return concat(prefix, leaf(addition));
    }

    private static final class Node {
        private final String value;
        private final Node left;
        private final Node right;
        private final int length;

        private Node(String value, Node left, Node right) {
            this.value = value;
            this.left = left;
            this.right = right;
            this.length = value == null ? left.length + right.length : value.length();
        }
    }
}
