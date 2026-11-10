#!/usr/bin/env bash
# Build upstream in an isolated copy. Never write into the upstream snapshot.
set -Eeuo pipefail
PROJECT_ROOT="$(cd -- "$(dirname -- "$0")/../.." && pwd -P)"
python3 -B "$PROJECT_ROOT/scripts/arch-terminal/verify-source.py"
WORK_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/agentcodi-arch-build.XXXXXX")"
trap 'rm -rf -- "$WORK_DIR"' EXIT
cp -a "$PROJECT_ROOT/third_party/arch-terminal/." "$WORK_DIR/"
python3 -B "$PROJECT_ROOT/scripts/arch-terminal/fetch-sources.py" --destination "$WORK_DIR/.cache/sources"
bash "$WORK_DIR/scripts/build-native.sh"
bash "$WORK_DIR/scripts/source-bundle.sh"
OUTPUT="$PROJECT_ROOT/output/arch-terminal"
mkdir -p "$OUTPUT/native" "$OUTPUT/assets"
cp "$WORK_DIR/app/build/generated/jniLibs/arm64-v8a/"*.so "$OUTPUT/native/"
cp -a "$WORK_DIR/app/build/generated/assets/." "$OUTPUT/assets/"
cp "$WORK_DIR/output/"*.tar.gz "$OUTPUT/"
cp "$WORK_DIR/output/SOURCE-SHA256SUMS" "$OUTPUT/"
cp "$PROJECT_ROOT/third_party/arch-terminal.lock.json" "$OUTPUT/source-lock.json"
(
  cd "$OUTPUT"
  find native assets -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
