"""Failure-path tests for the hosted-test drivers. Everything is mocked (network, CLI, ADB, SQL): no hosted call is made.

These assert behavior (what was deleted, what was sent, what the ledger says), not that a guard string exists in source.
"""
import contextlib
import importlib
import io
import json
import os
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
safety = importlib.import_module('hosted_safety')
workflow = importlib.import_module('workflow_hosted_regression')
authz = importlib.import_module('hosted_authz_probe')
fcm = importlib.import_module('real_fcm_probe')

CUID = '11111111-1111-4111-8111-111111111111'
OUID = '33333333-3333-4333-8333-333333333333'
RID = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000001'
OTHER = 'cccccccc-cccc-4ccc-8ccc-000000000002'
NID = 'dddddddd-dddd-4ddd-8ddd-000000000003'
DESC = 'CP16 eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee: water service issue.'
PHOTO = f'{CUID}/{RID}/photo.jpg'
CREDS = {'citizen': {'email': 'c@x.test', 'password': 'pw-c', 'uid': CUID}, 'official': {'email': 'o@x.test', 'password': 'pw-o', 'uid': OUID}, 'fixture': True}


class FakeHost:
    """A tiny hosted state plus the SQL the driver issues, answered by substring. Writes are recorded, never executed."""

    def __init__(self, **kw):
        self.reports = {RID: dict(owner=CUID, description=DESC, photo=PHOTO)}
        self.new_ids = [RID]
        self.notes, self.history, self.object_present = [NID], [{'previous_status': 'Pending', 'status': 'Under Review'}], True
        self.writes, self.requests, self.guard_refuses = [], [], False
        self.logout_status, self.logout_raises, self.download_raises, self.runner_rc, self.receipt = 204, False, False, 0, {'report_id': RID, 'description': DESC}
        self.deleted = False
        self.__dict__.update(kw)

    snap_calls = 0
    outbox_pending = 0
    device_deletes = 0
    @property
    def report_writes(self): return [w for w in self.writes if 'delete from public.reports' in w]
    device_rows = 0

    def snapshot(self):
        self.snap_calls += 1
        base = {k: [] for k in workflow.TABLES}; base['reports'] = ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa']
        if self.deleted or self.snap_calls == 1: return base      # the first call is the pre-run baseline
        snap = {k: list(v) for k, v in base.items()}; snap['reports'] = sorted(base['reports'] + self.new_ids)
        return snap

    def sql(self, query, write=False):
        if write:
            self.writes.append(query)
            if 'delete from public.devices' in query: self.device_deletes += 1; return []
            if self.guard_refuses: raise SystemExit('SQL error: Fixture identity mismatch; cleanup refused')
            self.deleted = True; return []
        q = ' '.join(query.split())
        if 'select owner_id::text owner, description, photo_path' in q:
            r = self.reports.get(q.split("id='")[1].split("'")[0]); return [dict(owner=r['owner'], description=r['description'], photo_path=r['photo'])] if r else []
        if 'select category, status, original_path, source' in q: return [dict(category='Water Interruption', status='Under Review', original_path=None, source='app')]
        if 'select previous_status, status from public.report_history' in q: return self.history
        if 'select id::text id, read from public.notifications' in q: return [dict(id=n, read=True) for n in self.notes]
        if 'select id::text id from public.notifications' in q: return [dict(id=n) for n in self.notes]
        if 'from private.push_outbox o join public.notifications n' in q: return [dict(c=str(self.outbox_pending))]
        if 'select count(*) c from public.devices where user_id in' in q: return [dict(c=str(self.device_rows))]
        if 'from private.push_outbox' in q: return [dict(delivered=True, attempts=1)]
        if 'select count(*) c from public.report_history' in q: return [dict(c=str(len(self.history)))]
        if 'select count(*) c from public.reports where id=' in q: return [dict(c='0' if self.deleted else '1')]
        if 'storage.objects' in q: return [dict(c='1' if self.object_present and not self.deleted_object else '0')]
        raise AssertionError('unexpected SQL: ' + q[:120])

    deleted_object = False

    def call(self, method, path, token=None, **kw):
        self.requests.append((method, path))
        if path.startswith('/auth/v1/logout'):
            if self.logout_raises: raise OSError('network down')
            return self.logout_status, None
        if method == 'DELETE' and '/storage/v1/object/report-images/' in path:
            self.deleted_object = True; return 200, None
        raise AssertionError('unexpected call ' + path)


def run_workflow(host, work, serial='emulator-5556'):
    def fake_run(command, **kw):
        out = ''.join(f'CP-16 phase: {p}\nTime: 1.0\nOK (1 test)\n' for p in ('citizen', 'official', 'review')) if host.runner_rc == 0 else 'failed'
        return types.SimpleNamespace(returncode=host.runner_rc, stdout=out, stderr='')

    def fetch(path, token):
        if host.download_raises: raise OSError('download failed')
        return b'\xff\xd8\xff' + b'0' * 100
    with patch.object(workflow, 'sql', host.sql), patch.object(workflow, 'call', host.call), patch.object(workflow, 'snapshot', host.snapshot), \
            patch.object(workflow, 'sign_in', lambda e, p: ('tok-' + e, CUID)), patch.object(workflow, 'fetch_object', fetch), \
            patch.object(workflow.subprocess, 'run', fake_run), patch.object(workflow, 'clear_receipt', lambda s: None), \
            patch.object(workflow, 'read_receipt', lambda s: host.receipt):
        a = types.SimpleNamespace(work=work, serial=serial, credentials='unused')
        with contextlib.redirect_stdout(io.StringIO()):
            return workflow.execute(a, CREDS)


def ledger_of(work): return json.load(open(os.path.join(work, 'workflow-ledger.json')))


class SafetyHelperTest(unittest.TestCase):
    def test_logout_classification_is_honest(self):
        self.assertEqual(safety.logout_local(lambda *a, **k: (204, None), 't')[0], 'confirmed')
        self.assertEqual(safety.logout_local(lambda *a, **k: (401, None), 't')[0], 'already-invalid')
        self.assertEqual(safety.logout_local(lambda *a, **k: (503, None), 't')[0], 'unresolved')
        def boom(*a, **k): raise OSError('dns')
        outcome, detail = safety.logout_local(boom, 't')
        self.assertEqual(outcome, 'unresolved'); self.assertEqual(detail, 'OSError')

    def test_one_failing_logout_does_not_skip_the_other_session(self):
        seen = []
        def call(method, path, token=None, **kw):
            seen.append(token)
            if token == 'citizen-token': raise OSError('network down')
            return 204, None
        reg = safety.SessionRegistry(call); reg.add('citizen', 'citizen-token'); reg.add('official', 'official-token')
        outcomes = reg.teardown()
        self.assertEqual(seen, ['citizen-token', 'official-token'], 'both teardowns must be attempted')
        self.assertEqual(outcomes['citizen'][0], 'unresolved'); self.assertEqual(outcomes['official'][0], 'confirmed')
        self.assertEqual(reg.summary(), 'unresolved', 'one unresolved session makes the whole cleanup unresolved')
        self.assertEqual(safety.final_exit(True, reg.summary(), True), 1)

    def test_summary_distinguishes_confirmed_from_already_invalid(self):
        reg = safety.SessionRegistry(lambda *a, **k: (401, None)); reg.add('citizen', 't'); reg.teardown()
        self.assertEqual(reg.summary(), 'already-invalid')
        self.assertEqual(safety.SessionRegistry(lambda *a, **k: (204, None)).summary(), 'none-created')

    def test_ledger_is_exclusive_and_updates_atomically(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'ledger.json'); led = safety.RunLedger(path, a=1)
            with self.assertRaises(FileExistsError): safety.RunLedger(path)
            led.update(b=2); self.assertEqual(json.load(open(path))['b'], 2)
            self.assertEqual([f for f in os.listdir(d) if f.startswith('.ledger-')], [], 'no temp file left behind')
            self.assertNotIn('token', json.dumps(json.load(open(path))))

    def test_cleanup_steps_all_run_and_failures_are_recorded(self):
        order = []
        def bad(): order.append('bad'); raise RuntimeError('x')
        results = safety.run_all([('a', bad), ('b', lambda: order.append('b')), ('c', lambda: (_ for _ in ()).throw(SystemExit('sql')))])
        self.assertEqual(order, ['bad', 'b']); self.assertEqual([n for n, e in results if e], ['a', 'c'])


class WorkflowDriverTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(); self.work = self.dir.name
    def tearDown(self): self.dir.cleanup()

    def assertNoDeletion(self, host):
        self.assertEqual(host.report_writes, [], 'no destructive report SQL may be sent')
        self.assertFalse(host.deleted_object, 'no Storage delete may be sent')

    def test_happy_path_cleans_with_one_guarded_transaction_and_confirmed_logout(self):
        host = FakeHost(); code = run_workflow(host, self.work)
        self.assertEqual(code, 0); self.assertEqual(len(host.report_writes), 1)
        sql = host.report_writes[0]; self.assertTrue(sql.startswith('begin;') and sql.endswith('commit;')); self.assertNotIn('\n', sql)
        for guard in ('Fixture identity mismatch', 'Unexpected notification references', 'Unexpected history references'): self.assertIn(guard, sql)
        led = ledger_of(self.work); self.assertEqual(led['fixture_cleanup'], 'cleaned'); self.assertEqual(led['session_cleanup'], 'confirmed')

    def test_unrelated_sole_new_report_is_never_touched(self):
        host = FakeHost(new_ids=[OTHER], receipt={'report_id': RID, 'description': DESC})
        host.reports[OTHER] = dict(owner=CUID, description='someone else', photo=None)
        code = run_workflow(host, self.work)
        self.assertEqual(code, 1); self.assertNoDeletion(host)
        led = ledger_of(self.work); self.assertEqual(led['fixture_cleanup'], 'REFUSED'); self.assertIn(OTHER, led['new_ids']['reports'])

    def test_missing_receipt_means_no_ownership_and_no_deletion(self):
        host = FakeHost(receipt={}); code = run_workflow(host, self.work)
        self.assertEqual(code, 1); self.assertNoDeletion(host); self.assertEqual(ledger_of(self.work)['fixture_cleanup'], 'REFUSED')

    def test_wrong_owner_refused(self):
        host = FakeHost(); host.reports[RID]['owner'] = '99999999-9999-4999-8999-999999999999'
        code = run_workflow(host, self.work); self.assertEqual(code, 1); self.assertNoDeletion(host)

    def test_wrong_run_marker_refused(self):
        host = FakeHost(); host.reports[RID]['description'] = 'CP16 something else entirely'
        code = run_workflow(host, self.work); self.assertEqual(code, 1); self.assertNoDeletion(host)

    def test_photo_not_bound_to_owner_and_report_refused(self):
        host = FakeHost(); host.reports[RID]['photo'] = f'{CUID}/{OTHER}/photo.jpg'
        code = run_workflow(host, self.work); self.assertEqual(code, 1); self.assertNoDeletion(host)

    def test_unexpected_extra_references_are_refused_by_the_transaction(self):
        host = FakeHost(guard_refuses=True)
        code = run_workflow(host, self.work)
        self.assertEqual(code, 1); self.assertEqual(len(host.report_writes), 1)
        led = ledger_of(self.work); self.assertEqual(led['fixture_cleanup'], 'REFUSED-by-guard'); self.assertFalse(host.deleted_object)

    def test_download_failure_still_cleans_up_scoped_fixture_and_tears_down_sessions(self):
        host = FakeHost(download_raises=True); code = run_workflow(host, self.work)
        self.assertEqual(code, 1, 'the original failure must keep the run failed')
        self.assertEqual(len(host.report_writes), 1, 'identity was proven before the failure, so scoped cleanup still runs')
        led = ledger_of(self.work); self.assertEqual(led['fixture_cleanup'], 'cleaned')
        self.assertTrue(any(e['where'] == 'run' for e in led['errors'])); self.assertIn('citizen', led['sessions'])

    def test_logout_http_failure_is_not_reported_as_cleaned(self):
        host = FakeHost(logout_status=503); code = run_workflow(host, self.work)
        led = ledger_of(self.work); self.assertEqual(led['fixture_cleanup'], 'cleaned'); self.assertEqual(led['session_cleanup'], 'unresolved')
        self.assertEqual(code, 1, 'fixtures cleaned but a session may remain: exit must be non-zero')

    def test_logout_network_failure_is_unresolved(self):
        host = FakeHost(logout_raises=True); code = run_workflow(host, self.work)
        self.assertEqual(ledger_of(self.work)['session_cleanup'], 'unresolved'); self.assertEqual(code, 1)

    def test_partial_setup_runner_failure_deletes_nothing(self):
        host = FakeHost(runner_rc=1, new_ids=[], receipt={}); code = run_workflow(host, self.work)
        self.assertEqual(code, 1); self.assertNoDeletion(host)
        self.assertEqual(ledger_of(self.work)['fixture_cleanup'], 'REFUSED')

    def test_shared_demo_accounts_are_refused_before_any_hosted_call_or_status_change(self):
        calls = []
        shared = {k: v for k, v in CREDS.items() if k != 'fixture'}
        host = FakeHost()
        with patch.object(workflow, 'snapshot', lambda: calls.append(1) or host.snapshot()), patch.object(workflow, 'sql', lambda *a, **k: calls.append(2) or []):
            with self.assertRaises(SystemExit) as cm: workflow.execute(types.SimpleNamespace(work=self.work, serial='emulator-5556', credentials='x'), shared)
        self.assertIn('fixture identities', str(cm.exception)); self.assertEqual(calls, []); self.assertEqual(os.listdir(self.work), [], 'not even a ledger is created')

    def test_existing_registrations_on_the_fixture_identities_refuse_the_run_and_are_not_deleted(self):
        host = FakeHost(device_rows=1); code = run_workflow(host, self.work)
        self.assertEqual(code, 1); self.assertEqual(host.device_deletes, 0, 'registrations this run did not prove it owns are never removed')
        self.assertNoDeletion(host)

    def test_fixture_device_registrations_are_removed_after_a_clean_run_with_a_label_guard(self):
        host = FakeHost(); self.assertEqual(run_workflow(host, self.work), 0)
        self.assertEqual(host.device_deletes, 1); self.assertEqual(ledger_of(self.work)['fixture_devices'], 'removed')
        stmt = [w for w in host.writes if 'delete from public.devices' in w][0]
        self.assertIn("raw_app_meta_data->>'pinpoint_fixture'='true'", stmt); self.assertIn('device cleanup refused', stmt)

    def test_undelivered_push_outbox_defers_cleanup_instead_of_racing_the_dispatcher(self):
        host = FakeHost(outbox_pending=2)
        with patch.object(workflow, 'OUTBOX_WAIT', 0), patch.object(workflow, 'OUTBOX_POLL', 0):
            code = run_workflow(host, self.work)
        self.assertEqual(code, 1); self.assertNoDeletion(host)
        self.assertEqual(ledger_of(self.work)['fixture_cleanup'], 'DEFERRED-outbox-not-delivered')

    def test_existing_ledger_is_refused_before_any_hosted_call(self):
        host = FakeHost(); run_workflow(host, self.work)
        second = FakeHost(); calls = []
        with patch.object(workflow, 'snapshot', lambda: calls.append(1) or second.snapshot()):
            with self.assertRaises(FileExistsError): workflow.execute(types.SimpleNamespace(work=self.work, serial='emulator-5556', credentials='x'), CREDS)
        self.assertEqual(calls, [], 'no hosted read may happen once the ledger already exists')


class ForbiddenUploadTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(); self.ledger = safety.RunLedger(os.path.join(self.dir.name, 'l.json'))
        authz.results.clear()
    def tearDown(self): self.dir.cleanup()

    def run_probe(self, responses, present_after=(), lost=()):
        """responses: status per upload. present_after: path substrings that exist on the host afterwards."""
        order, created = [], set()
        ledger_at_first_upload = {}
        def call(method, path, token=None, **kw):
            if method == 'POST':
                if not ledger_at_first_upload: ledger_at_first_upload.update(json.load(open(self.ledger.path)))
                key = path.split('/storage/v1/object/')[1]; order.append(key)
                if any(s in key for s in present_after): created.add(key)
                if len(order) in lost: raise OSError('lost response')
                return responses[len(order) - 1], {'statusCode': '403'}
            if method == 'DELETE':
                created.discard(path.split('/storage/v1/object/')[1]); return 200, None
            raise AssertionError(path)
        def sql(q, **kw):
            name = q.split("name='")[1].split("'")[0]; return [{'n': 1 if f'{q.split("bucket_id=" + chr(39))[1].split(chr(39))[0]}/{name}' in created else 0}]
        unexpected = authz.attempt_forbidden_uploads(self.ledger, 'ctok', CUID, OUID, call_fn=call, sql_fn=sql)
        return unexpected, ledger_at_first_upload, order

    def test_every_path_is_ledgered_before_the_first_upload(self):
        unexpected, at_first, order = self.run_probe([403, 400, 400, 400])
        self.assertEqual(len(at_first['forbidden_attempts']), 4); self.assertTrue(all(e['state'] == 'planned' for e in at_first['forbidden_attempts']))
        self.assertEqual({e['path'] for e in at_first['forbidden_attempts']}, {f'{p}' for p in order}); self.assertEqual(unexpected, [])

    def test_unexpected_success_on_the_original_path_fails_and_stays_actionable(self):
        unexpected, _, _ = self.run_probe([400, 400, 400, 200], present_after=('report-originals/',))
        self.assertEqual(len(unexpected), 1); self.assertTrue(unexpected[0].startswith('report-originals/'))
        led = json.load(open(self.ledger.path))
        self.assertEqual(led['cleanup_required'], unexpected, 'no client authority over originals: it must stay recorded, not be hidden')
        self.assertFalse([r for r in authz.results if r['probe'].startswith('forbidden uploads created no object')][0]['pass'])

    def test_unexpected_object_in_another_accounts_folder_is_not_masked(self):
        unexpected, _, _ = self.run_probe([200, 400, 400, 400], present_after=(f'report-images/{OUID}/',))
        led = json.load(open(self.ledger.path)); self.assertEqual(len(unexpected), 1)
        self.assertTrue(any('cleanup-required' in e['state'] for e in led['forbidden_attempts']))

    def test_lost_response_does_not_hide_a_created_object(self):
        unexpected, _, _ = self.run_probe([400, 400, 400, 400], present_after=('photo.png',), lost=(2,))
        self.assertEqual(len(unexpected), 1); self.assertTrue(unexpected[0].endswith('photo.png'))

    def test_own_folder_object_is_removed_by_the_owner_but_the_failure_remains(self):
        unexpected, _, _ = self.run_probe([400, 200, 400, 400], present_after=('photo.png',))
        led = json.load(open(self.ledger.path))
        self.assertEqual(led['cleanup_required'], [], 'the client could remove it')
        self.assertTrue(any(e['state'] == 'removed-by-owner-but-policy-regressed' for e in led['forbidden_attempts']))
        self.assertFalse([r for r in authz.results if r['probe'].startswith('forbidden uploads created no object')][0]['pass'])


class FcmGuardTest(unittest.TestCase):
    def make(self, recipient_rows, protected_fp):
        a = types.SimpleNamespace(work=tempfile.mkdtemp(), serial='emulator-5556', report_id=RID, run_id='t')
        p = fcm.Probe(a, CREDS); p.installation = 'inst-emulator'; p.cfp = {'f': 'x', 'n': 2}
        p.devices = lambda uid: [{'i': i} for i in recipient_rows] if uid == p.uid else []
        p.protected_fp = lambda: protected_fp
        p.validate_pair = lambda: None; p.verify_report = lambda: None
        self.sent = []
        p.rpc = lambda name, body: self.sent.append((name, body)); p.session = lambda: 'tok'; p.reporter_session = lambda: 'recipient-tok'
        return p

    def test_signed_out_status_change_is_guarded_and_aborts_on_a_new_registration(self):
        p = self.make(['inst-someone-else'], {'f': 'x', 'n': 2})
        with patch.object(fcm, 'call', lambda *a, **k: (200, [])):
            with self.assertRaises(SystemExit) as cm: p.change('Under Review', '5b signed out', registered=False)
        self.assertIn('unexpected recipient device registration', str(cm.exception)); self.assertEqual(self.sent, [], 'no RPC may be sent')

    def test_zero_registrations_signed_out_is_allowed_through_the_same_guard(self):
        p = self.make([], {'f': 'x', 'n': 2})
        with patch.object(fcm, 'call', lambda *a, **k: (200, [])):
            with self.assertRaises(SystemExit) as cm: p.change('Under Review', '5b signed out', registered=False)   # no notification row in the stub
        self.assertEqual(len(self.sent), 1, 'the guard passed and the RPC was issued exactly once'); self.assertIn('no notification', str(cm.exception))

    def test_changed_citizen_rows_abort_before_send(self):
        p = self.make([], {'f': 'CHANGED', 'n': 2})
        with patch.object(fcm, 'call', lambda *a, **k: (200, [])):
            with self.assertRaises(SystemExit): p.change('Under Review', 'x', registered=False)
        self.assertEqual(self.sent, [])

    def test_5b_path_uses_change_and_has_no_direct_status_rpc(self):
        text = (SCRIPTS / 'real_fcm_probe.py').read_text(encoding='utf-8')
        self.assertEqual(text.count("self.rpc('update_report_status'"), 1, 'only change() may issue the status RPC')


if __name__ == '__main__':
    unittest.main()
