package de.archterminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Main-thread session ownership and selection, independent of Android and the PTY implementation. */
final class SessionManager<T> {
    interface Backend<T> {
        T create() throws Exception;
        boolean isRunning(T terminal);
        void finish(T terminal);
    }

    static final class Entry<T> {
        final int number;
        final T terminal;
        private boolean closing, finished;

        private Entry(int number, T terminal) { this.number = number; this.terminal = terminal; }
        boolean closing() { return closing; }
    }

    private final Backend<T> backend;
    private final List<Entry<T>> entries = new ArrayList<>();
    private Entry<T> selected;
    private int nextNumber = 1;

    SessionManager(Backend<T> backend) { this.backend = backend; }

    List<Entry<T>> entries() { return Collections.unmodifiableList(new ArrayList<>(entries)); }
    Entry<T> selected() { return selected; }
    boolean isEmpty() { return entries.isEmpty(); }

    Entry<T> create() throws Exception {
        // A failed PTY start leaves the existing terminals, selection and numbering intact.
        T terminal = backend.create();
        Entry<T> entry = new Entry<>(nextNumber++, terminal);
        entries.add(entry);
        selected = entry;
        return entry;
    }

    Entry<T> restartSelected() throws Exception {
        if (selected == null) return create();
        if (selected.closing || !selected.finished || backend.isRunning(selected.terminal)) return selected;
        T terminal = backend.create();
        Entry<T> replacement = new Entry<>(selected.number, terminal);
        entries.set(entries.indexOf(selected), replacement);
        selected = replacement;
        return replacement;
    }

    boolean select(Entry<T> entry) {
        if (!entries.contains(entry)) return false;
        selected = entry;
        return true;
    }

    boolean close(Entry<T> entry) {
        if (!entries.contains(entry) || entry.closing) return false;
        if (!entry.finished) {
            entry.closing = true;
            // Retain ownership until the PTY has drained output and delivered its exit callback.
            if (backend.isRunning(entry.terminal)) backend.finish(entry.terminal);
        } else remove(entry);
        return true;
    }

    boolean finished(T terminal) {
        for (Entry<T> entry : entries) {
            if (entry.terminal == terminal) {
                // A reaped process can still have unread or unrendered PTY output.
                entry.finished = true;
                if (entry.closing) remove(entry);
                return true;
            }
        }
        return false;
    }

    int runningCount() {
        int count = 0;
        for (Entry<T> entry : entries) if (backend.isRunning(entry.terminal)) count++;
        return count;
    }

    void closeAll() {
        for (Entry<T> entry : entries()) close(entry);
    }

    private void remove(Entry<T> entry) {
        int index = entries.indexOf(entry);
        entries.remove(index);
        if (selected == entry) selected = entries.isEmpty() ? null : entries.get(Math.min(index, entries.size() - 1));
    }
}
