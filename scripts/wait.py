#!/usr/bin/env python3
"""Wait for Compose health checks. Every subprocess and the overall wait is bounded."""
import json
import os
import pathlib
import subprocess
import sys
import time

root = pathlib.Path(__file__).resolve().parent.parent
env_args = ["--env-file", os.devnull] if os.getenv("DEMO_REUSE_AGENT") == "1" else []
compose = ["docker", "compose", *env_args, "--project-name", os.getenv("COMPOSE_PROJECT_NAME") or "lh-returns-demo", "--file", str(root / "compose.yaml")]
service = sys.argv[1] if len(sys.argv) > 1 else "app"
timeout = int(sys.argv[2]) if len(sys.argv) > 2 else 600
end = time.monotonic() + timeout
last = "not created"
while time.monotonic() < end:
    try:
        ids = subprocess.check_output(compose + ["ps", "--all", "--quiet", service], text=True, timeout=15).split()
        if ids:
            state = json.loads(subprocess.check_output(
                ["docker", "inspect", "--format", "{{json .State}}", ids[0]], text=True, timeout=15))
            last = state.get("Health", {}).get("Status", state["Status"])
            if last == "healthy":
                print(f"{service}: healthy")
                sys.exit(0)
            if state["Status"] in {"exited", "dead"}:
                break
    except (subprocess.SubprocessError, json.JSONDecodeError) as error:
        last = str(error)
    time.sleep(2)
print(f"{service} did not become healthy in {timeout}s; last state: {last}", file=sys.stderr)
print(f"Inspect {service} readiness before retrying; container environment and logs are not dumped automatically.", file=sys.stderr)
sys.exit(1)
