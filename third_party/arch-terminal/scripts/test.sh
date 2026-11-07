#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "$0")/android-env.sh"
cd "$ARCH_PROJECT_ROOT"
mkdir -p output/tests
python3 scripts/fetch-sources.py
cmake -S . -B .build/host -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DLIBARCHIVE_SOURCE="$ARCH_PROJECT_ROOT/.build/sources/libarchive"
cmake --build .build/host -j"${ARCH_BUILD_JOBS:-1}"
ctest --test-dir .build/host --parallel 1 --output-on-failure --output-junit "$ARCH_PROJECT_ROOT/output/tests/native.xml" --output-log "$ARCH_PROJECT_ROOT/output/tests/native.txt"
python3 -m unittest discover -s tests/python -v 2>&1 | tee output/tests/python.txt
./gradlew --no-daemon --no-parallel --max-workers=1 --console=plain :terminal-emulator:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
