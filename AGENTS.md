# Returns demo

This is a standalone Java demo of damaged-order resolution using LittleHorse and lh-agent-connector.

Use real Kafka ingress and fulfillment events, Java WfSpec/worker APIs and the actual lh-agent-connector for model/tool execution. The existing LittleHorse Dashboard is the visual interface for orchestration, agent tasks, variables, retries and completion. Do not build an admin-style case desk or duplicate that Dashboard. A shopper-facing ecommerce/mobile exchange experience is allowed as the customer side of the demo; connect it to real Kafka ingress and persisted results. Retain terminal triggers, demo-review callbacks and backend HTTP/MCP endpoints. Business fixtures and reviewer/carrier simulators must be labeled. Do not substitute fixtures for the real LLM and call them AI. Agent tools are read-only; workflow/worker code owns identity validation, amounts, policy and actions. Reject unsupported model proposals. Persistent state and idempotency must survive worker restart. Verify real end-to-end behavior before recording-ready claims.

Use published 1.3.0 artifacts where supported, contain agent Java25 in its prebuilt container, and use Java21 for the demo. Do not alter other repos or stop unrelated containers. Private/customer artifacts stay out of this generic repo. New instruction files use AGENTS.md. Keep secrets out of source/history/logs.

Keep internal schedules, planning notes and personal context outside the repository. Preserve unrelated changes when collaborating.
