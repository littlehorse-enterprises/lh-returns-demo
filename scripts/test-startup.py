#!/usr/bin/env python3
"""Exercise the real standalone shell wrapper with isolated process fixtures.

Requires GNU timeout (timeout on Linux, gtimeout on macOS). No Docker, model,
network, broker data, or real service is used. Absolute image paths are mapped
into a temporary fixture root; the wrapper's control flow is unchanged.
"""
import os
import pathlib
import re
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

REPO = pathlib.Path(os.environ.get('STARTUP_TEST_REPO', pathlib.Path(__file__).resolve().parent.parent))
TIMEOUT = shutil.which('gtimeout') or shutil.which('timeout')


@unittest.skipUnless(TIMEOUT, 'GNU timeout is required')
class StandaloneStartupTest(unittest.TestCase):
    def run_wrapper(self, mode, ready='30', probe='20', outer=45):
        with tempfile.TemporaryDirectory(prefix='returns-startup-') as directory:
            root = pathlib.Path(directory)
            for sub in ['kafka/bin', 'lh', 'bin', 'tmp']:
                (root / sub).mkdir(parents=True)
            def script(name, body):
                path = root / name
                path.write_text('#!/bin/bash\n' + body + '\n')
                path.chmod(0o755)
            os.symlink(TIMEOUT, root / 'bin/timeout')
            script('kafka/bin/kafka-storage.sh', 'exit 0')
            script('kafka/bin/kafka-server-start.sh', '''echo "$$" >> "$TEST_ROOT/pids"
if [[ "$PROBE_MODE" == dead ]]; then exit 3; fi
trap 'exit 0' TERM
while true; do sleep 0.2; done''')
            script('kafka/bin/kafka-topics.sh', '''trap '' TERM
echo "$$ $PPID" >> "$TEST_ROOT/probe-pids"
if [[ "$PROBE_MODE" == hang ]]; then
  echo 'fixture probe ignores TERM' >&2
  while true; do sleep 0.2; done
elif [[ "$PROBE_MODE" == success ]]; then
  echo API_READY >> "$TEST_ROOT/events"
  exit 0
else
  echo 'fixture broker API unavailable' >&2
  sleep 0.2
  exit 1
fi''')
            script('lh/dashboard-entrypoint.sh', 'echo DASHBOARD >> "$TEST_ROOT/events"')
            script('lh/littlehorse-entrypoint.sh', 'echo ENGINE >> "$TEST_ROOT/events"; sleep 0.2')
            source = (REPO / 'infrastructure/standalone-entrypoint.sh').read_text()
            paths = {'/kafka/': str(root / 'kafka') + '/',
                     '/lh/': str(root / 'lh') + '/',
                     '/tmp/returns-': str(root / 'tmp/returns-')}
            # Rewrite original image paths once; a Linux fixture root itself contains /tmp/returns-.
            source = re.sub(r'/kafka/|/lh/|/tmp/returns-', lambda match: paths[match[0]], source)
            (root / 'entrypoint.sh').write_text(source)
            env = {'PATH': str(root / 'bin') + ':' + os.environ['PATH'],
                   'TEST_ROOT': str(root), 'PROBE_MODE': mode,
                   'LHS_STATE_DIR': str(root / 'state'), 'KAFKA_LOG_DIR': str(root / 'data'),
                   'INTERNAL_KAFKA_READY_TIMEOUT_SECONDS': ready,
                   'INTERNAL_KAFKA_PROBE_TIMEOUT_SECONDS': probe}
            start = time.monotonic()
            process = subprocess.Popen(['bash', str(root / 'entrypoint.sh')], env=env,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, start_new_session=True)
            expired = False
            try:
                output, _ = process.communicate(timeout=outer)
            except subprocess.TimeoutExpired:
                expired = True
                os.killpg(process.pid, signal.SIGKILL)
                output, _ = process.communicate(timeout=2)
            finally:
                # Each PID was written by this test's own temporary scripts only.
                probe_pids = root / 'probe-pids'
                if probe_pids.exists():
                    for line in probe_pids.read_text().splitlines():
                        pid, parent = map(int, line.split())
                        try:
                            if os.getpgid(pid) == parent:
                                os.killpg(parent, signal.SIGKILL)
                            else:
                                os.kill(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            elapsed = time.monotonic() - start
            events = (root / 'events').read_text().splitlines() if (root / 'events').exists() else []
            self.assertFalse(expired, 'wrapper exceeded outer deadline: ' + output[-800:])
            return process.returncode, output, events, elapsed

    def test_success_starts_services_only_after_api(self):
        code, output, events, _ = self.run_wrapper('success')
        self.assertEqual(code, 0, output)
        self.assertEqual(events[0], 'API_READY')
        self.assertIn('ENGINE', events)
        self.assertIn('DASHBOARD', events)

    def test_probe_ignoring_term_cannot_hold_startup_forever(self):
        # Keep the strict deadline regression independent of the generous
        # process-start allowance used by the ordering and broker-exit cases.
        code, output, events, elapsed = self.run_wrapper('hang', ready='6', probe='5', outer=14)
        self.assertNotEqual(code, 0)
        self.assertIn('readiness deadline exceeded', output)
        self.assertIn('fixture probe ignores TERM', output)
        self.assertEqual(events, [])
        self.assertLess(elapsed, 13.5)

    def test_exited_broker_never_starts_engine(self):
        code, output, events, _ = self.run_wrapper('dead')
        self.assertNotEqual(code, 0)
        self.assertIn('Internal Kafka exited before API readiness', output)
        self.assertIn('fixture broker API unavailable', output)
        self.assertEqual(events, [])

    def test_invalid_deadline_fails_safely(self):
        code, output, events, _ = self.run_wrapper('success', ready='0')
        self.assertNotEqual(code, 0)
        self.assertIn('deadlines must be positive seconds', output)
        self.assertEqual(events, [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
