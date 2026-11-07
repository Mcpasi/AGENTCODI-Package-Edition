package de.agentcodi.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import de.agentcodi.runtime.AgentRuntimeService;

/** Keeps existing navigation routes while handing the console to Arch Terminal. */
public final class TerminalActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            AgentRuntimeService.prepareArchTerminalWorkspace(this);
            startActivity(new Intent(this, de.archterminal.MainActivity.class));
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
