# Vendored terminal components

Source: termux/termux-app v0.118.3, commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.

`emulator-src` and `view-src` contain the upstream sources and tests. Their Apache-2.0 exception is documented in `UPSTREAM-LICENSE.md`. The adjacent Gradle projects compile those trees directly. Upstream JNI C code is not built. Native calls resolve to `native/jni.cpp` and `native/pty.cpp`.

Local changes are described in the root `NOTICE.md`. Tests marked `InputQueueTest` are new; the existing VT, Unicode, color, scrolling and control sequence tests remain intact.
