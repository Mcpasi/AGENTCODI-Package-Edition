#!/usr/bin/env bash
set -Eeuo pipefail
PROJECT_ROOT="$(cd -- "$(dirname -- "$0")/../.." && pwd -P)"
python3 -B "$PROJECT_ROOT/scripts/arch-terminal/verify-source.py"
WORK_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/agentcodi-arch-tests.XXXXXX")"
trap 'rm -rf -- "$WORK_DIR"' EXIT
cp -a "$PROJECT_ROOT/third_party/arch-terminal/." "$WORK_DIR/"
python3 -B "$PROJECT_ROOT/scripts/arch-terminal/fetch-sources.py" --destination "$WORK_DIR/.cache/sources"
case "${1:-}" in
  '') bash "$WORK_DIR/scripts/test.sh" ;;
  --rootfs) bash "$WORK_DIR/scripts/test-rootfs.sh" ;;
  *) echo "Usage: test-upstream.sh [--rootfs]" >&2; exit 2 ;;
esac
