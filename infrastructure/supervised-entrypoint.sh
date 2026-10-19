#!/usr/bin/env bash
# Local standalone supervision: a live JVM can still have a failed Streams client.
set -euo pipefail
entrypoint="${RUNTIME_ENTRYPOINT:-/lh/returns-entrypoint.sh}"
url="${ENGINE_READINESS_URL:-http://localhost:1822/readiness}"
interval="${ENGINE_HEALTH_INTERVAL_SECONDS:-10}"
startup="${ENGINE_READY_TIMEOUT_SECONDS:-1200}"
limit="${ENGINE_HEALTH_FAILURES:-6}"
shutdown="${ENGINE_SHUTDOWN_TIMEOUT_SECONDS:-110}"
for value in "$interval" "$startup" "$limit" "$shutdown"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo 'Engine supervisor bounds must be positive integers.' >&2; exit 2; }
done
cleanup() {
  trap - EXIT TERM INT
  if kill -0 "$runtime_pid" 2>/dev/null; then
    kill -TERM "$runtime_pid" 2>/dev/null || true
    end=$((SECONDS + shutdown))
    while kill -0 "$runtime_pid" 2>/dev/null && (( SECONDS < end )); do sleep 1; done
    kill -KILL "$runtime_pid" 2>/dev/null || true
  fi
  wait "$runtime_pid" 2>/dev/null || true
}
bash "$entrypoint" &
runtime_pid=$!
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
ready=0
failures=0
deadline=$((SECONDS + startup))
while kill -0 "$runtime_pid" 2>/dev/null; do
  if timeout --kill-after=1s 5 curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
    if (( ready == 0 )); then echo 'Engine supervisor: native readiness established.'; fi
    ready=1
    failures=0
  elif (( ready == 1 )); then
    failures=$((failures + 1))
    if (( failures >= limit )); then
      echo 'Engine supervisor: sustained native readiness loss; stopping runtime for retained-data restart.' >&2
      exit 1
    fi
  elif (( SECONDS >= deadline )); then
    echo 'Engine supervisor: initial readiness deadline exceeded; data preserved.' >&2
    exit 1
  fi
  sleep "$interval" &
  wait "$!" || true
done
wait "$runtime_pid"
