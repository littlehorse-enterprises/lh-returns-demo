#!/usr/bin/env python3
"""Run a command with an explicit wall-clock deadline, preserving output and exit status."""
import subprocess
import sys

try:
    result = subprocess.run(sys.argv[2:], timeout=int(sys.argv[1]), check=False)
except subprocess.TimeoutExpired:
    print(f"Command exceeded {sys.argv[1]} seconds: {' '.join(sys.argv[2:])}", file=sys.stderr)
    sys.exit(124)
sys.exit(result.returncode)
