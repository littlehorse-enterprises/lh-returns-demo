#!/usr/bin/env python3
"""Exercise real supervisor control flow with isolated child/health process fixtures."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
TIMEOUT = shutil.which('gtimeout') or shutil.which('timeout')

@unittest.skipUnless(TIMEOUT, 'GNU timeout is required')
class EngineSupervisorTest(unittest.TestCase):
    def run_fixture(self, mode, **settings):
        with tempfile.TemporaryDirectory(prefix='returns-supervisor-') as temp:
            root = pathlib.Path(temp)
            binary = root/'bin'; binary.mkdir()
            os.symlink(TIMEOUT, binary/'timeout')
            runtime = root/'runtime.sh'
            runtime.write_text('''#!/bin/bash
trap 'echo stopped > "$FIXTURE_ROOT/stopped"; exit 0' TERM
echo started > "$FIXTURE_ROOT/started"
if [[ "$FIXTURE_MODE" == child-exit ]]; then exit 7; fi
for _ in {1..20}; do sleep 0.2; done
''')
            curl = binary/'curl'
            curl.write_text('''#!/bin/bash
count=0; [[ ! -f "$FIXTURE_ROOT/count" ]] || read -r count < "$FIXTURE_ROOT/count"
count=$((count + 1)); echo "$count" > "$FIXTURE_ROOT/count"
if [[ "$FIXTURE_MODE" == never-ready ]]; then exit 1; fi
if [[ "$FIXTURE_MODE" == failed && "$count" -gt 1 ]]; then exit 1; fi
if [[ "$FIXTURE_MODE" == transient && "$count" == 2 ]]; then exit 1; fi
exit 0
'''); curl.chmod(0o755)
            env={**os.environ,'PATH':str(binary)+':'+os.environ['PATH'],
                 'RUNTIME_ENTRYPOINT':str(runtime),'FIXTURE_ROOT':str(root),'FIXTURE_MODE':mode,
                 'ENGINE_HEALTH_INTERVAL_SECONDS':'1','ENGINE_READY_TIMEOUT_SECONDS':'3',
                 'ENGINE_HEALTH_FAILURES':'2','ENGINE_SHUTDOWN_TIMEOUT_SECONDS':'2',**settings}
            result=subprocess.run(['bash',str(ROOT/'infrastructure/supervised-entrypoint.sh')],env=env,
                                  text=True,capture_output=True,timeout=15)
            return result, (root/'stopped').exists(), (root/'started').exists()

    def test_failed_readiness_stops_child_and_exits_for_restart(self):
        result,stopped,_=self.run_fixture('failed')
        self.assertEqual(result.returncode,1,result.stdout+result.stderr)
        self.assertTrue(stopped)
        self.assertIn('sustained native readiness loss',result.stderr)

    def test_initial_readiness_is_bounded(self):
        result,stopped,_=self.run_fixture('never-ready')
        self.assertEqual(result.returncode,1,result.stdout+result.stderr)
        self.assertTrue(stopped)
        self.assertIn('initial readiness deadline exceeded',result.stderr)

    def test_transient_readiness_failure_does_not_restart(self):
        result,stopped,_=self.run_fixture('transient')
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertFalse(stopped)

    def test_child_exit_status_is_preserved(self):
        result,_,_=self.run_fixture('child-exit')
        self.assertEqual(result.returncode,7,result.stdout+result.stderr)

    def test_invalid_bound_never_starts_runtime(self):
        result,_,started=self.run_fixture('failed',ENGINE_HEALTH_FAILURES='0')
        self.assertEqual(result.returncode,2)
        self.assertFalse(started)

if __name__=='__main__': unittest.main(verbosity=2)
