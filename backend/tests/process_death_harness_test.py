"""Unit tests for the process-death harness's host-side parts. No emulator, device or network beyond loopback is used."""
import http.client
import importlib
import json
import pathlib
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
h = importlib.import_module('process_death_harness')


class SerialGuardTest(unittest.TestCase):
    def test_physical_device_serials_are_refused_before_any_adb_call(self):
        for serial in ('12531254AH101767', 'R58M1234567', 'emulator', 'emulator-', '192.168.1.5:5555', 'emulator-5556; rm', 'emulator-5556\n'):
            with self.assertRaises(SystemExit, msg=serial): h.Device(serial, '.')

    def test_emulator_serial_is_accepted(self):
        self.assertEqual(h.Device('emulator-5556', '.').s, 'emulator-5556')


class MissingFileTest(unittest.TestCase):
    def test_error_text_is_missing_but_real_binary_is_not(self):
        self.assertTrue(h.missing(b'cat: /data/user/0/com.example.pinpoint/files/queued_photos/' + b'a' * 36 + b'.jpg: No such file or directory\n'))
        self.assertTrue(h.missing(b'run-as: package not debuggable'))
        self.assertFalse(h.missing(b'\xff\xd8\xff\xe0' + bytes(5000)), 'a real photo must never look missing')
        self.assertFalse(h.missing(b'SQLite format 3\x00' + bytes(4096)))


class BackendFaultTest(unittest.TestCase):
    def setUp(self):
        self.b = h.Backend(); self.server = ThreadingHTTPServer(('127.0.0.1', 0), h.make_handler(self.b)); self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start(); self.port = self.server.server_address[1]
        self.token = self.signin('u@x.test')

    def tearDown(self): self.b.release.set(); self.server.shutdown()

    def req(self, method, path, body=b'', token=None, timeout=5):
        c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=timeout)
        try:
            c.request(method, path, body, {'Authorization': f'Bearer {token}'} if token else {}); r = c.getresponse(); return r.status, r.read()
        finally: c.close()

    def signin(self, email):
        s, data = self.req('POST', '/auth/v1/token?grant_type=password', json.dumps({'email': email}).encode()); self.assertEqual(s, 200)
        return json.loads(data)['access_token']

    def test_upload_is_immutable_and_a_duplicate_is_reported_with_both_hashes(self):
        s, _ = self.req('POST', '/storage/v1/object/report-images/u/r/photo.jpg', b'one', self.token); self.assertEqual(s, 200)
        s, body = self.req('POST', '/storage/v1/object/report-images/u/r/photo.jpg', b'two', self.token)
        self.assertEqual(s, 400); self.assertIn(b'409', body)
        self.assertEqual(self.b.objects['u/r/photo.jpg'], b'one'); dup = [e for e in self.b.events if e['kind'] == 'upload_duplicate'][0]
        self.assertNotEqual(dup['sha'], dup['stored'])

    def test_submit_report_is_idempotent_per_id_and_refuses_another_owner(self):
        rid = '11111111-1111-4111-8111-111111111111'; body = json.dumps({'p_id': rid, 'p_photo_path': None, 'p_description': 'x'}).encode()
        self.assertEqual(self.req('POST', '/rest/v1/rpc/submit_report', body, self.token)[0], 200)
        self.assertEqual(self.req('POST', '/rest/v1/rpc/submit_report', body, self.token)[0], 200)
        self.assertEqual([e['new'] for e in self.b.events if e['kind'] == 'rpc_committed'], [True, False], 'the retry must not create a second report')
        other = self.signin('v@x.test')
        self.assertEqual(self.req('POST', '/rest/v1/rpc/submit_report', body, other)[0], 403)

    def test_a_held_rpc_never_commits_until_released_and_commit_then_hold_commits_first(self):
        rid = '22222222-2222-4222-8222-222222222222'; body = json.dumps({'p_id': rid}).encode()
        self.b.rpc_plan.append('hold')
        t = threading.Thread(target=lambda: self.req('POST', '/rest/v1/rpc/submit_report', body, self.token, timeout=2), daemon=True); t.start()
        self.assertIsNotNone(self.b.wait_event('rpc_received', 3)); self.assertEqual(self.b.reports, {}, 'a held RPC must not have committed')
        self.b.release.set(); t.join(5)
        self.b.release.clear(); rid2 = '33333333-3333-4333-8333-333333333333'; self.b.rpc_plan.append('commit-then-hold')
        t2 = threading.Thread(target=lambda: self.req('POST', '/rest/v1/rpc/submit_report', json.dumps({'p_id': rid2}).encode(), self.token, timeout=2), daemon=True); t2.start()
        self.assertIsNotNone(self.b.wait_event('rpc_committed', 3)); self.assertIn(rid2, self.b.reports); self.b.release.set(); t2.join(5)

    def test_offline_mode_closes_the_connection_without_an_answer(self):
        self.b.offline = True
        with self.assertRaises((http.client.RemoteDisconnected, ConnectionError, OSError)): self.req('GET', '/rest/v1/reports', b'', self.token)
        self.assertTrue(any(e['kind'] == 'refused' for e in self.b.events))

    def test_unauthenticated_requests_are_rejected(self):
        self.assertEqual(self.req('POST', '/rest/v1/rpc/submit_report', b'{}')[0], 401)


if __name__ == '__main__':
    unittest.main()
