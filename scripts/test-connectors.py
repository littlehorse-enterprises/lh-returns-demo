#!/usr/bin/env python3
"""Check connector readiness against an isolated REST fixture, never a live broker."""
import json
import os
import pathlib
import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parent.parent


class ConnectorReadinessTest(unittest.TestCase):
    def run_installer(self, mode, recover=False):
        calls = []
        counts = {}
        restarted = set()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, status, body):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_PUT(self):
                calls.append(('PUT', self.path))
                self.rfile.read(int(self.headers['Content-Length']))
                self.reply(201, {'name': self.path.split('/')[2]})

            def do_POST(self):
                calls.append(('POST', self.path))
                self.rfile.read(int(self.headers.get('Content-Length', '0')))
                restarted.add(self.path.split('/')[2])
                self.send_response(202)
                self.send_header('Content-Length', '0')
                self.end_headers()

            def do_GET(self):
                calls.append(('GET', self.path))
                counts[self.path] = counts.get(self.path, 0) + 1
                first = counts[self.path] == 1
                if mode == 'delayed' and first:
                    self.reply(404, {'error_code': 404, 'message': 'Status not reported yet'})
                elif mode == 'server-error':
                    self.reply(500, {'error_code': 500})
                else:
                    tasks = [] if mode == 'empty' and first else [
                        {'id': 0, 'state': 'FAILED' if mode == 'failed' or (mode == 'recoverable' and self.path.split('/')[2] not in restarted) else 'RUNNING'}]
                    self.reply(200, {'connector': {'state': 'RUNNING'}, 'tasks': tasks})

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run(
                [sys.executable, str(ROOT / 'scripts/install-connectors.py'), *(['--recover-failed'] if recover else [])],
                env=dict(os.environ, CONNECT_PORT=str(server.server_port)),
                capture_output=True, text=True, timeout=45)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        return result, calls, counts

    def test_new_status_404_waits_for_both_real_task_states(self):
        result, calls, counts = self.run_installer('delayed')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len([c for c in calls if c[0] == 'PUT']), 2)
        self.assertEqual(list(counts.values()), [2, 2])
        self.assertEqual(result.stdout.count('connector and tasks RUNNING'), 2)

    def test_running_connector_without_tasks_is_not_ready(self):
        result, _, counts = self.run_installer('empty')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(list(counts.values()), [2, 2])

    def test_failed_task_stops_before_second_connector(self):
        result, calls, _ = self.run_installer('failed')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FAILED', result.stderr)
        self.assertEqual(len([c for c in calls if c[0] == 'PUT']), 1)

    def test_failed_tasks_can_be_recovered_once_after_registration(self):
        result, calls, _ = self.run_installer('recoverable', recover=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len([c for c in calls if c[0] == 'POST']), 2)
        self.assertTrue(all('includeTasks=true&onlyFailed=true' in path for method,path in calls if method == 'POST'))

    def test_persistent_failure_is_not_restarted_forever(self):
        result, calls, _ = self.run_installer('failed', recover=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len([c for c in calls if c[0] == 'POST']), 1)
        self.assertEqual(len([c for c in calls if c[0] == 'PUT']), 1)

    def test_status_500_is_not_hidden_as_assignment_delay(self):
        result, calls, _ = self.run_installer('server-error')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP Error 500', result.stderr)
        self.assertEqual(len([c for c in calls if c[0] == 'GET']), 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
