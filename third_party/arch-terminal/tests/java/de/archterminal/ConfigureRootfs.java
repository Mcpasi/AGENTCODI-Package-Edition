package de.archterminal;

import java.io.File;

/** Apply exactly the app's package configuration to the disposable smoke-test image. */
public final class ConfigureRootfs {
    public static void main(String[] arguments) throws Exception { RootfsConfig.configure(new File(arguments[0])); }
}
