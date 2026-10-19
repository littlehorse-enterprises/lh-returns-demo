#!/usr/bin/env bash
# Keep the published standalone runtime, with durable Kafka identity and a bounded broker barrier.
set -euo pipefail
export LHS_STATE_DIR="${LHS_STATE_DIR:-/data/lh}"
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms256m -Xmx512m}"
data_dir="${KAFKA_LOG_DIR:-/data/kafka}"
ready_timeout="${INTERNAL_KAFKA_READY_TIMEOUT_SECONDS:-600}"
probe_timeout="${INTERNAL_KAFKA_PROBE_TIMEOUT_SECONDS:-90}"
for duration in "$ready_timeout" "$probe_timeout"; do
  [[ "$duration" =~ ^[1-9][0-9]*$ ]] || { echo 'Kafka readiness deadlines must be positive seconds.' >&2; exit 1; }
done
mkdir -p "$data_dir" "$LHS_STATE_DIR"
config=/tmp/returns-internal-kafka.properties
cat > "$config" <<CONFIG
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@localhost:9093
listeners=INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
advertised.listeners=INTERNAL://localhost:9092
inter.broker.listener.name=INTERNAL
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT
log.dirs=$data_dir
default.replication.factor=1
min.insync.replicas=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
CONFIG
if [[ -f "$data_dir/meta.properties" ]]; then
  cluster_id="$(sed -n 's/^cluster.id=//p' "$data_dir/meta.properties" | head -n 1)"
  [[ -n "$cluster_id" ]] || { echo 'Existing Kafka metadata has no cluster.id; preserving it and stopping.' >&2; exit 1; }
  echo 'Reusing persisted internal Kafka cluster identity.'
else
  cluster_id=MkU3OEVBNTcwNTJENDM2Qk
fi
# Native formatter validates persisted identity and leaves already formatted storage intact.
/kafka/bin/kafka-storage.sh format --ignore-formatted --config "$config" --cluster-id "$cluster_id"
children=()
cleanup() {
  # Give the engine a chance to close its streams while its broker is still available.
  if [[ -n "${lh_pid:-}" ]]; then
    kill -TERM "$lh_pid" 2>/dev/null || true
    for _ in {1..15}; do kill -0 "$lh_pid" 2>/dev/null || break; sleep 1; done
  fi
  for ((index=${#children[@]}-1; index>=0; index--)); do kill -TERM "${children[$index]}" 2>/dev/null || true; done
  wait || true
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
/kafka/bin/kafka-server-start.sh "$config" &
kafka_pid=$!
children+=("$kafka_pid")
probe_log=/tmp/returns-kafka-readiness.log
deadline=$((SECONDS + ready_timeout))
while true; do
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    echo 'Internal Kafka API readiness deadline exceeded; data preserved.' >&2
    tail -15 "$probe_log" >&2 || true
    exit 1
  fi
  limit=$probe_timeout
  (( limit <= remaining )) || limit=$remaining
  started=$SECONDS
  # Force-stop only the read-only CLI if its JVM ignores TERM; never the broker.
  if timeout --kill-after=1s "$limit" /kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >"$probe_log" 2>&1; then
    echo "Internal Kafka API probe succeeded in $((SECONDS - started))s."
    break
  fi
  echo "Internal Kafka API probe not ready after $((SECONDS - started))s; retrying within overall deadline."
  kill -0 "$kafka_pid" 2>/dev/null || { tail -15 "$probe_log" >&2; echo 'Internal Kafka exited before API readiness.' >&2; exit 1; }
  sleep 2
done
echo 'Internal Kafka API ready; starting published dashboard and LittleHorse server.'
/lh/dashboard-entrypoint.sh &
children+=("$!")
/lh/littlehorse-entrypoint.sh &
lh_pid=$!
children+=("$lh_pid")
wait "$lh_pid"
