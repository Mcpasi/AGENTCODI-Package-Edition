package de.agentcodi.app;

import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class ArchTerminalNavigationTest {
    @Test public void terminalOpensArchWithoutStartingCodexOrDownloadingPackages() {
        TerminalActivity activity = Robolectric.buildActivity(TerminalActivity.class).create().get();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals("de.archterminal.MainActivity", next.getComponent().getClassName());
        assertTrue(activity.isFinishing());
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }
}
