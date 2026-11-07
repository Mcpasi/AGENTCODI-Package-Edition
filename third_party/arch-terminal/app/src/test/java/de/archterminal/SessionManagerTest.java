package de.archterminal;

import java.io.IOException;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionManagerTest {
    private static final class Shell {
        boolean running = true;
        int stops;
        String output = "";
    }

    private static final class Backend implements SessionManager.Backend<Shell> {
        boolean fail;
        @Override public Shell create() throws IOException {
            if (fail) throw new IOException("PTY unavailable");
            return new Shell();
        }
        @Override public boolean isRunning(Shell terminal) { return terminal.running; }
        @Override public void finish(Shell terminal) { terminal.stops++; }
    }

    private final Backend backend = new Backend();
    private final SessionManager<Shell> sessions = new SessionManager<>(backend);

    @Test public void shellsKeepIndependentStateAcrossSelection() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        first.terminal.output = "first shell history";
        SessionManager.Entry<Shell> second = sessions.create();
        second.terminal.output = "second shell history";
        assertSame(second, sessions.selected());
        assertTrue(sessions.select(first));
        assertEquals("first shell history", sessions.selected().terminal.output);
        assertEquals("second shell history", second.terminal.output);
        assertEquals(2, sessions.runningCount());
        assertEquals(0, first.terminal.stops + second.terminal.stops);
    }

    @Test public void failedCreationKeepsSelectionAndDoesNotConsumeANumber() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        backend.fail = true;
        assertThrows(IOException.class, sessions::create);
        assertSame(first, sessions.selected());
        assertEquals(1, sessions.entries().size());
        assertEquals(1, sessions.runningCount());
        backend.fail = false;
        assertEquals(2, sessions.create().number);
    }

    @Test public void closingWaitsForExitAndOnlyStopsItsOwnShell() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        SessionManager.Entry<Shell> second = sessions.create();
        assertTrue(sessions.close(first));
        assertTrue(first.closing());
        assertEquals(1, first.terminal.stops);
        assertEquals(0, second.terminal.stops);
        assertEquals(2, sessions.runningCount());
        assertEquals(2, sessions.entries().size());
        assertFalse(sessions.close(first));
        first.terminal.running = false;
        assertTrue(sessions.finished(first.terminal));
        assertSame(second, sessions.selected());
        assertEquals(1, sessions.runningCount());
        assertFalse(sessions.finished(first.terminal));
        assertFalse(sessions.select(first));
        assertFalse(sessions.close(first));
    }

    @Test public void closingSelectedShellSelectsANeighborAndKeepsNumbersStable() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        SessionManager.Entry<Shell> middle = sessions.create();
        SessionManager.Entry<Shell> last = sessions.create();
        sessions.select(middle);
        middle.terminal.running = false;
        sessions.finished(middle.terminal);
        assertTrue(sessions.close(middle));
        assertSame(last, sessions.selected());
        assertEquals(1, first.number);
        assertEquals(3, last.number);
        last.terminal.running = false;
        sessions.finished(last.terminal);
        sessions.close(last);
        assertSame(first, sessions.selected());
        assertEquals(4, sessions.create().number);
    }

    @Test public void naturalBackgroundExitKeepsTranscriptAndOtherShellRunning() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        SessionManager.Entry<Shell> second = sessions.create();
        first.terminal.output = "final output";
        first.terminal.running = false;
        assertTrue(sessions.finished(first.terminal));
        assertEquals(2, sessions.entries().size());
        assertSame(second, sessions.selected());
        assertEquals(1, sessions.runningCount());
        sessions.select(first);
        assertEquals("final output", sessions.selected().terminal.output);
    }

    @Test public void restartReplacesOnlyEndedTabAndIgnoresItsLateCallback() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        SessionManager.Entry<Shell> second = sessions.create();
        sessions.select(first);
        first.terminal.running = false;
        sessions.finished(first.terminal);
        SessionManager.Entry<Shell> replacement = sessions.restartSelected();
        assertNotSame(first.terminal, replacement.terminal);
        assertEquals(first.number, replacement.number);
        assertSame(replacement, sessions.entries().get(0));
        assertSame(second, sessions.entries().get(1));
        assertEquals(2, sessions.runningCount());
        assertFalse(sessions.finished(first.terminal));
        assertSame(replacement, sessions.selected());
        assertEquals(0, second.terminal.stops);
    }

    @Test public void failedRestartPreservesEndedTranscriptAndOtherShell() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        first.terminal.running = false;
        sessions.finished(first.terminal);
        first.terminal.output = "exit details";
        SessionManager.Entry<Shell> second = sessions.create();
        sessions.select(first);
        backend.fail = true;
        assertThrows(IOException.class, sessions::restartSelected);
        assertSame(first, sessions.selected());
        assertEquals("exit details", first.terminal.output);
        assertEquals(2, sessions.entries().size());
        assertEquals(1, sessions.runningCount());
        assertEquals(0, second.terminal.stops);
    }

    @Test public void runningAndClosingTabsCannotBeRestarted() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        assertSame(first, sessions.restartSelected());
        sessions.close(first);
        first.terminal.running = false;
        assertSame(first, sessions.restartSelected());
        assertEquals(1, sessions.entries().size());
    }

    @Test public void closeAfterProcessExitWaitsForDrainedOutputCallback() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        SessionManager.Entry<Shell> second = sessions.create();
        sessions.select(first);
        // waitpid has completed, but the reader and final rendering have not.
        first.terminal.running = false;
        assertTrue(sessions.close(first));
        assertTrue("Draining PTY must remain owned until its exit callback", sessions.entries().contains(first));
        assertTrue(first.closing());
        assertSame(first, sessions.selected());
        assertSame(first, sessions.restartSelected());
        first.terminal.output = "last buffered output";
        assertTrue(sessions.finished(first.terminal));
        assertFalse(sessions.entries().contains(first));
        assertSame(second, sessions.selected());
        assertEquals(0, second.terminal.stops);
    }

    @Test public void restartAfterProcessExitWaitsForDrainedOutputCallback() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        first.terminal.running = false;
        assertSame("Restart must wait for the old PTY's final output", first, sessions.restartSelected());
        assertEquals(1, sessions.entries().size());
        first.terminal.output = "last buffered output";
        assertTrue(sessions.finished(first.terminal));
        assertEquals("last buffered output", sessions.selected().terminal.output);
        SessionManager.Entry<Shell> replacement = sessions.restartSelected();
        assertNotSame(first, replacement);
        assertEquals(first.number, replacement.number);
        assertEquals(2, sessions.create().number);
    }

    @Test public void stopAllKeepsDrainingPtysUntilTheirCallbacks() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        SessionManager.Entry<Shell> second = sessions.create();
        first.terminal.running = false;
        second.terminal.running = false;
        sessions.closeAll();
        assertEquals("Reaped children may still have pending PTY output", 2, sessions.entries().size());
        sessions.finished(second.terminal);
        assertSame(first, sessions.selected());
        sessions.finished(first.terminal);
        assertTrue(sessions.isEmpty());
    }

    @Test public void stopAllIncludesBackgroundShellsAndWaitsForEveryExit() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        SessionManager.Entry<Shell> second = sessions.create();
        SessionManager.Entry<Shell> ended = sessions.create();
        ended.terminal.running = false;
        sessions.finished(ended.terminal);
        sessions.closeAll();
        assertEquals(1, first.terminal.stops);
        assertEquals(1, second.terminal.stops);
        assertEquals(0, ended.terminal.stops);
        assertEquals(2, sessions.entries().size());
        second.terminal.running = false;
        sessions.finished(second.terminal);
        assertEquals(1, sessions.runningCount());
        assertFalse(sessions.isEmpty());
        first.terminal.running = false;
        sessions.finished(first.terminal);
        assertEquals(0, sessions.runningCount());
        assertTrue(sessions.isEmpty());
        assertNull(sessions.selected());
    }

    @Test public void sessionListIsAnImmutableSnapshot() throws Exception {
        SessionManager.Entry<Shell> first = sessions.create();
        List<SessionManager.Entry<Shell>> snapshot = sessions.entries();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        sessions.create();
        assertEquals(1, snapshot.size());
        assertSame(first, snapshot.get(0));
    }

    @Test public void emptyManagerCanStartAndCloseItsLastEndedTab() throws Exception {
        assertNull(sessions.selected());
        assertEquals(0, sessions.runningCount());
        assertTrue(sessions.isEmpty());
        SessionManager.Entry<Shell> first = sessions.restartSelected();
        first.terminal.running = false;
        sessions.finished(first.terminal);
        sessions.close(first);
        assertTrue(sessions.isEmpty());
        assertNull(sessions.selected());
        assertFalse(sessions.finished(new Shell()));
    }
}
