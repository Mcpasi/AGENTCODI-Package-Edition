package de.archterminal;

import java.io.File;

/** Apply exactly the app's package configuration to the disposable smoke-test image. */
public final class ConfigureRootfs {
    public static void main(String[] arguments) throws Exception {
        File root = new File(arguments[0]);
        if (arguments.length > 1 && "--upgrade".equals(arguments[1])) RootfsConfig.upgrade(root, path -> {});
        else RootfsConfig.configure(root);
    }
}
