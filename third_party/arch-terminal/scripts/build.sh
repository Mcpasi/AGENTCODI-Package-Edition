#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "$0")/android-env.sh"
cd "$ARCH_PROJECT_ROOT"
./gradlew --no-daemon --no-parallel --max-workers=1 --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
mkdir -p output
cp app/build/outputs/apk/debug/app-debug.apk output/ArchTerminal-0.2.0-arm64-debug.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk output/ArchTerminal-tests.apk
sha256sum output/*.apk > output/SHA256SUMS
echo 'APK: output/ArchTerminal-0.2.0-arm64-debug.apk'
