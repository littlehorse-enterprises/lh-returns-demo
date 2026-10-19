#!/usr/bin/env bash
set -euo pipefail
DEMO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DEMO_ROOT"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-lh-returns-demo}"
export COMPOSE_PARALLEL_LIMIT=1
COMPOSE_ARGS=(--project-name "$COMPOSE_PROJECT_NAME" --file "$DEMO_ROOT/compose.yaml")
if [[ "${DEMO_REUSE_AGENT:-0}" == 1 ]]; then COMPOSE_ARGS=(--env-file /dev/null "${COMPOSE_ARGS[@]}"); fi
compose() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }
bounded_compose() {
  local duration="$1"; shift
  python3 "$DEMO_ROOT/scripts/run-bounded.py" "$duration" docker compose "${COMPOSE_ARGS[@]}" "$@"
}
require_tools() {
  command -v docker >/dev/null || { echo 'Docker with Compose v2 is required.' >&2; exit 1; }
  command -v python3 >/dev/null || { echo 'Python 3 is required for bounded readiness and scenario checks.' >&2; exit 1; }
  docker compose version >/dev/null
  python3 "$DEMO_ROOT/scripts/run-bounded.py" 30 docker info --format '{{.ServerVersion}}' >/dev/null
}
