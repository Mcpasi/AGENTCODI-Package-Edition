package de.archterminal;

import android.util.Log;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

abstract class SessionCallbacks implements TerminalSessionClient {
    public void onTextChanged(TerminalSession session) {}
    public void onTitleChanged(TerminalSession session) {}
    public void onSessionFinished(TerminalSession session) {}
    public void onCopyTextToClipboard(TerminalSession session, String text) {}
    public void onPasteTextFromClipboard(TerminalSession session) {}
    public void onBell(TerminalSession session) {}
    public void onColorsChanged(TerminalSession session) {}
    public void onTerminalCursorStateChange(boolean state) {}
    public Integer getTerminalCursorStyle() { return 0; }
    public void logError(String tag, String message) { Log.e(tag, message); }
    public void logWarn(String tag, String message) { Log.w(tag, message); }
    public void logInfo(String tag, String message) { Log.i(tag, message); }
    public void logDebug(String tag, String message) { Log.d(tag, message); }
    public void logVerbose(String tag, String message) { Log.v(tag, message); }
    public void logStackTraceWithMessage(String tag, String message, Exception error) { Log.e(tag, message, error); }
    public void logStackTrace(String tag, Exception error) { Log.e(tag, error.toString(), error); }
}
