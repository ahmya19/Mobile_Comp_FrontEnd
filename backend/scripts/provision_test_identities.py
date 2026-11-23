#!/usr/bin/env python3
"""Provision the two permanent, clearly labelled PinPoint TEST identities through trusted Auth administration.

  * pinpoint test citizen   - a normal citizen (no row in private.officials)
  * pinpoint test official  - assigned to Alangilan by an INSERT into private.officials (the documented admin path)

How: the Auth Admin API (POST /auth/v1/admin/users, email_confirm=true, so no email is sent and public-signup policy is
untouched). The project's secret key is obtained in memory from the already logged-in Supabase CLI session
(`supabase projects api-keys --reveal`), is passed only in an HTTP header, and is NEVER printed, logged, written to the ledger
or credentials file, or put in an exception message. Nothing is ever inserted into auth.* with SQL.

Safety order: (1) exclusive ledger with the full intended scope; (2) exclusive private credentials file written BEFORE any
creation (a created account is never left without its password); (3) refuse if either email already exists; (4) create,
recording each id in the ledger at once; (5) on any failure delete ONLY the ids this run created and report; (6) verify with
ordinary sign-ins (citizen is not an official; official is Alangilan; neither has a device registration; the two existing
demo accounts' device rows are unchanged); (7) always local-logout the verification sessions and report session outcome
separately. Passwords are random and never leave the credentials file. Use a private --work directory outside the repository.
"""
import argparse, json, os, re, secrets, subprocess, sys, time, urllib.error, urllib.request, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hosted_safety as safety  # noqa: E402
from hosted_authz_probe import BASE, KEY, REPO, call, sign_in  # noqa: E402
from map_nav_hosted import sql  # noqa: E402  (read-only unless write=True)

DOMAIN = 'pinpoint-fixtures.example.com'
IDENTITIES = {
    'citizen': {'email': f'pinpoint-test-citizen@{DOMAIN}', 'display_name': 'PinPoint TEST citizen (fixture)', 'barangay': None},
    'official': {'email': f'pinpoint-test-official@{DOMAIN}', 'display_name': 'PinPoint TEST official Alangilan (fixture)', 'barangay': 'Alangilan'},
}
PROJECT_REF = 'jaysxjjdvaitgrbqjlhe'


class OwnerStep(SystemExit):
    """A genuinely required manual access step (never a request for a secret in chat)."""


def fetch_secret_key(run=subprocess.run):
    """Reveal the project's secret key in memory from the logged-in CLI. Returns the key; never prints it."""
    r = run(['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'projects', 'api-keys', '--reveal'], capture_output=True, text=True, encoding='utf-8', cwd=REPO)
    text = r.stdout or ''; i = text.find('[')
    try: keys = json.loads(text[i:text.rindex(']') + 1])
    except Exception: keys = []
    for k in keys:
        if k.get('type') == 'secret' and str(k.get('api_key', '')).startswith('sb_secret_') and '…' not in str(k.get('api_key')): return k['api_key']
    raise OwnerStep('Could not obtain the project secret key from the Supabase CLI login. Owner step: run `npx supabase login` for the '
                    'project owner account (or create the two fixture users in the Supabase dashboard, Authentication > Users, with "Auto Confirm User" on).')


def admin(method, path, key, body=None, urlopen=urllib.request.urlopen):
    """Auth Admin API call. Errors carry only the HTTP status, never headers, bodies with credentials, or the key."""
    req = urllib.request.Request(BASE + path, json.dumps(body).encode() if body is not None else None, {'apikey': key, 'Content-Type': 'application/json'}, method=method)
    try:
        with urlopen(req, timeout=30) as r: raw = r.read().decode('utf-8', 'replace'); status = r.status
    except urllib.error.HTTPError as e: raw = e.read().decode('utf-8', 'replace'); status = e.code
    except Exception as e: raise RuntimeError(f'admin {method} {path.split("?")[0]} failed: {type(e).__name__}') from None
    try: return status, (json.loads(raw) if raw else None)
    except ValueError: return status, None


def existing(emails):
    q = "select email from auth.users where email in (" + ','.join("'" + e + "'" for e in emails) + ")"
    return [r['email'] for r in sql(q)]


def demo_device_fingerprint(demo_uids):
    ids = ','.join("'" + u + "'::uuid" for u in demo_uids)
    return sql(f"select md5(coalesce(string_agg(to_jsonb(d)::text,',' order by installation_id),'')) f,count(*) n from public.devices d where user_id in ({ids})")[0]


def provision(a, creds_demo, fetch=fetch_secret_key, admin_call=admin, sql_fn=sql, call_fn=call, sign_in_fn=sign_in, existing_fn=existing, fp_fn=demo_device_fingerprint):
    ledger = safety.RunLedger(os.path.join(a.work, 'identity-ledger.json'), intent='create two labelled fixture identities (citizen, Alangilan official)',
                              identities={r: {'email': v['email'], 'barangay': v['barangay']} for r, v in IDENTITIES.items()}, created={}, role_rows=[],
                              status='intent-recorded')
    sessions = safety.SessionRegistry(call_fn)
    created, role_set, ok, failure = {}, [], False, None
    demo_uids = [creds_demo['citizen']['uid'], creds_demo['official']['uid']]
    try:
        before = fp_fn(demo_uids); ledger.update(demo_device_rows_before=int(before['n']))
        taken = existing_fn([v['email'] for v in IDENTITIES.values()])
        if taken: raise SystemExit('Fixture email(s) already exist; refusing to create or overwrite: ' + ', '.join(taken) + ' (use --verify-only)')
        passwords = {r: secrets.token_urlsafe(24) for r in IDENTITIES}
        out = {r: {'email': v['email'], 'password': passwords[r], 'uid': None} for r, v in IDENTITIES.items()}
        out.update(fixture=True, label='PinPoint project-owned TEST identities (permanent); never use shared demo accounts for push tests', project=PROJECT_REF)
        with open(a.out, 'x', encoding='utf-8') as f: json.dump(out, f, indent=1)   # exclusive; BEFORE any creation
        ledger.update(credentials_file_written=True, status='credentials-file-written')
        key = fetch()
        for role, ident in IDENTITIES.items():
            status, data = admin_call('POST', '/auth/v1/admin/users', key, {
                'email': ident['email'], 'password': passwords[role], 'email_confirm': True,
                'app_metadata': {'pinpoint_fixture': True, 'fixture_role': role},
                'user_metadata': {'display_name': ident['display_name']}})
            if status not in (200, 201) or not isinstance(data, dict) or not safety.valid_uuid(str(data.get('id', ''))):
                raise RuntimeError(f'Auth admin create for the {role} fixture failed (HTTP {status})')
            created[role] = data['id']; out[role]['uid'] = data['id']
            ledger.update(created=dict(created))
        ledger.update(status='users-created')
        official_uid = created['official']
        sql_fn(f"insert into private.officials(user_id,barangay) values('{official_uid}','Alangilan')", write=True)
        role_set.append(official_uid); ledger.update(role_rows=list(role_set), status='roles-set')
        with open(a.out + '.tmp', 'w', encoding='utf-8') as f: json.dump(out, f, indent=1)
        os.replace(a.out + '.tmp', a.out)   # uids recorded
        ok = verify(a, out, ledger, sessions, demo_uids, before, sql_fn, call_fn, sign_in_fn, fp_fn)
    except BaseException as error:
        failure = error
        try: ledger.error('provision', error)
        except Exception: pass
    finally:
        if failure is not None and not isinstance(failure, OwnerStep) and created:
            rollback(ledger, created, role_set, fetch, admin_call, sql_fn)
        sessions.teardown()
        ledger.update(session_cleanup=sessions.summary(), sessions={r: {'outcome': o, 'detail': d} for r, (o, d) in sessions.outcomes.items()})
    if failure is not None:
        print('provisioning failed:', type(failure).__name__, str(failure)[:300]); return 1
    print('identities ready:', {r: i['email'] for r, i in IDENTITIES.items()}, '| verification ok:', ok, '| session cleanup:', ledger.data['session_cleanup'])
    return safety.final_exit(ok, ledger.data['session_cleanup'], ok)


def rollback(ledger, created, role_set, fetch, admin_call, sql_fn):
    """Delete ONLY the ids this run created (recorded in the ledger). The officials row cascades with the user."""
    results = {}
    try:
        key = fetch()
        for role, uid in created.items():
            if not safety.valid_uuid(uid): continue
            try: status, _ = admin_call('DELETE', f'/auth/v1/admin/users/{uid}', key); results[role] = status
            except Exception as error: results[role] = type(error).__name__
    except BaseException as error: results['key'] = type(error).__name__
    ledger.update(rollback=results, status='ROLLED-BACK' if all(v in (200, 204) for v in results.values()) and results else 'ROLLBACK-INCOMPLETE-needs-review')


def verify(a, out, ledger, sessions, demo_uids, before, sql_fn, call_fn, sign_in_fn, fp_fn):
    checks = {}
    ctok, cuid = sign_in_fn(out['citizen']['email'], out['citizen']['password']); sessions.add('citizen', ctok)
    otok, ouid = sign_in_fn(out['official']['email'], out['official']['password']); sessions.add('official', otok)
    checks['ids match creation'] = (cuid, ouid) == (out['citizen']['uid'], out['official']['uid'])
    s, cp = call_fn('POST', '/rest/v1/rpc/my_profile', token=ctok, body={}); checks['citizen is not an official'] = s == 200 and cp.get('official') is False
    s, op = call_fn('POST', '/rest/v1/rpc/my_profile', token=otok, body={}); checks['official is an Alangilan official'] = s == 200 and op.get('official') is True and op.get('barangay') == 'Alangilan'
    n = sql_fn(f"select count(*) c from public.devices where user_id in ('{cuid}'::uuid,'{ouid}'::uuid)")[0]['c']
    checks['no device registrations yet'] = int(n) == 0
    meta = sql_fn(f"select count(*) c from auth.users where id in ('{cuid}'::uuid,'{ouid}'::uuid) and raw_app_meta_data->>'pinpoint_fixture'='true' and email_confirmed_at is not null")[0]['c']
    checks['labelled fixtures, confirmed by admin'] = int(meta) == 2
    after = fp_fn(demo_uids); checks['demo accounts device rows unchanged'] = after == before
    ledger.update(verification=checks, status='verified' if all(checks.values()) else 'VERIFICATION-FAILED')
    for k, v in checks.items(): print(('PASS ' if v else 'FAIL ') + k)
    return all(checks.values())


def verify_only(a, out_path, creds_demo):
    out = json.load(open(out_path, encoding='utf-8'))
    if out.get('fixture') is not True: raise SystemExit('Not a fixture credentials file.')
    ledger = safety.RunLedger(os.path.join(a.work, 'identity-verify-ledger.json'), intent='verify existing fixture identities')
    sessions = safety.SessionRegistry(call)
    demo_uids = [creds_demo['citizen']['uid'], creds_demo['official']['uid']]
    try: ok = verify(a, out, ledger, sessions, demo_uids, demo_device_fingerprint(demo_uids), sql, call, sign_in, demo_device_fingerprint)
    finally:
        sessions.teardown(); ledger.update(session_cleanup=sessions.summary())
    return safety.final_exit(ok, ledger.data['session_cleanup'], ok)


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument('--demo-credentials', required=True, help='existing private demo accounts JSON (only their uids are read, to prove their devices are untouched)')
    p.add_argument('--work', required=True); p.add_argument('--out', required=True, help='private credentials file to create (exclusive)')
    p.add_argument('--apply', action='store_true'); p.add_argument('--verify-only', action='store_true')
    a = p.parse_args(argv)
    a.work = os.path.abspath(a.work); a.out = os.path.abspath(a.out)
    for path in (a.work, a.out):
        if os.path.commonpath([path, REPO]) == REPO: raise SystemExit('Keep the work directory and credentials outside the repository.')
    os.makedirs(a.work, exist_ok=True)
    demo = json.load(open(a.demo_credentials, encoding='utf-8-sig'))
    if a.verify_only: return verify_only(a, a.out, demo)
    if not a.apply:
        print('DRY RUN. Would create:', {r: (i['email'], i['barangay'] or 'citizen') for r, i in IDENTITIES.items()}, '\nPass --apply to proceed.'); return 0
    return provision(a, demo)


if __name__ == '__main__':
    sys.exit(main())
