package de.archterminal;

import android.content.Context;
import android.content.res.Configuration;

final class AppLanguage {
    static TerminalLanguage get(Context context) {
        return TerminalLanguage.fromPreference(context.getSharedPreferences("terminal", Context.MODE_PRIVATE)
            .getString("language", "de"));
    }

    static void set(Context context, TerminalLanguage language) {
        context.getSharedPreferences("terminal", Context.MODE_PRIVATE).edit().putString("language", language.code).apply();
    }

    static Context wrap(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(get(context).locale);
        return context.createConfigurationContext(config);
    }

    /** Service and installer resources follow preference changes without restarting any PTY. */
    static String text(Context context, int resource, Object... arguments) {
        return wrap(context).getString(resource, arguments);
    }

    private AppLanguage() {}
}
