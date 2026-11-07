package de.archterminal;

import android.content.Context;
import de.agentcodi.storage.ArchCommandWrappers;
import java.io.File;
import java.io.IOException;

/** AGENTCODI adapter; the vendored Arch Terminal sources remain unchanged. */
public final class AgentCodiArch {
    public static void prepare(Context context, File state) throws Exception {
        if (!RootfsInstaller.installed(context)) {
            throw new IOException("Arch Terminal öffnen und zuerst Arch installieren / fortsetzen.");
        }
        RuntimeFiles.prepare(context);
        boolean compatible = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)
            .getBoolean("no_seccomp", false);
        ArchCommandWrappers.prepare(state, context.getFilesDir(),
            new File(context.getApplicationInfo().nativeLibraryDir, "libagentcodi-shell.so"),
            compatible, new File(context.getFilesDir(), "arch/usr/bin").list());
    }
    private AgentCodiArch() {}
}
