#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
python3 "$DEMO_ROOT/scripts/wait.py" "$@"
