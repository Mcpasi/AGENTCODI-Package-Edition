#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "$0")/android-env.sh"
cd "$ARCH_PROJECT_ROOT"
if [ -f "$ANDROID_HOME/platforms/android-36/android.jar" ] && [ -x "$ANDROID_HOME/build-tools/35.0.0/aapt2" ] && [ -f "$ANDROID_NDK_HOME/source.properties" ]; then
  echo 'Reusing installed Android SDK 36, build tools 35.0.0 and NDK r28c.'
  exit 0
fi
manager="$ANDROID_HOME/cmdline-tools/9.0/bin/sdkmanager"
if [ ! -x "$manager" ]; then
  mkdir -p .cache/tools "$ANDROID_HOME/cmdline-tools"
  archive="$ARCH_PROJECT_ROOT/.cache/tools/commandlinetools-linux-9123335_latest.zip"
  if [ ! -f "$archive" ]; then
    curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-9123335_latest.zip -o "$archive"
  fi
  printf '%s  %s\n' 0bebf59339eaa534f4217f8aa0972d14dc49e7207be225511073c661ae01da0a "$archive" | sha256sum -c -
  unpack="$ARCH_PROJECT_ROOT/.cache/tools/android-command-tools"
  mkdir -p "$unpack"
  unzip -qo "$archive" -d "$unpack"
  mv "$unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/9.0"
fi
"$manager" --sdk_root="$ANDROID_HOME" --licenses < <(yes)
"$manager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools' 'ndk;28.2.13676358'
echo 'Android toolchain installed. JDK 17, Python 3.12+, CMake 3.22+, make, a C++17 compiler and libarchive development headers are also required for all tests.'
