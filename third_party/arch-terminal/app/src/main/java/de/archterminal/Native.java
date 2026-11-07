package de.archterminal;

final class Native {
    static { System.loadLibrary("archterminal"); }
    static native void extract(String archive, String destination);
    static native void repairDirectoryModes(String rootfs);
    static native void resetExtractionCancellation();
    static native void cancelExtraction();
    private Native() {}
}
