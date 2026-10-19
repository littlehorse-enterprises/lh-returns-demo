#!/usr/bin/env python3
"""Install only this demo's two sinks, then require RUNNING connector AND task status."""
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parent.parent
base = f"http://127.0.0.1:{os.getenv('CONNECT_PORT', '8083')}"

def call(path, method="GET", value=None):
    data = None if value is None else json.dumps(value).encode()
    request = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        payload = response.read()
        return json.loads(payload) if payload else None

for name in ["customer-requests", "fulfillment-events"]:
    config = json.loads((root / "infrastructure" / "connectors" / f"{name}.json").read_text())
    connector = f"returns-{name}"
    call(f"/connectors/{connector}/config", "PUT", config)
    deadline = time.monotonic() + 180
    last = None
    restarted_at = None
    while time.monotonic() < deadline:
        try:
            last = call(f"/connectors/{connector}/status")
        except urllib.error.HTTPError as error:
            # Distributed Connect can accept the config before status is published.
            if error.code != 404:
                raise
            last = {"http": 404, "state": "awaiting worker assignment"}
            time.sleep(2)
            continue
        tasks = last.get("tasks", [])
        if last["connector"]["state"] == "RUNNING" and tasks and all(task["state"] == "RUNNING" for task in tasks):
            print(f"{connector}: connector and tasks RUNNING")
            break
        if any(task["state"] == "FAILED" for task in tasks) or last["connector"]["state"] == "FAILED":
            if "--recover-failed" in sys.argv and restarted_at is None:
                # Configuration PUT alone does not restart a task left FAILED by an engine outage.
                call(f"/connectors/{connector}/restart?includeTasks=true&onlyFailed=true", "POST")
                restarted_at = time.monotonic()
                print(f"{connector}: requested one failed-task restart after metadata registration")
                time.sleep(2)
                continue
            if restarted_at is not None and time.monotonic() - restarted_at < 15:
                time.sleep(2)
                continue
            print(json.dumps(last, indent=2), file=sys.stderr)
            sys.exit(f"{connector} failed; metadata must be registered before sinks start.")
        time.sleep(2)
    else:
        sys.exit(f"{connector} readiness timed out: {last}")
