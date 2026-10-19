#!/usr/bin/env python3
"""Resolve only synthetic temporary Compose config; never contact a Docker daemon."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ([shutil.which('docker-compose')] if shutil.which('docker-compose')
           else [shutil.which('docker') or 'docker', 'compose'])


class ComposeImageIsolationTest(unittest.TestCase):
    def resolve(self, values=None, project=None):
        with tempfile.TemporaryDirectory(prefix='returns-compose-images-') as temp:
            root = Path(temp)
            shutil.copy2(ROOT/'compose.yaml', root/'compose.yaml')
            (root/'fixture.env').write_text('MODEL_API_KEY=fixture-only-not-a-secret\n'
                                           'MODEL_NAME=claude-fixture\n')
            (root/'docker-config').mkdir()
            # No inherited provider values, user Docker configuration, or daemon access.
            env = {'PATH': os.environ['PATH'], 'DOCKER_CONFIG': str(root/'docker-config'),
                   'DOCKER_HOST': 'unix://' + str(root/'no-daemon.sock'), **(values or {})}
            command = [*COMPOSE, '--env-file', str(root/'fixture.env'), '--file', str(root/'compose.yaml')]
            if project:
                command += ['--project-name', project]
            command += ['--profile', 'tools', 'config', '--format', 'json']
            result = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, timeout=30)
            # Never print a resolved config or subprocess output, even on failure.
            self.assertEqual(result.returncode, 0, 'Synthetic Compose configuration failed')
            config = json.loads(result.stdout)
            self.assertEqual(config['services']['agent']['environment']['AGENT_CHAT_MODEL_ANTHROPIC_API_KEY'],
                             'fixture-only-not-a-secret')
            return config

    def assert_images(self, config, app, connect):
        for service in ('app', 'carrier', 'register'):
            self.assertEqual(config['services'][service]['image'], app, service)
        self.assertEqual(config['services']['connect']['image'], connect)
        self.assertIn('build', config['services']['app'])
        self.assertIn('build', config['services']['connect'])

    def test_unset_and_empty_image_variables_preserve_defaults(self):
        for values in ({}, {'DEMO_APP_IMAGE': '', 'DEMO_CONNECT_IMAGE': ''}):
            with self.subTest(values=values):
                config = self.resolve(values)
                self.assert_images(config, 'lh-returns-demo-app:local', 'lh-returns-demo-connect:1.3.0')
                self.assertEqual(config['name'], 'lh-returns-demo')

    def test_custom_tags_cover_builds_carrier_and_registration_with_project_isolation(self):
        defaults = self.resolve()
        config = self.resolve({'DEMO_APP_IMAGE': 'returns-fixture-app:isolated',
                               'DEMO_CONNECT_IMAGE': 'returns-fixture-connect:isolated',
                               'APP_PORT': '8092', 'DASHBOARD_PORT': '8081', 'LH_PORT': '2025',
                               'LH_HEALTH_PORT': '18123', 'CONNECT_PORT': '8084'},
                              project='returns-image-fixture')
        self.assert_images(config, 'returns-fixture-app:isolated', 'returns-fixture-connect:isolated')
        for service in ('agent', 'littlehorse', 'kafka'):
            self.assertEqual(config['services'][service]['image'], defaults['services'][service]['image'])
        self.assertEqual(config['name'], 'returns-image-fixture')
        self.assertEqual(config['networks']['default']['name'], 'returns-image-fixture_default')
        for volume in ('littlehorse-data', 'business-kafka-data', 'app-data'):
            self.assertEqual(config['volumes'][volume]['name'], 'returns-image-fixture_' + volume)
        expected = {'app': {8091: '8092'}, 'connect': {8083: '8084'},
                    'littlehorse': {2023: '2025', 8080: '8081', 1822: '18123'}}
        for service, ports in expected.items():
            actual = config['services'][service]['ports']
            self.assertTrue(all(p['host_ip'] == '127.0.0.1' for p in actual))
            self.assertEqual({p['target']: str(p['published']) for p in actual}, ports)
        for service in ('app', 'carrier', 'register'):
            self.assertTrue(any(v['type'] == 'volume' and v['source'] == 'app-data'
                                for v in config['services'][service]['volumes']))

    def test_published_agent_preserves_tool_and_secret_logging_boundaries(self):
        config = self.resolve()
        service = config['services']['agent']
        agent = service['environment']
        self.assertEqual(agent['AGENT_CHAT_MODEL_PROVIDER'], 'anthropic')
        self.assertEqual(agent['AGENT_CHAT_MODEL_ANTHROPIC_LOG_REQUESTS'], 'false')
        self.assertEqual(agent['AGENT_CHAT_MODEL_ANTHROPIC_LOG_RESPONSES'], 'false')
        self.assertEqual(agent['AGENT_MCP_CLIENTS_RETURNS_TOOLS_NAMES'],
                         'get_order,get_policy,get_fulfillment_options')
        self.assertEqual(service['image'],
                         'ghcr.io/littlehorse-enterprises/lh-agent-connector:1.3.0@sha256:5d624810dd0e119a3571a663c340614befec735893786f302495978b6b17e82f')
        self.assertNotIn('build', service)

    def test_each_override_is_independent(self):
        self.assert_images(self.resolve({'DEMO_APP_IMAGE': 'returns-fixture-app:independent'}),
                           'returns-fixture-app:independent', 'lh-returns-demo-connect:1.3.0')
        self.assert_images(self.resolve({'DEMO_CONNECT_IMAGE': 'returns-fixture-connect:independent'}),
                           'lh-returns-demo-app:local', 'returns-fixture-connect:independent')


if __name__ == '__main__':
    unittest.main(verbosity=2)
