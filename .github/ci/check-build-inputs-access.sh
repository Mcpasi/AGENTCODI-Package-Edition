#!/usr/bin/env bash
# Fail before source/compiler builds if archived pinned inputs cannot be read.
set -euo pipefail
if [ -z "${GH_TOKEN:-}" ]; then
    echo 'AGENTCODI_INPUTS_TOKEN is required for archived pinned build inputs.' >&2
    exit 1
fi
if ! gh api repos/Mcpasi/agentcodi-build-inputs/releases/tags/0.7.6-preview.1 --silent; then
    echo 'AGENTCODI_INPUTS_TOKEN needs Contents: read access to Mcpasi/agentcodi-build-inputs.' >&2
    exit 1
fi
echo 'Private build-input mirror read access verified.'
