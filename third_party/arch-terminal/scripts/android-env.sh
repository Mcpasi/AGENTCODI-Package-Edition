#!/usr/bin/env bash
# Sourced by build scripts. Existing SDK/NDK caches are preferred.
ARCH_PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
  elif [ -d "$HOME/.buildozer/android/platform/android-sdk/platforms/android-36" ]; then
    export ANDROID_HOME="$HOME/.buildozer/android/platform/android-sdk"
  else
    export ANDROID_HOME="$ARCH_PROJECT_ROOT/.cache/android-sdk"
  fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$ANDROID_HOME/ndk/28.2.13676358" ]; then
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
  elif [ -d "$HOME/.buildozer/android/platform/android-ndk-r28c" ]; then
    export ANDROID_NDK_HOME="$HOME/.buildozer/android/platform/android-ndk-r28c"
  else
    export ANDROID_NDK_HOME="$ARCH_PROJECT_ROOT/.cache/android-ndk-r28c"
  fi
fi
