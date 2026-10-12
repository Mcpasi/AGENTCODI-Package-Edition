package de.agentcodi.freedom;

import de.agentcodi.core.UiLanguage;

/** Official campaign destinations, independent of Android views and runtime state. */
public final class AndroidFreedomSupport {
    private static final String WEBSITE = "https://keepandroidopen.org/";

    private AndroidFreedomSupport() {
    }

    public static String websiteForLanguage(UiLanguage language) {
        if (language == UiLanguage.GERMAN) {
            return WEBSITE + "de/";
        }
        if (language == UiLanguage.SIMPLIFIED_CHINESE) {
            return WEBSITE + "zh-CN/";
        }
        return WEBSITE;
    }
}
