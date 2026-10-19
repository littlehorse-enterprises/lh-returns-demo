#!/usr/bin/env python3
"""Fault-inject HTTP read failures into the live acceptance poller; no services used."""
import socket
import unittest
from unittest.mock import patch

import scenarios


class CasePollingTest(unittest.TestCase):
    def retry_read_error(self, error):
        case = {'status': 'DELIVERED', 'lhStatus': 'COMPLETED'}
        with patch.object(scenarios, 'api', side_effect=[error, case]) as api, \
                patch.object(scenarios.time, 'sleep') as sleep:
            result = scenarios.wait_for('fixture', 'delivery', lambda c: c == case)
        self.assertIs(result, case)
        self.assertEqual(api.call_count, 2)
        sleep.assert_called_once_with(2)

    def test_body_socket_timeout_is_retried(self):
        # On Python 3.9 this is an OSError, but not a built-in TimeoutError.
        self.retry_read_error(socket.timeout('fixture body read timed out'))

    def test_body_connection_reset_is_retried(self):
        self.retry_read_error(ConnectionResetError('fixture process restarted'))

    def test_repeated_io_failures_still_reach_deadline(self):
        with patch.object(scenarios, 'TIMEOUT', 1), \
                patch.object(scenarios.time, 'monotonic', side_effect=[0, 0, 2]), \
                patch.object(scenarios.time, 'sleep'), \
                patch.object(scenarios, 'api', side_effect=OSError('fixture offline')):
            with self.assertRaisesRegex(AssertionError, 'Timed out waiting'):
                scenarios.wait_for('fixture', 'delivery', lambda _: False)

    def test_invalid_json_is_not_hidden_as_restart(self):
        with patch.object(scenarios, 'api', side_effect=ValueError('fixture invalid JSON')):
            with self.assertRaisesRegex(ValueError, 'invalid JSON'):
                scenarios.wait_for('fixture', 'delivery', lambda _: False)

    def test_business_escalation_still_fails_immediately(self):
        with patch.object(scenarios, 'api', return_value={'status': 'ESCALATED'}):
            with self.assertRaisesRegex(AssertionError, 'Case ended before'):
                scenarios.wait_for('fixture', 'delivery', lambda _: False)


if __name__ == '__main__':
    unittest.main(verbosity=2)
