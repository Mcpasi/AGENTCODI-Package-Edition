#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "$0")/android-env.sh"
cd "$ARCH_PROJECT_ROOT"
python3 scripts/fetch-sources.py
mkdir -p output
# Ship this with distributed APKs: exact sources and build instructions, including LGPL relinking inputs.
tar --exclude=.git --exclude=.cache --exclude=.build --exclude=.gradle --exclude=build \
  --exclude=output --exclude=local.properties -czf output/ArchTerminal-0.1.9-source.tar.gz \
  .github .gitignore app native scripts tests docs third_party gradle gradlew build.gradle settings.gradle gradle.properties CMakeLists.txt README.md CHANGELOG.md LICENSE NOTICE.md
tar -czf output/ArchTerminal-0.1.9-native-inputs.tar.gz -C .cache/sources \
  proot-v5.1.107.102.zip talloc-2.4.3.tar.gz libarchive-3.7.7.tar.xz
sha256sum output/*.tar.gz > output/SOURCE-SHA256SUMS
