#!/usr/bin/env python3
"""Validate hosted-model configuration without printing or storing credentials."""
import json
import os
import pathlib
import re
import subprocess
import sys


def validate(environment):
    provider = environment.get("AGENT_CHAT_MODEL_PROVIDER", "")
    if provider != "anthropic":
        raise ValueError("This example requires the Anthropic Agent Connector adapter.")
    prefix = f"AGENT_CHAT_MODEL_{provider.upper()}_"
    key = environment.get(prefix + "API_KEY", "") or ""
    if not key.strip() or key.strip().lower() in {"replace-me", "your-key", "your-api-key", "changeme"} or key.strip().startswith("<"):
        raise ValueError("Set MODEL_API_KEY to an Anthropic key in the ignored .env file or your environment. Start with .env.example.")
    model = environment.get(prefix + "MODEL", "") or ""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", model):
        raise ValueError("Set MODEL_NAME to a tool-calling model ID from the selected provider.")
    if not model.startswith("claude-"):
        raise ValueError("Anthropic requires a Claude MODEL_NAME; .env.example includes a preset.")
    if environment.get(prefix + "BASE_URL"):
        raise ValueError("This demo uses the provider's direct API; remove custom model endpoint settings.")
    return provider, model


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    command = ["docker", "compose", "--project-name", os.getenv("COMPOSE_PROJECT_NAME") or "lh-returns-demo", "--file", str(root / "compose.yaml"), "config", "--format", "json"]
    try:
        # Resolved config may contain a key. Keep it in memory; never echo it or subprocess errors.
        result = subprocess.run(command, capture_output=True, text=True, timeout=30, check=True)
        configuration = json.loads(result.stdout)
        provider, model = validate(configuration["services"]["agent"]["environment"])
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (subprocess.SubprocessError, KeyError):
        print("Could not validate model configuration. Check Docker Compose and .env syntax.", file=sys.stderr)
        return 1
    print(f"Hosted model configured: {provider} / {model}. Live model execution is checked by smoke.sh.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
