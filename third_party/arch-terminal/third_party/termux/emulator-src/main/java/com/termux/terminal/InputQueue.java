package com.termux.terminal;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Bounded, nonblocking producer for the UI; a slow child cannot block the main thread. */
final class InputQueue {
    private final ArrayDeque<byte[]> entries = new ArrayDeque<>();
    private final int capacity;
    private int bytes;
    private boolean closed;
    InputQueue(int capacity) { this.capacity = capacity; }
    synchronized boolean offer(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || offset > data.length - count) throw new IndexOutOfBoundsException();
        if (closed || count > capacity - bytes) return false;
        if (count == 0) return true;
        entries.addLast(Arrays.copyOfRange(data, offset, offset + count));
        bytes += count;
        notifyAll();
        return true;
    }
    synchronized byte[] take() {
        while (entries.isEmpty() && !closed) {
            try { wait(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return null; }
        }
        if (closed) return null;
        byte[] result = entries.removeFirst();
        bytes -= result.length;
        return result;
    }
    synchronized void close() { closed = true; entries.clear(); bytes = 0; notifyAll(); }
}
