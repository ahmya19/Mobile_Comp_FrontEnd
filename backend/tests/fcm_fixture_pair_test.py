import contextlib
import importlib
import io
import json
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
probe = importlib.import_module('real_fcm_probe')
C = '11111111-1111-4111-8111-111111111111'
O = '33333333-3333-4333-8333-333333333333'
R = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000001'
I = '55555555-5555-4555-8555-555555555555'
CREDS = {'fixture': True, 'citizen': {'uid': C, 'email': 'c@example.test', 'password': 'pw-c'},
         'official': {'uid': O, 'email': 'o@example.test', 'password': 'pw-o'}}


class FixturePairProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.p = probe.Probe(types.SimpleNamespace(serial='emulator-5556', report_id=R, run_id='unit', work=self.temp.name), CREDS)
        self.p.installation = I; self.p.cfp = {'f': 'original', 'n': 2}

    def test_shared_demo_flag_and_same_account_pair_are_refused_before_io(self):
        for creds in ({**CREDS, 'fixture': False}, {**CREDS, 'official': CREDS['citizen']}):
            with self.assertRaises(ValueError): probe.Probe(self.p.a, creds)

    def test_admin_fixture_labels_and_distinct_roles_are_required(self):
        rows = [{'id': C, 'fixture': True, 'confirmed': True, 'area': None},
                {'id': O, 'fixture': True, 'confirmed': True, 'area': 'Alangilan'}]
        with patch.object(probe, 'sql', return_value=rows): self.p.validate_pair()
        for change in ({'fixture': False}, {'confirmed': False}, {'area': 'Other area'}):
            with patch.object(probe, 'sql', return_value=[rows[0], {**rows[1], **change}]):
                with self.assertRaises(SystemExit): self.p.validate_pair()

    def test_reporter_submits_and_actor_updates_with_separate_sessions(self):
        def signin(email, _): return ('citizen-token', C) if email.startswith('c@') else ('official-token', O)
        calls = []
        with patch.object(probe, 'sign_in', side_effect=signin), patch.object(probe, 'call', side_effect=lambda *a: (calls.append(a) or (200, {}))):
            self.p.rpc('submit_report', {'p_id': R}); self.p.rpc('update_report_status', {'p_report_id': R})
        self.assertEqual([c[2] for c in calls], ['citizen-token', 'official-token'])

    def test_recipient_reads_notices_and_guard_is_last_before_status_rpc(self):
        order = []
        self.p.reporter_session = lambda: 'citizen-token'
        self.p.session = lambda: order.append('actor-auth') or 'official-token'
        self.p.guard = lambda *a, **k: order.append('guard')
        self.p.rpc = lambda *a: order.append('mutation')
        self.p.new_notice = lambda _: {'id': 'notice'}
        calls = []
        with patch.object(probe, 'call', side_effect=lambda *a: (calls.append(a) or (200, []))): self.p.change('Under Review', 'test')
        self.assertEqual(calls[0][2], 'citizen-token')
        self.assertEqual(order[-2:], ['guard', 'mutation'])

    def test_report_owner_must_be_citizen_not_actor(self):
        with patch.object(probe, 'sql', return_value=[{'owner': O, 'description': self.p.marker, 'barangay': 'Alangilan'}]):
            with self.assertRaises(SystemExit): self.p.verify_report()

    def test_unlabelled_pair_cannot_reach_the_status_mutation(self):
        sent = []
        self.p.reporter_session = lambda: 'citizen-token'; self.p.session = lambda: 'actor-token'
        self.p.rpc = lambda *a: sent.append(a)
        rows = [{'id': C, 'fixture': False, 'confirmed': True, 'area': None},
                {'id': O, 'fixture': True, 'confirmed': True, 'area': 'Alangilan'}]
        with patch.object(probe, 'call', return_value=(200, [])), patch.object(probe, 'sql', return_value=rows):
            with self.assertRaises(SystemExit): self.p.change('Under Review', 'bad-label')
        self.assertEqual(sent, [])

    def guard_setup(self, recipient, actor=(), fingerprint=None):
        self.p.validate_pair = lambda: None; self.p.verify_report = lambda: None
        self.p.devices = lambda uid: [{'i': i} for i in (recipient if uid == C else actor)]
        self.p.protected_fp = lambda: fingerprint or self.p.cfp

    def test_normal_send_requires_exactly_one_owned_registration(self):
        for rows in ([], ['someone-else'], [I, 'someone-else']):
            self.guard_setup(rows)
            with self.assertRaises(SystemExit): self.p.guard('normal')
        self.guard_setup([I]); self.assertEqual(self.p.guard('normal'), 1)

    def test_signed_out_requires_zero_recipient_and_zero_actor_devices(self):
        self.guard_setup([]); self.assertEqual(self.p.guard('signed-out', registered=False), 0)
        self.guard_setup([I])
        with self.assertRaises(SystemExit): self.p.guard('signed-out', registered=False)
        self.guard_setup([], actor=[I])
        with self.assertRaises(SystemExit): self.p.guard('signed-out', registered=False)

    def test_protected_device_drift_prevents_mutation(self):
        self.guard_setup([I], fingerprint={'f': 'changed', 'n': 2})
        with self.assertRaises(SystemExit): self.p.guard('normal')

    def test_failed_observation_is_a_failure_not_a_successful_log_line(self):
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AssertionError): self.p.expect('missed delivery', False, notification_in_tray=False)
        self.assertFalse(self.p.matrix[0]['passed'])

    def test_tray_uses_active_keys_and_excludes_other_packages(self):
        with patch.object(probe, 'adb', return_value=f'0|android|1|system|1000\n0|com.example.pinpoint|0|{R}|10123\n') as adb:
            self.assertEqual(self.p.tray(), [R])
        self.assertEqual(adb.call_args.args[2], 'cmd notification list')

    def test_permission_oracle_requires_one_actual_runtime_state(self):
        for state in ('true', 'false'):
            with patch.object(probe, 'adb', return_value=f'  android.permission.POST_NOTIFICATIONS: granted={state}, flags=[]'):
                self.assertEqual(self.p.permission_denied(), state == 'false')
        for text in ('', 'permission unknown', 'android.permission.POST_NOTIFICATIONS: granted=false\nandroid.permission.POST_NOTIFICATIONS: granted=true'):
            with patch.object(probe, 'adb', return_value=text):
                with self.assertRaises(AssertionError): self.p.permission_denied()

    def test_readiness_rejects_stale_token_and_wrong_session_without_logging_digest(self):
        ready = dict(expected_user_matches=True, configured_backend_matches=True,
                     notifications_enabled=True, permission_granted=True, channel_importance=3, token_md5='private-digest')
        for change, registered in (({}, 'different'), ({'expected_user_matches': False}, 'private-digest'),
                                   ({'channel_importance': 0}, 'private-digest')):
            with patch.object(probe, 'adb', side_effect=['OK (1 test)', json.dumps({**ready, **change})]), \
                 patch.object(probe, 'sql', return_value=[{'digest': registered}]), contextlib.redirect_stdout(io.StringIO()) as out:
                with self.assertRaises(AssertionError): self.p.readiness()
            self.assertNotIn('private-digest', out.getvalue())
        with patch.object(probe, 'adb', side_effect=['OK (1 test)', json.dumps(ready)]), \
             patch.object(probe, 'sql', return_value=[{'digest': 'private-digest'}]), contextlib.redirect_stdout(io.StringIO()):
            self.p.readiness()

    def test_an_extra_identical_message_cannot_be_tapped_as_the_expected_notice(self):
        self.p.instrument = lambda phase: self.assertEqual(phase, 'clear-tray')
        self.p.change = lambda *a: ({'id': R, 'message': 'identical body'}, probe.now())
        self.p.wait_tray = lambda *a: True
        self.p.wait_delivered = lambda *a: {'d': True, 'a': 1, 'e': '', 'lat': 1}
        with patch.object(probe, 'adb', return_value=''), \
             patch.object(self.p, 'tray', side_effect=[[], [R, 'older-notice'], [R, 'older-notice']]), \
             patch.object(self.p, 'tap_text') as tap, patch.object(self.p, 'screen_has', return_value=False), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AssertionError): self.p.deliver_and_tap('positive', 'Under Review')
        tap.assert_not_called()

    def test_actor_wrong_identity_still_enters_owned_session_teardown(self):
        with patch.object(probe, 'sign_in', return_value=('token', C)):
            with self.assertRaises(SystemExit): self.p.session()
        with patch.object(self.p.sessions, '_call', return_value=(204, None)):
            self.p.sessions.teardown()
        self.assertEqual(self.p.sessions.summary(), 'confirmed')

    def test_cleanup_marker_cannot_change_scope(self):
        self.p.marker = "PUSHFCM-x'; delete from reports; --"
        with self.assertRaises(ValueError): self.p.cleanup_statement()

    def finish_stub(self, signout_fails=False):
        self.guard_setup([])
        baseline = [{'name': 'reports', 'count': 0, 'digest': 'baseline'}]
        ledger = probe.safety.RunLedger(str(pathlib.Path(self.temp.name)/'push-ledger.json'),
            recipient=C, actor=O, device_precheck='clean', protected_device_fp=self.p.cfp,
            baseline={'reports': 0}, baseline_fingerprints=baseline)
        def instrument(_):
            if signout_fails: raise SystemExit('UI signout failed')
        self.p.instrument = instrument
        with patch.object(probe, 'adb', return_value=''), patch.object(probe, 'sql', return_value=[{'c': 0}]), \
             patch.object(probe, 'snapshot', return_value=baseline), contextlib.redirect_stdout(io.StringIO()):
            self.p.finish(ledger.path)

    def test_cleanup_success_does_not_turn_failed_checks_into_a_pass(self):
        self.finish_stub()
        self.assertEqual(self.p.exit_code, 1)

    def test_unresolved_app_signout_is_a_failure_even_after_fixture_cleanup(self):
        self.p.test_ok = True
        self.finish_stub(signout_fails=True)
        self.assertEqual(self.p.exit_code, 1)
