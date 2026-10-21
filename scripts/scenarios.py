#!/usr/bin/env python3
"""Exercise the real HTTP -> Kafka Connect -> LittleHorse -> agent -> fulfillment journey.

No model or business-state fixture is substituted for the running stack. Each wait has a deadline.
The recovery case restarts only the demo app after its persisted failure marker is observable.
"""
import json
import datetime
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

os.environ.setdefault("DEMO_REUSE_AGENT", "1")
ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = f"http://127.0.0.1:{os.getenv('APP_PORT', '8091')}"
COMPOSE = ["docker", "compose", "--env-file", os.devnull, "--project-name", os.getenv("COMPOSE_PROJECT_NAME") or "lh-returns-demo", "--file", str(ROOT / "compose.yaml")]
TIMEOUT = int(os.getenv("SCENARIO_TIMEOUT_SECONDS", "900"))


def api(path, method="GET", body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            assert response.status == expected, f"HTTP {response.status}, expected {expected}"
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == expected:
            return json.loads(error.read())
        raise AssertionError(f"{method} {path}: HTTP {error.code}: {error.read().decode()}") from error


def wait_for(case_id, description, predicate, terminal_ok=False):
    end = time.monotonic() + TIMEOUT
    last = None
    while time.monotonic() < end:
        try:
            last = api(f"/api/cases/{case_id}")
        except (OSError, AssertionError):
            # A process restart briefly removes HTTP; disappearance isn't fabricated progress.
            time.sleep(2)
            continue
        if predicate(last):
            return last
        if not terminal_ok and last["status"] in {"ESCALATED", "REJECTED"} and description != "review rejection":
            raise AssertionError(f"Case ended before {description}: {json.dumps(last)}")
        time.sleep(2)
    raise AssertionError(f"Timed out waiting for {description}: {json.dumps(last)}")


def events(case):
    return [event["type"] for event in case.get("events", [])]


def assert_delivered(case):
    assert case["status"] == "DELIVERED", case
    assert case.get("lhAvailable") is True and case.get("lhStatus") == "COMPLETED", case
    assert case.get("decision") == "REPLACE", case
    assert case["replacement"]["effectCount"] == 1, case
    assert case["delivery"]["delivered"] is True, case
    assert "CARRIER_EVENT_PUBLISHED" in events(case), case
    assert "DELIVERED" in events(case), case
    # The HTTP/MCP layer records actual read invocations; an asserted prompt is insufficient proof.
    reads = case.get("toolReads", [])
    names = {read.get("name", read.get("tool")) if isinstance(read, dict) else read for read in reads}
    assert {"get_order", "get_policy", "get_fulfillment_options"} <= names, f"Actual three-tool read audit missing: {reads}"


def start(scenario):
    case = api("/api/requests", "POST", {"scenario": scenario}, expected=201)
    assert case["requestId"] == case["wfRunId"], case
    print(f"Started {scenario}: {case['requestId']}", flush=True)
    print(f"Dashboard: {case['dashboardUrl']}", flush=True)
    return case["requestId"]


def delivered(case_id):
    case = wait_for(case_id, "delivery and native workflow completion", lambda c: c["status"] == "DELIVERED" and c.get("lhStatus") == "COMPLETED")
    assert_delivered(case)
    print(f"PASS delivered {case_id}: native COMPLETED, one persisted replacement, real agent read all three tools", flush=True)
    return case


def normal():
    return delivered(start("normal"))


def information():
    created = api("/api/requests", "POST", {"scenario": "normal",
        "message": "What is the price of my Jedi Training Lightsaber order, and is it replacement eligible? I only need information."}, expected=201)
    case_id = created["requestId"]
    case = wait_for(case_id, "recorded customer answer and native completion",
                    lambda c: c["status"] == "INFORMATION_SENT" and c.get("lhStatus") == "COMPLETED")
    assert case.get("decision") == "SEND_INFO" and case.get("policy") == "INFO", case
    assert isinstance(case.get("customerAnswer"), str) and case["customerAnswer"].strip(), case
    assert len(case["customerAnswer"]) <= 4000 and "49" in case["customerAnswer"], case
    assert not case.get("replacement") and not case.get("carrierPublished"), case
    reads = {read.get("name", read.get("tool")) if isinstance(read, dict) else read
             for read in case.get("toolReads", [])}
    assert {"get_order", "get_policy", "get_fulfillment_options"} <= reads, case
    print(f"PASS information {case_id}: actual answer stored, no replacement, native COMPLETED", flush=True)
    return case


def replay_recorded(case):
    replay = api(f"/api/cases/{case['requestId']}/replay", "POST", {})
    targets = replay.get("targetOffsets", {})
    expected = {"customer-requests", "fulfillment-events"} if case.get("carrierPublished") else {"customer-requests"}
    assert set(targets) == expected, replay
    for topic in expected:
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            if connect_offsets("returns-" + topic).get(0, -1) >= targets[topic]:
                break
            time.sleep(2)
        else:
            raise AssertionError(f"Replay not committed by returns-{topic}: target={targets}")
    return api(f"/api/cases/{case['requestId']}")


def review(approved):
    case_id = start("review")
    pending = wait_for(case_id, "policy review gate", lambda c: c["status"] == "WAITING_REVIEW")
    assert pending.get("policy") == "REVIEW" and not pending.get("replacement"), pending
    assert pending.get("lhAvailable") is True and pending.get("lhStatus") == "RUNNING", pending
    replayed = replay_recorded(pending)
    assert replayed["status"] == "WAITING_REVIEW" and not replayed.get("replacement"), replayed
    api(f"/api/cases/{case_id}/review", "POST", {"approved": approved})
    if approved:
        result = delivered(case_id)
    else:
        result = wait_for(case_id, "review rejection", lambda c: c["status"] == "REJECTED" and c.get("lhStatus") == "COMPLETED")
        assert not result.get("replacement"), result
        print(f"PASS rejected {case_id}: no replacement effect, native COMPLETED", flush=True)
    api(f"/api/cases/{case_id}/review", "POST", {"approved": True}, expected=409)
    return result


def recovery():
    case_id = start("recovery")
    persisted = wait_for(case_id, "persisted effect followed by injected failure", lambda c: c.get("recoveryFailureInjected") is True)
    assert persisted["replacement"]["effectCount"] == 1 and "RECOVERY_FAILURE" in events(persisted), persisted
    assert "REPLACEMENT_REUSED" not in events(persisted), "Retry already occurred; crash window was missed"
    print("Killing the demo worker AFTER persistence and BEFORE acknowledgement; carrier stays independent.", flush=True)
    try:
        subprocess.run(COMPOSE + ["kill", "-s", "SIGKILL", "app"], check=True, timeout=45)
        marker = next(event for event in persisted["events"] if event["type"] == "RECOVERY_FAILURE")
        marked_at = datetime.datetime.fromisoformat(re.sub(r"(\.\d{6})\d+", r"\1", marker["at"]).replace("Z", "+00:00")).timestamp()
        assert time.time() - marked_at < int(os.getenv("RECOVERY_PAUSE_SECONDS", "20")), "Kill missed the pre-acknowledgement window"
    finally:
        subprocess.run(COMPOSE + ["start", "app"], check=True, timeout=90)
    subprocess.run([str(ROOT / "scripts" / "wait.sh"), "app", "300"], check=True, timeout=330)
    case = delivered(case_id)
    assert "REPLACEMENT_REUSED" in events(case), case
    print(f"PASS recovery {case_id}: failure marker and receipt survived restart; retry reused action", flush=True)
    return case


def connect_offsets(connector):
    url = f"http://127.0.0.1:{os.getenv('CONNECT_PORT', '8083')}/connectors/{connector}/offsets"
    with urllib.request.urlopen(url, timeout=15) as response:
        return {entry["partition"]["kafka_partition"]: entry["offset"]["kafka_offset"]
                for entry in json.load(response)["offsets"]}


def duplicates(case=None):
    case = case or normal()
    case_id = case["requestId"]
    resubmitted = api("/api/requests", "POST", {"requestId": case_id,
                     "scenario": case["scenario"], "message": case["message"]}, expected=201)
    assert resubmitted["requestId"] == case_id, resubmitted
    assert_delivered(resubmitted)
    connectors = ["returns-customer-requests", "returns-fulfillment-events"]
    replay = api(f"/api/cases/{case_id}/replay", "POST", {})
    targets = replay.get("targetOffsets", {})
    assert set(targets) == {"customer-requests", "fulfillment-events"}, replay
    for name in connectors:
        deadline = time.monotonic() + 150
        last = None
        while time.monotonic() < deadline:
            last = connect_offsets(name)
            if last.get(0, -1) >= targets[name[len("returns-"):]]:
                break
            time.sleep(2)
        else:
            raise AssertionError(f"Replay not committed by {name}: target={targets}, after={last}")
    current = api(f"/api/cases/{case_id}")
    assert_delivered(current)
    assert events(current).count("REPLACEMENT_CREATED") == 1, current
    assert events(current).count("DELIVERED") == 1, current
    print(f"PASS duplicates {case_id}: both sink offsets advanced, same run and one effect", flush=True)
    return current


def carrier_restart():
    case_id = start("carrier-restart")
    pending = wait_for(case_id, "pending carrier outbox", lambda c: bool(c.get("replacement")))
    assert not pending.get("carrierPublished") and not pending.get("delivery"), pending
    try:
        subprocess.run(COMPOSE + ["stop", "carrier"], check=True, timeout=60)
        stopped = api(f"/api/cases/{case_id}")
        assert not stopped.get("carrierPublished") and not stopped.get("delivery"), "Carrier completed before the restart window"
        assert stopped["replacement"] == pending["replacement"], stopped
        replayed = replay_recorded(stopped)
        assert replayed["status"] == "WAITING_DELIVERY" and not replayed.get("delivery"), replayed
        assert replayed["replacement"] == pending["replacement"], replayed
    finally:
        subprocess.run(COMPOSE + ["start", "carrier"], check=True, timeout=90)
    case = delivered(case_id)
    print(f"PASS carrier restart {case_id}: pending outbox survived process restart", flush=True)
    return case


def register(wait_timeout):
    subprocess.run(COMPOSE + ["--profile", "tools", "run", "--rm", "--no-deps",
                   "-e", f"WAIT_TIMEOUT_SECONDS={wait_timeout}",
                   "-e", "AGENT_TIMEOUT_SECONDS=300", "register"], check=True, timeout=150)


def timeouts():
    result = []
    try:
        register(12)
        case_id = start("review")
        wait_for(case_id, "pending review", lambda c: c["status"] == "WAITING_REVIEW")
        review_case = wait_for(case_id, "review timeout", lambda c: c["status"] == "ESCALATED"
                              and c.get("lhStatus") in {"ERROR", "EXCEPTION"}, terminal_ok=True)
        assert not review_case.get("replacement"), review_case
        result.append(review_case)
        subprocess.run(COMPOSE + ["stop", "carrier"], check=True, timeout=60)
        case_id = start("normal")
        carrier_case = wait_for(case_id, "delivery timeout", lambda c: c["status"] == "ESCALATED"
                               and c.get("lhStatus") in {"ERROR", "EXCEPTION"}, terminal_ok=True)
        assert carrier_case["replacement"]["effectCount"] == 1 and not carrier_case.get("delivery"), carrier_case
        result.append(carrier_case)
        print("PASS review and carrier deadlines: native failed runs, no unauthorized or repeated effect", flush=True)
    finally:
        try:
            subprocess.run(COMPOSE + ["start", "carrier"], check=True, timeout=90)
        finally:
            register(int(os.getenv("WAIT_TIMEOUT_SECONDS", "300")))
    late = wait_for(carrier_case["requestId"], "late carrier follow-up",
                    lambda c: bool(c.get("lateDelivery")), terminal_ok=True)
    assert late["status"] == "ESCALATED" and late.get("followUpRequired") is True, late
    assert late.get("lhStatus") in {"ERROR", "EXCEPTION"} and not late.get("delivery"), late
    assert late["replacement"] == carrier_case["replacement"], late
    result[-1] = late
    print("PASS late delivery: carrier fact recorded, failed native outcome retained, one receipt", flush=True)
    return result


def main():
    scenario = sys.argv[1] if len(sys.argv) > 1 else "all"
    assert api("/api/health")["status"] == "ready"
    result = []
    if scenario == "all":
        first = normal()
        result = [first, information(), review(True), review(False), recovery(), duplicates(first), carrier_restart(), *timeouts()]
    elif scenario == "normal": result = [normal()]
    elif scenario == "information": result = [information()]
    elif scenario == "review": result = [review(True)]
    elif scenario == "rejection": result = [review(False)]
    elif scenario == "recovery": result = [recovery()]
    elif scenario == "duplicates": result = [duplicates()]
    elif scenario == "carrier-restart": result = [carrier_restart()]
    elif scenario == "timeouts": result = timeouts()
    else: raise ValueError("Choose all, normal, information, review, rejection, recovery, duplicates, carrier-restart or timeouts")
    print(json.dumps({"result": "PASS", "cases": [{"requestId": case["requestId"], "status": case["status"], "lhStatus": case.get("lhStatus")} for case in result]}, indent=2))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
