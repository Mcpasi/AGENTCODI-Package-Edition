#!/usr/bin/env bash
# Standalone Linux x86_64 -> Android ARM64 build; no Docker or target execution.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
exec python3 -B "$SCRIPT_DIR/local-build/build.py" "$@"
