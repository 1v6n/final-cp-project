#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"
UV_CACHE_DIR="${UV_CACHE_DIR:-${REPO_ROOT}/.uv-cache}" \
  uv run python regex/InvariantsAnalyzer.py \
    --log logs/run.log \
    --inv regex/TInvariants.yaml
