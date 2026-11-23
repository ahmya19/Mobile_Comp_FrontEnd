#!/usr/bin/env python3
"""Ordinary-client authorization probe against the hosted project (public key + demo sessions only).

Privileged SQL cannot prove what an ordinary client is allowed to do, so this script signs in as the demo citizen
and official through the public Auth API and checks that forbidden requests are refused and allowed ones work:
  * anonymous and garbage-token requests,
  * service-only RPCs, the private schema, and direct table writes (all must be refused),
  * cross-account reads (must return nothing),
  * Storage: forbidden upload paths, overwrite, cross-account delete/read/sign, and one allowed lifecycle on a
    single labelled object in the citizen's OWN folder (upload, read, list, delete).
Every refused request is chosen so that, even if it were wrongly allowed, it cannot change real data (random IDs,
invalid payloads, same-status updates). The only write is the lifecycle object, recorded in an exclusive private
ledger BEFORE upload, removed through the ordinary API, and verified absent with privileged read-only SQL.
Tokens, passwords and private IDs are never printed or written. Use a fresh --work directory per run.
"""
import argparse, base64, json, os, subprocess, sys, time, urllib.error, urllib.request, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hosted_safety  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
# A tiny valid JPEG (1x1) well under the bucket's 250 KB limit.
JPEG = base64.b64decode('/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=')


def props():
    out = {}
    path = os.path.join(REPO, 'local.properties')
    if not os.path.exists(path): return out  # CI and unit tests have no private configuration
    for line in open(path, encoding='utf-8'):
        if '=' in line and not line.startswith('#'):
            k, v = line.strip().split('=', 1); out[k] = v
    return out


CFG = props(); BASE = CFG.get('SUPABASE_URL', ''); KEY = CFG.get('SUPABASE_PUBLISHABLE_KEY', '')


def call(method, path, token=None, body=None, headers=None, raw=None, ctype='application/json'):
    h = {'apikey': KEY}
    if token: h['Authorization'] = f'Bearer {token}'
    if headers: h.update(headers)
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    if data is not None: h.setdefault('Content-Type', ctype)
    req = urllib.request.Request(BASE + path, data, h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode('utf-8', 'replace'); status = r.status
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace'); status = e.code
    try: parsed = json.loads(text) if text else None
    except ValueError: parsed = text[:200]
    return status, parsed


def sql(query):
    # cmd.exe (npx.cmd) truncates an argument at its first newline, which would run only part of a statement.
    if len(query.splitlines()) > 1: raise ValueError('Send single-line SQL through npx.cmd.')
    r = subprocess.run(['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'db', 'query', '--remote', '--read-only', query],
                       cwd=REPO, capture_output=True, text=True, encoding='utf-8')
    t = r.stdout; i = t.find('{')
    if i < 0: raise SystemExit('SQL failed: ' + (r.stderr or t)[:300])
    d = json.loads(t[i:])
    if 'error' in d: raise SystemExit('SQL error: ' + json.dumps(d['error'])[:300])
    return d.get('rows', [])


def sign_in(email, password):
    s, j = call('POST', '/auth/v1/token?grant_type=password', body={'email': email, 'password': password})
    assert s == 200, f'sign-in failed ({s})'
    return j['access_token'], j['user']['id']


def sign_out_local(token):
    """Local-scope logout (never revokes the user's other sessions). Returns (outcome, detail), never raises; see hosted_safety."""
    return hosted_safety.logout_local(call, token)


results = []


def record(name, ok, detail):
    results.append({'probe': name, 'pass': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ' -> ' + detail)


def denied(name, status, body):
    code = body.get('code') if isinstance(body, dict) else None
    inner = str(body.get('statusCode')) if isinstance(body, dict) and body.get('statusCode') is not None else None
    # Storage reports policy refusals as HTTP 400 with statusCode 403/404 in the body; a bare 400 is a malformed request, not a denial.
    # PGRST106 = the private schema is not exposed through the API at all (the intended refusal).
    ok = status in (401, 403, 404) or (status == 400 and inner in ('403', '404')) or (status == 406 and code == 'PGRST106')
    record(name, ok, f'HTTP {status}' + (f' code {code}' if code else '') + (f' storage {inner}' if inner else ''))


def empty(name, status, body):
    record(name, status == 200 and body == [], f'HTTP {status} rows={len(body) if isinstance(body, list) else "?"}')


def attempt_forbidden_uploads(ledger, ctok, cuid, ouid, call_fn=None, sql_fn=None):
    """Every exact bucket/path is recorded BEFORE its upload and checked afterwards on its own. An unexpected object is a
    failure that stays visible in the ledger (cleanup_required); it is never masked by a privileged cleanup. Returns the
    list of unexpected objects. A lost response does not hide an object: presence is read from the host, not the reply."""
    call_fn = call_fn or call; sql_fn = sql_fn or sql
    plan = [(f'report-images/{ouid}/{uuid.uuid4()}/photo.jpg', "another account's folder"),
            (f'report-images/{cuid}/{uuid.uuid4()}/photo.png', 'wrong file name'),
            (f'report-images/{cuid}/{uuid.uuid4()}/photo.jpg/extra', 'extra path segment'),
            (f'report-originals/{cuid}/{uuid.uuid4()}/original', 'original for a report that does not exist')]
    attempts = [{'path': path, 'label': label, 'state': 'planned'} for path, label in plan]
    ledger.update(forbidden_attempts=attempts)   # durable BEFORE the first upload

    def exists(path):
        bucket, name = path.split('/', 1)
        return int(sql_fn(f"select count(*)::int n from storage.objects where bucket_id='{bucket}' and name='{name}'")[0]['n'])
    unexpected = []
    for entry in attempts:
        path, label = entry['path'], entry['label']
        if exists(path):                       # fresh UUID paths: anything present beforehand is a collision, not a result
            entry['state'] = 'PRE-EXISTING'; unexpected.append(path); record(f'citizen: upload {label}', False, 'path already existed before the attempt'); continue
        try:
            s, j = call_fn('POST', f'/storage/v1/object/{path}', token=ctok, raw=JPEG, ctype='image/jpeg')
        except Exception as error:             # lost response: the object may still exist, so fall through to the host check
            s, j = None, type(error).__name__
        if s is None: record(f'citizen: upload {label}', False, f'no response ({j}); host check follows')
        else: denied(f'citizen: upload {label}', s, j)
        entry['http'] = s
        if exists(path): entry['state'] = 'UNEXPECTED-OBJECT-PRESENT'; unexpected.append(path)
        else: entry['state'] = 'absent'
    ledger.update(forbidden_attempts=attempts, cleanup_required=unexpected)
    record('forbidden uploads created no object (each exact path checked)', not unexpected, f'unexpected objects: {len(unexpected)}')
    for path in unexpected:                    # best effort only for the citizen's own folder; otherwise record precisely
        entry = next(e for e in attempts if e['path'] == path)
        if path.startswith('report-images/') and f'/{cuid}/' in path:   # never attempt deletes in the originals bucket
            try: s, _ = call_fn('DELETE', f'/storage/v1/object/{path}', token=ctok)
            except Exception: s = None
            entry['client_delete_http'] = s
            entry['state'] = 'removed-by-owner-but-policy-regressed' if not exists(path) else 'STILL-PRESENT-cleanup-required'
        else:
            entry['state'] = 'STILL-PRESENT-cleanup-required (no client delete attempted here: other account, or the originals bucket)'
    ledger.update(forbidden_attempts=attempts, cleanup_required=[e['path'] for e in attempts if 'cleanup-required' in e['state']])
    return unexpected



def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--credentials', required=True)
    ap.add_argument('--work', required=True, help='fresh private directory outside Git')
    a = ap.parse_args(argv)
    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO:
        raise SystemExit('Use a private --work directory outside the repository.')
    os.makedirs(work, exist_ok=True)
    creds = json.load(open(a.credentials, encoding='utf-8-sig'))
    cuid, ouid = creds['citizen']['uid'], creds['official']['uid']
    fixture_path = f'{cuid}/{uuid.uuid4()}/photo.jpg'
    # Intended scope is durable BEFORE any session or hosted write exists.
    ledger = hosted_safety.RunLedger(os.path.join(work, 'authz-probe-ledger.json'),
        intent='one orphan JPEG in the citizen own folder, plus exact-path forbidden upload attempts', object=fixture_path,
        bucket='report-images', status='intent-recorded')
    sessions = hosted_safety.SessionRegistry(call)
    fixture_created = False
    failure = None
    try:
        ctok, tuid = sign_in(creds['citizen']['email'], creds['citizen']['password']); sessions.add('citizen', ctok)
        if tuid != cuid: raise RuntimeError('Citizen credentials do not match the recorded account.')
        otok, tuid = sign_in(creds['official']['email'], creds['official']['password']); sessions.add('official', otok)
        if tuid != ouid: raise RuntimeError('Official credentials do not match the recorded account.')
        anyone = sql("select id::text id, status, owner_id::text owner from public.reports where owner_id is not null order by created_at limit 1")[0]
        # All existing reports belong to the demo citizen, so the non-owner attempt is made by the official.
        owned_by_citizen = anyone['id']
        rand = str(uuid.uuid4())

        # --- anonymous and garbage token
        s, j = call('GET', '/rest/v1/reports?select=id&limit=1'); denied('anon: read reports', s, j)
        s, j = call('POST', '/rest/v1/rpc/submit_report', body={'p_id': rand, 'p_category': 'Invalid', 'p_description': 'invalid probe', 'p_location': 'x', 'p_barangay': 'x'}); denied('anon: submit_report', s, j)
        s, j = call('GET', '/rest/v1/reports?select=id&limit=1', token='not.a.jwt'); record('garbage bearer: read reports', s == 401, f'HTTP {s}')
        s, j = call('POST', '/storage/v1/object/list/report-images', body={'prefix': '', 'limit': 5}); record('anon: list report-images', s in (401, 403) or j == [] or (isinstance(j, dict) and str(j.get('statusCode')) in ('403', '404')), f'HTTP {s} rows={len(j) if isinstance(j, list) else "?"}')

        # --- service-only RPCs and private schema, as an ordinary citizen (arguments are harmless even if allowed)
        for fn, body in [('claim_push_batch', {'p_report_id': rand}), ('finish_push', {'p_notification_id': rand, 'p_lease_token': rand}),
                         ('authorize_push_dispatch', {'p_token': '0' * 64}), ('remove_invalid_push_token', {'p_token': 'probe-nonexistent-token'}),
                         ('ingest_sms_report', {'p_gateway_id': '!', 'p_message_id': 'x', 'p_payload_hash': 'x', 'p_category': 'x', 'p_description': 'x', 'p_barangay': 'x'})]:
            s, j = call('POST', f'/rest/v1/rpc/{fn}', token=ctok, body=body); denied(f'citizen: rpc {fn}', s, j)
        s, j = call('GET', '/rest/v1/officials?select=*', token=ctok, headers={'Accept-Profile': 'private'}); denied('citizen: private.officials via API', s, j)
        s, j = call('GET', '/rest/v1/push_outbox?select=*', token=ctok, headers={'Accept-Profile': 'private'}); denied('citizen: private.push_outbox via API', s, j)

        # --- direct table writes (invalid or no-match payloads cannot change data even if wrongly allowed)
        for table in ['reports', 'notifications', 'devices', 'report_votes', 'profiles', 'report_history']:
            s, j = call('POST', f'/rest/v1/{table}', token=ctok, body={}, headers={'Prefer': 'return=minimal'}); denied(f'citizen: direct insert {table}', s, j)
        s, j = call('PATCH', f'/rest/v1/reports?id=eq.{rand}', token=ctok, body={'status': 'Resolved'}, headers={'Prefer': 'return=minimal'}); denied('citizen: direct update reports', s, j)
        s, j = call('DELETE', f'/rest/v1/reports?id=eq.{rand}', token=ctok, headers={'Prefer': 'return=minimal'}); denied('citizen: direct delete reports', s, j)
        s, j = call('PATCH', f'/rest/v1/notifications?id=eq.{rand}', token=ctok, body={'read': True}, headers={'Prefer': 'return=minimal'}); denied('citizen: direct update notifications', s, j)

        # --- role and ownership checks through the supported RPCs
        s, j = call('POST', '/rest/v1/rpc/update_report_status', token=ctok, body={'p_report_id': anyone['id'], 'p_status': anyone['status']}); denied('citizen: update_report_status (not an official)', s, j)
        s, j = call('POST', '/rest/v1/rpc/attach_original', token=otok, body={'p_report_id': owned_by_citizen}); denied("official: attach_original on the citizen's report (not the owner)", s, j)
        s, j = call('POST', '/rest/v1/rpc/my_profile', token=ctok, body={}); record('citizen: my_profile is not an official', s == 200 and j.get('official') is False, f'HTTP {s} official={j.get("official") if isinstance(j, dict) else "?"}')
        s, j = call('POST', '/rest/v1/rpc/my_profile', token=otok, body={}); record('official: my_profile is an official', s == 200 and j.get('official') is True, f'HTTP {s}')

        # --- cross-account reads
        for table, col in [('notifications', 'user_id'), ('devices', 'user_id'), ('profiles', 'id'), ('report_votes', 'user_id')]:
            s, j = call('GET', f'/rest/v1/{table}?select=*&{col}=eq.{ouid}', token=ctok); empty(f"citizen: another account's {table}", s, j)
            s, j = call('GET', f'/rest/v1/{table}?select=*&{col}=eq.{cuid}', token=otok); empty(f"official: citizen's {table}", s, j)

        # --- Storage: forbidden uploads, each exact path ledgered first and verified on its own
        attempt_forbidden_uploads(ledger, ctok, cuid, ouid)

        # --- Storage: one allowed lifecycle on a labelled orphan object in the citizen's own folder
        path = f'report-images/{fixture_path}'
        ledger.update(allowed_object_state='upload-planned')
        try: s, j = call('POST', f'/storage/v1/object/{path}', token=ctok, raw=JPEG, ctype='image/jpeg')
        except Exception as error: s = None; ledger.error('allowed-upload', error)
        # A lost acknowledgement must not hide the object: its unique path is ours, so presence on the host decides.
        fixture_created = int(sql(f"select count(*)::int n from storage.objects where bucket_id='report-images' and name='{fixture_path}'")[0]['n']) == 1
        record('citizen: upload own orphan photo (allowed)', s in (200, 201), f'HTTP {s}' + ('' if s else ' (no response; host shows object=%s)' % fixture_created))
        if fixture_created:
            ledger.update(allowed_object_state='uploaded')
            s, _ = call('GET', f'/storage/v1/object/authenticated/{path}', token=ctok); record('citizen: read own orphan', s == 200, f'HTTP {s}')
            s, _ = call('GET', f'/storage/v1/object/authenticated/{path}', token=otok); record("official: cannot read citizen's unreferenced orphan", s in (400, 401, 403, 404), f'HTTP {s} (400/404 = hidden by policy)')
            s, _ = call('GET', f'/storage/v1/object/authenticated/{path}'); record('anon: cannot read it', s in (400, 401, 403, 404), f'HTTP {s}')
            s, j = call('POST', '/storage/v1/object/list/report-images', token=ctok, body={'prefix': f'{cuid}/', 'limit': 100})
            record('citizen: own folder listing works', s == 200 and isinstance(j, list), f'HTTP {s} entries={len(j) if isinstance(j, list) else "?"}')
            s, j = call('POST', '/storage/v1/object/list/report-images', token=otok, body={'prefix': f'{cuid}/{fixture_path.split("/")[1]}/', 'limit': 100})
            record("official: listing does not show the orphan", s == 200 and isinstance(j, list) and not any(e.get('name') == 'photo.jpg' for e in j), f'HTTP {s}')
            s, j = call('POST', f'/storage/v1/object/{path}', token=ctok, raw=JPEG, ctype='image/jpeg', headers={'x-upsert': 'true'}); denied('citizen: overwrite own object (no update policy)', s, j)
            s, j = call('POST', f'/storage/v1/object/sign/{path}', token=otok, body={'expiresIn': 60}); denied("official: cannot sign a URL for the citizen's orphan", s, j)
            s, j = call('POST', f'/storage/v1/object/sign/{path}'.replace('/sign/', '/sign/'), body={'expiresIn': 60}); denied('anon: cannot sign a URL', s, j)
            s, j = call('DELETE', f'/storage/v1/object/{path}', token=otok); denied("official: cannot delete the citizen's orphan", s, j)
            still = sql(f"select count(*)::int n from storage.objects where bucket_id='report-images' and name='{fixture_path}'")[0]['n']
            record('orphan survived the forbidden delete', still == 1, f'rows={still}')
    except BaseException as error:
        failure = error
        try: ledger.error('probe', error)
        except Exception: pass
    finally:
        def remove_fixture():
            if not fixture_created: ledger.update(fixture_cleanup='nothing-created'); return
            s, _ = call('DELETE', f'/storage/v1/object/report-images/{fixture_path}', token=ctok)
            gone = sql(f"select count(*)::int n from storage.objects where bucket_id='report-images' and name='{fixture_path}'")[0]['n']
            record('citizen: delete own orphan (allowed) and verify it is gone', s in (200, 204) and gone == 0, f'HTTP {s} rows_after={gone}')
            ledger.update(fixture_cleanup='cleaned' if gone == 0 else 'FAILED-object-still-present', object_delete_http=s)
        hosted_safety.run_all([('fixture-cleanup', remove_fixture)], ledger)
        sessions.teardown()
        ledger.update(session_cleanup=sessions.summary(), sessions={r: {'outcome': o, 'detail': d} for r, (o, d) in sessions.outcomes.items()})
    out = {'run_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'passed': sum(r['pass'] for r in results), 'total': len(results),
           'fixture_cleanup': ledger.data.get('fixture_cleanup'), 'session_cleanup': ledger.data.get('session_cleanup'),
           'cleanup_required': ledger.data.get('cleanup_required', []), 'results': results}
    json.dump(out, open(os.path.join(work, 'authz-probe-result.json'), 'w'), indent=1)
    print(f"\n{out['passed']}/{out['total']} probes passed | fixture cleanup: {out['fixture_cleanup']} | sessions: {out['session_cleanup']}")
    fixture_ok = ledger.data.get('fixture_cleanup') in ('cleaned', 'nothing-created') and not out['cleanup_required']
    return hosted_safety.final_exit(fixture_ok, out['session_cleanup'], out['passed'] == out['total'] and failure is None)


if __name__ == '__main__':
    sys.exit(main())
