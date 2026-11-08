#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "$0")/android-env.sh"
cd "$ARCH_PROJECT_ROOT"
adb_bin="${ARCH_ADB:-adb}"
if [ "$("$adb_bin" get-state 2>/dev/null || true)" != device ]; then
  echo 'No authorized Android device. Connect an ARM64 phone with USB debugging and rerun.' >&2
  exit 2
fi
if ! "$adb_bin" shell getprop ro.product.cpu.abilist | tr -d '\r' | grep -q arm64-v8a; then
  echo 'These APKs require an ARM64 Android device.' >&2
  exit 2
fi
scripts/build.sh
"$adb_bin" install -r output/ArchTerminal-0.2.0-arm64-debug.apk
"$adb_bin" install -r output/ArchTerminal-tests.apk
args=()
if [ "${1:-}" = --rootfs ]; then args=(-e rootfs true); fi
mkdir -p output/tests
"$adb_bin" shell am instrument -w -r "${args[@]}" de.archterminal.test/de.archterminal.TerminalInstrumentation | tee output/tests/device.txt
if ! grep -q 'archterminal.success=true' output/tests/device.txt; then
  echo 'Android instrumentation failed or did not report success.' >&2
  exit 1
fi
