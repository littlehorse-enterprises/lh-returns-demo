#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
case "${1:-stop}" in
  stop) bounded_compose 180 stop; echo "Stopped only ${COMPOSE_PROJECT_NAME} services; all state and models retained." ;;
  down) bounded_compose 180 down; echo "Removed only ${COMPOSE_PROJECT_NAME} containers/network; all volumes retained." ;;
  *) echo 'Usage: scripts/cleanup.sh [stop|down]. This script never deletes volumes.' >&2; exit 2 ;;
esac
