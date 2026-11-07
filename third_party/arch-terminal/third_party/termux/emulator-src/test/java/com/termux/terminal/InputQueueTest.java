package com.termux.terminal;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class InputQueueTest {
    @Test public void queueKeepsOrderAndOwnsTheBytes() {
        InputQueue queue = new InputQueue(4);
        byte[] bytes = {1, 2, 3};
        assertTrue(queue.offer(bytes, 0, 3));
        bytes[0] = 9;
        assertTrue(queue.offer(new byte[]{4}, 0, 1));
        assertFalse(queue.offer(new byte[]{5}, 0, 1));
        assertArrayEquals(new byte[]{1, 2, 3}, queue.take());
        assertTrue(queue.offer(new byte[]{5}, 0, 1));
        assertArrayEquals(new byte[]{4}, queue.take());
        assertArrayEquals(new byte[]{5}, queue.take());
    }
    @Test public void closeWakesWriterWithoutADeadline() throws Exception {
        InputQueue queue = new InputQueue(2);
        AtomicBoolean ended = new AtomicBoolean();
        Thread thread = new Thread(() -> { assertNull(queue.take()); ended.set(true); });
        thread.start();
        queue.close();
        thread.join(1000);
        assertTrue(ended.get());
        assertFalse(queue.offer(new byte[]{1}, 0, 1));
    }
    @Test public void rejectsOversizedInputWithoutClosingQueue() {
        InputQueue queue = new InputQueue(2);
        assertFalse(queue.offer(new byte[]{1, 2, 3}, 0, 3));
        assertTrue(queue.offer(new byte[]{7}, 0, 1));
        assertArrayEquals(new byte[]{7}, queue.take());
    }
}
