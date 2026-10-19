#!/usr/bin/env bash
set -euo pipefail
reuse_images=0
for option in "$@"; do
  case "$option" in
    --reuse-agent) export DEMO_REUSE_AGENT=1 ;;
    --reuse-images) reuse_images=1 ;;
    *) echo "Usage: $0 [--reuse-agent] [--reuse-images]" >&2; exit 2 ;;
  esac
done
source "$(dirname "$0")/common.sh"
require_tools
compose config --quiet
if [[ "${DEMO_REUSE_AGENT:-0}" == 1 ]]; then
  # Reuse only an actually healthy configured agent; do not read or replace its credential.
  "$DEMO_ROOT/scripts/wait.sh" agent 30
else
  python3 "$DEMO_ROOT/scripts/check-model.py"
fi
# Pull published runtimes and build the Java app and Kafka connector.
if [[ "$reuse_images" == 0 ]]; then
  bounded_compose 1800 pull littlehorse kafka
  bounded_compose 1800 build app connect
  if [[ "${DEMO_REUSE_AGENT:-0}" != 1 ]]; then
    bounded_compose 1800 pull agent
  fi
fi
# Keep two JVM-heavy broker cold starts from competing on a shared development host.
bounded_compose 900 up -d littlehorse
"$DEMO_ROOT/scripts/wait.sh" littlehorse 600
bounded_compose 900 up -d kafka
"$DEMO_ROOT/scripts/wait.sh" kafka 300
bounded_compose 900 up -d connect
"$DEMO_ROOT/scripts/wait.sh" connect 600
# Topics predate any producer or sink. Connect's internal topics are provisioned by the worker.
for topic in customer-requests fulfillment-events; do
  bounded_compose 180 exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
    --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 1
done
# HTTP/MCP and business workers must be alive before the agent connects to the tool catalog.
bounded_compose 600 up -d app carrier
"$DEMO_ROOT/scripts/wait.sh" app 300
if [[ "${DEMO_REUSE_AGENT:-0}" != 1 ]]; then
  bounded_compose 600 up -d agent
fi
"$DEMO_ROOT/scripts/wait.sh" agent 300
# Register AFTER the actual agent has registered its TaskDef, BEFORE installing ingress sinks.
python3 - "$DEMO_ROOT" <<'PY'
import os, pathlib, subprocess, sys, time
root = pathlib.Path(sys.argv[1])
env_args = ["--env-file", os.devnull] if os.getenv("DEMO_REUSE_AGENT") == "1" else []
command = ["docker", "compose", *env_args, "--project-name", os.getenv("COMPOSE_PROJECT_NAME") or "lh-returns-demo", "--file", str(root / "compose.yaml"), "--profile", "tools", "run", "--rm", "--no-deps", "register"]
deadline = time.monotonic() + 300
last = ""
while time.monotonic() < deadline:
    try:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
        last = result.stdout
        if result.returncode == 0:
            print(last.strip())
            break
    except subprocess.TimeoutExpired as error:
        last = str(error)
    time.sleep(3)
else:
    print(last[-6000:], file=sys.stderr)
    sys.exit("Workflow registration did not succeed; inspect agent and app logs. Sinks were not installed.")
PY
python3 "$DEMO_ROOT/scripts/install-connectors.py" --recover-failed
echo "Customer storefront: http://localhost:${APP_PORT:-8091}"
echo "Workflow Dashboard: http://localhost:${DASHBOARD_PORT:-8080}"
echo "Trigger a scenario with ./scripts/demo.sh normal (or review/recovery)."
echo "State retained in this project's volumes. Run ./scripts/smoke.sh to verify real flows."
