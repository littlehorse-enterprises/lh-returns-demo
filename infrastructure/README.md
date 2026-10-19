# Runtime boundaries

`compose.yaml` uses the digest-pinned LittleHorse standalone 1.3.0 image and the published, digest-pinned Agent Connector 1.3.0 image. The business broker is Apache Kafka 4.0.0; Kafka Connect is Confluent 8.1.2 with the published shaded LittleHorse 1.3.0 plugin. Its SHA-256 comes from the GitHub release asset metadata. The Agent Connector calls Anthropic directly. `MODEL_API_KEY` supplies the Anthropic key and `MODEL_NAME` defaults to the end-to-end tested `claude-haiku-5-5`. Model calls and tool decisions are real; no local model or classifier is provisioned.

Run `./scripts/setup.sh`, then `./scripts/demo.sh normal` or `./scripts/smoke.sh`. Docker Desktop needs memory for LittleHorse's internal Kafka/server/dashboard, a business broker, Connect, the Java app/agent and outbound hosted model calls. Cold image downloads are separate from demo runtime. Model inference runs through Anthropic's direct API.

Setup ordering: start engine and wait for readiness → start business broker and wait → start Connect → create business topics → start app HTTP/MCP, workers and separate carrier → start actual agent → register LH workflow/events after agent TaskDef exists → install two sink connectors and require task RUNNING states. Config installation is repeatable. The REST endpoints for Connect and the demo are bound only to localhost; internal traffic uses service names. No host networking or host model service is required.

Topics:

- `customer-requests`: JSON workflow input variables, stable request ID as string Kafka key. The WfRun sink maps that key to the same WfRun ID; automatic offset-derived IDs are disabled.
- `fulfillment-events`: schemaless JSON boolean delivery confirmation, stable request ID as key. The correlated-event sink posts `replacement-delivered`; its definition retains the correlation to reject conflicting replays.
- Connect's three internal storage topics retain connector configuration, offsets and status in the business broker.

The low-level Kafka Connect REST endpoint is for this demo's diagnostics/configuration. Business actions remain Java workers; agent MCP access includes only the three read-only tools.

The small standalone wrapper preserves the published Kafka/server/dashboard executables. It reuses `cluster.id` from native `meta.properties`, formats through the native Kafka formatter, and waits for the broker API before starting the server/dashboard. This avoids a cold-start restart race in the upstream entrypoint, which uses a new random cluster ID when `quorum-state` is not yet present. It never creates fake Kafka state or removes data.

Persistence: the upstream standalone entrypoints use `/data/kafka` and `/data/lh`; a named volume mounts `/data` so the internal Kafka log and LittleHorse RocksDB state survive recreation. A separate named volume preserves the business broker. `/data` in the app preserves action receipts, cases and delivery outbox. These volumes belong to the selected Compose project, which defaults to `lh-returns-demo`.

`./scripts/cleanup.sh` stops only this project's services and preserves state. `./scripts/cleanup.sh down` removes only its containers/network, also preserving volumes. Neither deletes state. The recovery scenario kills only the app after its receipt is durable and before task acknowledgement, then starts it again. The separate carrier shares the application volume and can recover a pending outbox entry independently.

Host ports are configurable with `APP_PORT`, `LH_PORT`, `DASHBOARD_PORT`, `LH_HEALTH_PORT`, `CONNECT_PORT`. Default localhost ports: 8091, 2023, 8080, 18122 and 8083. `MODEL_NAME` selects a model with native tool-calling support. The tested configuration uses `MODEL_NAME=claude-haiku-5-5`. `scripts/check-model.py` checks the resolved configuration without printing the key; setup runs it before downloads or startup. `WAIT_TIMEOUT_SECONDS` defaults to300 for event waits; `AGENT_TIMEOUT_SECONDS` defaults to300 for inference; `SCENARIO_TIMEOUT_SECONDS` defaults to900 for the real hosted agent journey.

Setup starts the engine and business broker sequentially, checking real readiness
before continuing. Internal Kafka API probes allow 90 seconds within a 600-second
startup budget; a healthy native probe previously took more than 20 seconds.
A one-second forced-kill grace bounds a read-only CLI that ignores SIGTERM.
Invalid deadlines fail before formatting storage or launching the broker. The
last probe error is retained when startup fails; no data is removed.

Run `python3 scripts/test-startup.py` for isolated startup process tests. They
require GNU timeout (`timeout` on Linux or `gtimeout` on macOS) and exercise
readiness ordering, stubborn probes, an exited broker and invalid configuration.
These tests use temporary process fixtures and do not replace live runtime acceptance.

The key is supplied only to Agent Connector through the ignored `.env` or shell environment. Model HTTP request/response logging is explicitly disabled. No key is stored in source, the case UI or application state. Existing local-model volumes from earlier setups are left untouched; the current Compose stack no longer mounts or deletes them.

The outer standalone supervisor probes native readiness with bounded HTTP calls. After initial readiness, six consecutive failures cause an orderly runtime stop and nonzero exit so Compose can restart it. Initial readiness has a1200-second limit; graceful shutdown has a110-second limit. Probes normally run10seconds apart. This handles a JVM that remains alive after a Streams client failure; it does not diagnose or fix the initiating host/broker interruption. Named volumes are preserved.

Run `python3 scripts/test-supervisor.py` for isolated checks of persistent and transient readiness failures, startup bounds, child exit propagation and invalid configuration. These process fixtures are separate from live service acceptance.

Setup pulls the published agent image unless `--reuse-images` or `--reuse-agent` is selected. After changing model configuration, do not reuse the old agent.
