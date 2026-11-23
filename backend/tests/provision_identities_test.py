"""Mocked tests for the fixture-identity provisioner: no hosted call, no real key, no CLI."""
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

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
prov = importlib.import_module('provision_test_identities')
safety = importlib.import_module('hosted_safety')

FAKE_KEY = 'sb_secret_FAKEKEYFORTESTSONLY0123456789'
DEMO = {'citizen': {'uid': '11111111-1111-4111-8111-111111111111'}, 'official': {'uid': '33333333-3333-4333-8333-333333333333'}}


class Env:
    """Stubs for every external dependency; records what was sent."""

    def __init__(self, tmp, **kw):
        self.tmp = tmp; self.a = types.SimpleNamespace(work=tmp, out=os.path.join(tmp, 'fixtures.json'))
        self.admin_calls, self.sql_writes, self.sql_reads, self.ledger_seen, self.key_fetches = [], [], [], [], 0
        self.users, self.fail_second, self.key_ok, self.existing = {}, False, True, []
        self.official_profile, self.demo_changes, self.logout_status, self.devices = True, False, 204, 0
        self.__dict__.update(kw)

    def fetch(self):
        self.key_fetches += 1
        if not self.key_ok: raise prov.OwnerStep('Owner step: run npx supabase login')
        return FAKE_KEY

    def admin(self, method, path, key, body=None):
        assert key == FAKE_KEY
        self.admin_calls.append((method, path, body))
        if not self.ledger_seen: self.ledger_seen.append(os.path.exists(os.path.join(self.tmp, 'identity-ledger.json')) and os.path.exists(self.a.out))
        if method == 'POST':
            if self.fail_second and len(self.users) == 1: return 500, {'msg': 'boom'}
            uid = f'{len(self.users) + 1}0000000-0000-4000-8000-00000000000{len(self.users) + 1}'
            self.users[body['email']] = uid; return 200, {'id': uid}
        if method == 'DELETE': self.users = {e: u for e, u in self.users.items() if u != path.rsplit('/', 1)[1]}; return 200, {}
        raise AssertionError(path)

    def sql(self, query, write=False):
        (self.sql_writes if write else self.sql_reads).append(query)
        if write: return []
        if 'raw_app_meta_data' in query: return [{'c': '2'}]
        if 'public.devices where user_id in' in query and 'count(*) c' in query: return [{'c': str(self.devices)}]
        return [{'c': '0'}]

    def fp(self, uids): return {'f': 'CHANGED' if self.demo_changes and self.sql_writes else 'same', 'n': 2}

    def sign_in(self, email, password):
        return 'tok-' + email, self.users[email]

    def call(self, method, path, token=None, body=None, **kw):
        if path.startswith('/auth/v1/logout'): return self.logout_status, None
        if path == '/rest/v1/rpc/my_profile':
            is_official = token.startswith('tok-pinpoint-test-official')
            return 200, {'official': is_official and self.official_profile, 'barangay': 'Alangilan' if is_official else None}
        raise AssertionError(path)

    def run(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = prov.provision(self.a, DEMO, fetch=self.fetch, admin_call=self.admin, sql_fn=self.sql, call_fn=self.call,
                                  sign_in_fn=self.sign_in, existing_fn=lambda emails: self.existing, fp_fn=self.fp)
        self.out = out.getvalue(); return code

    def ledger(self): return json.load(open(os.path.join(self.tmp, 'identity-ledger.json')))


class ProvisionTest(unittest.TestCase):
    def setUp(self): self.dir = tempfile.TemporaryDirectory(); self.tmp = self.dir.name
    def tearDown(self): self.dir.cleanup()

    def test_happy_path_creates_two_labelled_confirmed_identities_and_one_role_row(self):
        e = Env(self.tmp); self.assertEqual(e.run(), 0)
        posts = [c for c in e.admin_calls if c[0] == 'POST']; self.assertEqual(len(posts), 2)
        for _, path, body in posts:
            self.assertEqual(path, '/auth/v1/admin/users'); self.assertTrue(body['email_confirm']); self.assertTrue(body['app_metadata']['pinpoint_fixture'])
            self.assertIn('fixture', body['user_metadata']['display_name'].lower())
        self.assertEqual(len(e.sql_writes), 1)
        self.assertRegex(e.sql_writes[0], r"^insert into private\.officials\(user_id,barangay\) values\('[0-9a-f-]{36}','Alangilan'\)$")
        creds = json.load(open(e.a.out)); self.assertTrue(creds['fixture']); self.assertTrue(creds['citizen']['uid'] and creds['official']['uid'])
        led = e.ledger(); self.assertEqual(led['status'], 'verified'); self.assertEqual(led['session_cleanup'], 'confirmed')

    def test_ledger_and_credentials_file_exist_before_the_first_account_is_created(self):
        e = Env(self.tmp); e.run(); self.assertEqual(e.ledger_seen, [True])

    def test_no_sql_ever_touches_auth_tables_or_passwords(self):
        e = Env(self.tmp); e.run()
        for q in e.sql_writes: self.assertNotRegex(q.lower(), r'auth\.|password|encrypted_password|crypt\(')

    def test_secret_key_and_passwords_never_reach_stdout_ledger_or_errors(self):
        e = Env(self.tmp); e.run(); creds = json.load(open(e.a.out))
        blob = e.out + json.dumps(e.ledger())
        for secret in (FAKE_KEY, creds['citizen']['password'], creds['official']['password']): self.assertNotIn(secret, blob)

    def test_missing_cli_login_stops_before_any_creation_with_a_concrete_owner_step(self):
        e = Env(self.tmp, key_ok=False); self.assertEqual(e.run(), 1)
        self.assertEqual(e.admin_calls, []); self.assertEqual(e.sql_writes, []); self.assertIn('Owner step', e.out)

    def test_existing_fixture_email_is_refused_and_nothing_is_created(self):
        e = Env(self.tmp, existing=['pinpoint-test-citizen@pinpoint-fixtures.example.com']); self.assertEqual(e.run(), 1)
        self.assertEqual(e.admin_calls, []); self.assertEqual(e.key_fetches, 0, 'the key is not even fetched')
        self.assertFalse(os.path.exists(e.a.out), 'no credentials file for a refused run')

    def test_existing_credentials_file_is_never_overwritten(self):
        open(os.path.join(self.tmp, 'fixtures.json'), 'w').write('{"keep": true}')
        e = Env(self.tmp); self.assertEqual(e.run(), 1)
        self.assertEqual(e.admin_calls, []); self.assertEqual(json.load(open(e.a.out)), {'keep': True})

    def test_failure_after_the_first_account_deletes_only_what_this_run_created(self):
        e = Env(self.tmp, fail_second=True); self.assertEqual(e.run(), 1)
        deletes = [c for c in e.admin_calls if c[0] == 'DELETE']; self.assertEqual(len(deletes), 1)
        self.assertEqual(e.users, {}, 'the one created account was rolled back'); self.assertEqual(e.sql_writes, [], 'no role row without both accounts')
        self.assertEqual(e.ledger()['status'], 'ROLLED-BACK')

    def test_role_failure_after_both_accounts_rolls_both_back(self):
        e = Env(self.tmp)
        def failing_sql(query, write=False):
            if write: raise SystemExit('SQL error')
            return Env.sql(e, query, write)
        e.sql = failing_sql; self.assertEqual(e.run(), 1)
        self.assertEqual(len([c for c in e.admin_calls if c[0] == 'DELETE']), 2); self.assertEqual(e.users, {})

    def test_verification_failure_is_reported_not_hidden(self):
        e = Env(self.tmp, official_profile=False); code = e.run()
        self.assertEqual(code, 1); self.assertEqual(e.ledger()['status'], 'VERIFICATION-FAILED')
        self.assertFalse(e.ledger()['verification']['official is an Alangilan official'])

    def test_a_pre_existing_device_registration_fails_verification(self):
        e = Env(self.tmp, devices=1); self.assertEqual(e.run(), 1)
        self.assertFalse(e.ledger()['verification']['no device registrations yet'])

    def test_logout_failure_makes_the_run_unresolved_even_when_identities_verify(self):
        e = Env(self.tmp, logout_status=503); code = e.run()
        self.assertEqual(e.ledger()['status'], 'verified'); self.assertEqual(e.ledger()['session_cleanup'], 'unresolved'); self.assertEqual(code, 1)

    def test_demo_account_device_rows_changing_fails_verification(self):
        e = Env(self.tmp, demo_changes=True); self.assertEqual(e.run(), 1)
        self.assertFalse(e.ledger()['verification']['demo accounts device rows unchanged'])

    def test_secret_key_parser_masks_nothing_and_rejects_truncated_or_missing_keys(self):
        good = json.dumps([{'type': 'publishable', 'api_key': 'sb_publishable_x'}, {'type': 'secret', 'api_key': FAKE_KEY}])
        self.assertEqual(prov.fetch_secret_key(lambda *a, **k: types.SimpleNamespace(stdout=good, stderr='')), FAKE_KEY)
        for bad in ('', 'not json', json.dumps([{'type': 'secret', 'api_key': 'sb_secret_Rq09…'}]), json.dumps([{'type': 'legacy', 'api_key': 'eyJ'}])):
            with self.assertRaises(prov.OwnerStep): prov.fetch_secret_key(lambda *a, **k: types.SimpleNamespace(stdout=bad, stderr=''))

    def test_admin_errors_never_include_the_key(self):
        def boom(req, timeout=0): raise OSError('connection failed for ' + req.headers.get('Apikey', ''))
        with self.assertRaises(RuntimeError) as cm: prov.admin('POST', '/auth/v1/admin/users', FAKE_KEY, {}, urlopen=boom)
        self.assertNotIn(FAKE_KEY, str(cm.exception))


if __name__ == '__main__':
    unittest.main()
