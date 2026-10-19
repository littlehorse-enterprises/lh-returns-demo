#!/usr/bin/env python3
"""Exercise project selection without real Docker, credentials, or services."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent


class ProjectIsolationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='returns-project-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.bin = self.base / 'bin'
        self.bin.mkdir()
        self.log = self.base / 'commands.jsonl'
        docker = self.bin / 'docker'
        docker.write_text('#!' + sys.executable + '''
import json,os,sys
args=sys.argv[1:]
with open(os.environ['FAKE_DOCKER_LOG'],'a') as f:f.write(json.dumps(args)+'\\n')
if args[0]=='inspect':print(json.dumps({'Status':'running','Health':{'Status':'healthy'}}))
elif args[0]=='compose' and 'ps' in args:print('fixture-owned-container')
elif args[0]=='info':print('fixture-engine')
''')
        docker.chmod(0o755)
        self.env = {'PATH': str(self.bin) + ':' + os.environ['PATH'],
                    'COMPOSE_PROJECT_NAME': 'returns-isolation-fixture',
                    'DEMO_REUSE_AGENT': '1', 'FAKE_DOCKER_LOG': str(self.log)}

    def run_script(self, path, *args):
        result = subprocess.run([str(path), *args], env=self.env,
                                text=True, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result

    def assert_selected(self, minimum=1):
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        selected = [c for c in calls if c[0]=='compose' and c != ['compose', 'version']]
        self.assertGreaterEqual(len(selected), minimum)
        for call in selected:
            self.assertIn('--project-name', call, call)
            self.assertEqual(call[call.index('--project-name')+1], 'returns-isolation-fixture', call)
        return calls

    def test_cleanup_and_readiness_target_selected_project(self):
        for action in ('stop', 'down'):
            result = self.run_script(ROOT/'scripts/cleanup.sh', action)
            self.assertIn('returns-isolation-fixture', result.stdout)
        self.run_script(ROOT/'scripts/wait.sh', 'app', '5')
        self.assert_selected(3)

    def test_default_and_empty_project_keep_default(self):
        for value in (None, ''):
            env = dict(self.env)
            if value is None:env.pop('COMPOSE_PROJECT_NAME')
            else:env['COMPOSE_PROJECT_NAME']=value
            result=subprocess.run(['bash','-c','source "$1"; printf "%s" "$COMPOSE_PROJECT_NAME"',
                                   'fixture',str(ROOT/'scripts/common.sh')],env=env,text=True,capture_output=True,timeout=5)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(result.stdout,'lh-returns-demo')

    def load(self, name):
        spec=importlib.util.spec_from_file_location('fixture_module',ROOT/'scripts'/name)
        module=importlib.util.module_from_spec(spec)
        with patch.dict(os.environ, self.env):spec.loader.exec_module(module)
        return module

    def test_scenario_registration_targets_selected_project(self):
        module=self.load('scenarios.py')
        with patch.object(module.subprocess,'run') as run:
            module.register(12)
        command=run.call_args.args[0]
        self.assertEqual(command[command.index('--project-name')+1],self.env['COMPOSE_PROJECT_NAME'])
        self.assertIn('WAIT_TIMEOUT_SECONDS=12',command)
        self.assertIn(os.devnull,command)

    def test_model_validation_targets_selected_project_with_synthetic_config(self):
        module=self.load('check-model.py')
        config={'services':{'agent':{'environment':{'AGENT_CHAT_MODEL_PROVIDER':'anthropic',
            'AGENT_CHAT_MODEL_ANTHROPIC_API_KEY':'fixture-only-token',
            'AGENT_CHAT_MODEL_ANTHROPIC_MODEL':'claude-fixture'}}}}
        with patch.dict(os.environ,self.env), patch.object(module.subprocess,'run',return_value=subprocess.CompletedProcess([],0,json.dumps(config))) as run:
            self.assertEqual(module.main(),0)
        command=run.call_args.args[0]
        self.assertEqual(command[command.index('--project-name')+1],self.env['COMPOSE_PROJECT_NAME'])

    def test_repeat_setup_never_switches_projects_for_registration(self):
        scratch=self.base/'checkout'; (scratch/'scripts').mkdir(parents=True)
        for name in ('setup.sh','common.sh','wait.sh','wait.py','run-bounded.py'):
            shutil.copy2(ROOT/'scripts'/name,scratch/'scripts'/name)
        # Connector setup has separate HTTP tests. This fixture isolates lifecycle selection.
        (scratch/'scripts/install-connectors.py').write_text("print('fixture connector check')\n")
        self.run_script(scratch/'scripts/setup.sh','--reuse-agent','--reuse-images')
        calls=self.assert_selected(10)
        self.assertTrue(any('register' in c and 'run' in c for c in calls))

    def test_setup_pulls_published_agent_unless_explicitly_reused(self):
        scratch=self.base/'build-checkout'; (scratch/'scripts').mkdir(parents=True)
        for name in ('setup.sh','common.sh','wait.sh','wait.py','run-bounded.py'):
            shutil.copy2(ROOT/'scripts'/name,scratch/'scripts'/name)
        for name in ('install-connectors.py', 'check-model.py'):
            (scratch/'scripts'/name).write_text("print('fixture configuration check')\n")
        self.env.pop('DEMO_REUSE_AGENT')
        self.run_script(scratch/'scripts/setup.sh')
        calls=self.assert_selected(10)
        self.assertTrue(any(c[-2:] == ['pull', 'agent'] for c in calls))
        self.assertFalse(any('build' in c and 'agent' in c for c in calls))
        self.log.unlink()
        self.run_script(scratch/'scripts/setup.sh', '--reuse-agent')
        calls=self.assert_selected(10)
        self.assertFalse(any('build' in c and 'agent' in c for c in calls))
        self.assertFalse(any('up' in c and 'agent' in c for c in calls))


if __name__=='__main__':unittest.main(verbosity=2)
