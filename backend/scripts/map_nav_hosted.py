#!/usr/bin/env python3
"""Host driver for HostedMapNavigationTest (whole-app hosted map check).

Subcommands (run in order, or use `full`):
  prepare  - baseline counts, two labelled fixture reports via the citizen's ordinary session,
             independent privileged-SQL oracle, push the oracle to the emulator.
  run      - run the instrumented test on an isolated emulator (credentials redacted in output).
  cleanup  - delete ONLY the fixtures recorded in the private ledger, then verify the baseline.
  full     - prepare, run, cleanup (cleanup also runs if the test fails).

Requirements: Supabase CLI logged in and linked (privileged reads/cleanup), local.properties with the public
Supabase URL/publishable key, the private demo-accounts JSON, a booted emulator with both APKs installed.
Secrets are read locally and never printed. Evidence is written to --work (outside Git).
"""
import argparse, json, os, re, subprocess, sys, urllib.request, uuid

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PREFIX = 'MAPNAV-20261122-A01'
PKG = 'com.example.pinpoint'
ADB = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk', 'platform-tools', 'adb.exe')
FIXTURES = {  # unique, well-separated synthetic coordinates, away from every existing report
    'road': dict(category='Road Damage', latitude=13.600, longitude=121.000),
    'garbage': dict(category='Garbage', latitude=13.650, longitude=121.100),
}


def props():
    out = {}
    for line in open(os.path.join(REPO, 'local.properties'), encoding='utf-8'):
        if '=' in line and not line.startswith('#'):
            k, v = line.strip().split('=', 1); out[k] = v
    return out


def sql(query, write=False):
    cmd = ['npx.cmd', 'supabase', 'db', 'query', '--remote', '--yes' if write else '--read-only', query]
    r = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, encoding='utf-8', shell=False)
    text = r.stdout
    start = text.find('{')
    if start < 0:
        raise SystemExit('SQL failed: ' + (r.stderr or text)[:500])
    data = json.loads(text[start:])
    if 'error' in data:
        raise SystemExit('SQL error: ' + json.dumps(data['error'])[:500])
    return data.get('rows', [])


def counts():
    q = ("select (select count(*) from public.reports) reports,"
         "(select count(*) from public.reports where latitude is not null and longitude is not null) with_coords,"
         "(select count(*) from public.report_votes) votes,(select count(*) from public.notifications) notices,"
         "(select count(*) from public.report_history) history,(select count(*) from private.sms_receipts) sms_receipts,"
         "(select count(*) from private.push_outbox) outbox")
    return {k: int(v) for k, v in sql(q)[0].items()}


def http_json(url, body, headers):
    req = urllib.request.Request(url, json.dumps(body).encode(), headers, method='POST')
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw else None


def citizen_session(creds, cfg):
    base, key = cfg['SUPABASE_URL'], cfg['SUPABASE_PUBLISHABLE_KEY']
    tok = http_json(f"{base}/auth/v1/token?grant_type=password",
                    {'email': creds['citizen']['email'], 'password': creds['citizen']['password']},
                    {'apikey': key, 'content-type': 'application/json'})
    return base, key, tok['access_token'], tok['user']['id']


def adb(serial, *a, input_bytes=None):
    return subprocess.run([ADB, '-s', serial, *a], capture_output=True, input=input_bytes)


def shell_quote(v):
    return "'" + v.replace("'", "'\"'\"'") + "'"


def ledger_path(work): return os.path.join(work, 'map-nav-ledger.json')


def prepare(a, creds, cfg):
    os.makedirs(a.work, exist_ok=True)
    base_counts = counts()
    ids_before = [r['id'] for r in sql("select id::text id from public.reports order by created_at desc, id desc")]
    ledger = {'prefix': PREFIX, 'baseline_counts': base_counts, 'baseline_report_ids': ids_before,
              'fixtures': {k: dict(v, id=str(uuid.uuid4())) for k, v in FIXTURES.items()}, 'status': 'intent-recorded'}
    json.dump(ledger, open(ledger_path(a.work), 'w'), indent=1)  # intent first, so cleanup always knows the scope
    base, key, jwt, uid = citizen_session(creds, cfg)
    ledger['citizen_uid'] = uid
    for name, f in ledger['fixtures'].items():
        http_json(f"{base}/rest/v1/rpc/submit_report", {
            'p_id': f['id'], 'p_category': f['category'], 'p_description': f"{PREFIX} {name} marker fixture",
            'p_location': f"{PREFIX} location", 'p_barangay': 'Alangilan', 'p_latitude': f['latitude'],
            'p_longitude': f['longitude'], 'p_photo_path': None},
            {'apikey': key, 'authorization': f'Bearer {jwt}', 'content-type': 'application/json'})
        f['description'] = f"{PREFIX} {name} marker fixture"
    ledger['status'] = 'fixtures-created'
    json.dump(ledger, open(ledger_path(a.work), 'w'), indent=1)

    rows = sql("select id::text id, category, description, latitude, longitude, owner_id::text owner "
               "from public.reports order by created_at desc, id desc")
    has = lambda r: r['latitude'] is not None and r['longitude'] is not None
    page1 = rows[:25]
    cat = a.filter_category
    oracle = {
        'citizen_uid': uid, 'prefix': PREFIX, 'total_reports': len(rows),
        'coords': {r['id']: [r['latitude'], r['longitude']] for r in rows if has(r)},
        'page1': [r['id'] for r in page1 if has(r)], 'all': [r['id'] for r in rows if has(r)],
        'filter': {'category': cat, 'ids': [r['id'] for r in [x for x in rows if x['category'] == cat][:25] if has(r)]},
    }
    for name, f in ledger['fixtures'].items():
        row = next(r for r in rows if r['id'] == f['id'])
        assert row['owner'] == uid and row['description'] == f['description'], 'fixture not stored as expected'
        oracle[name] = {'id': f['id'], 'category': row['category'], 'description': row['description'],
                        'latitude': row['latitude'], 'longitude': row['longitude']}
    assert oracle['road']['id'] in oracle['page1'], 'road fixture must be on page one'
    groups = {}
    for r in rows:
        if has(r):
            groups.setdefault((r['latitude'], r['longitude']), []).append(r)
    stacks = [g for g in groups.values() if len(g) > 1]
    if stacks:  # reports sharing one coordinate: the chooser must make each reachable
        g = max(stacks, key=len)
        pick = next(r for r in g if r['description'].startswith('CP16 ') and sum(1 for x in g if x['description'] == r['description']) == 1)
        oracle['stack'] = {'count': len(g), 'ids': [r['id'] for r in g], 'latitude': g[0]['latitude'],
                           'longitude': g[0]['longitude'],
                           'pick': {'id': pick['id'], 'category': pick['category'], 'description': pick['description'],
                                    'latitude': pick['latitude'], 'longitude': pick['longitude']}}
    json.dump(oracle, open(os.path.join(a.work, 'map-nav-oracle.json'), 'w'), indent=1)
    adb(a.serial, 'shell', 'run-as', PKG, 'mkdir', '-p', 'files')
    # One command string, so the redirect runs inside run-as rather than in the device's outer shell.
    r = adb(a.serial, 'shell', f"run-as {PKG} sh -c 'cat > files/map-nav-oracle.json'",
            input_bytes=json.dumps(oracle).encode())
    assert r.returncode == 0, r.stderr.decode()[:300]
    print(json.dumps({'baseline': base_counts, 'after_fixtures': counts(), 'page1_with_coords': len(oracle['page1']),
                      'all_with_coords': len(oracle['all']), 'filter_ids': len(oracle['filter']['ids'])}))


def run(a, creds):
    c = creds['citizen']
    cmd = ('am instrument -w -r -e class com.example.pinpoint.HostedMapNavigationTest '
           '-e allowHostedMapChecks true '
           f'-e citizen_email {shell_quote(c["email"])} -e citizen_password {shell_quote(c["password"])} '
           f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner')
    r = adb(a.serial, 'shell', cmd)
    out = r.stdout.decode('utf-8', 'replace')
    out = out.replace(c['password'], '[redacted password]').replace(c['email'], '[demo email]')
    open(os.path.join(a.work, 'map-nav-test.log'), 'w', encoding='utf-8').write(out)
    keep = [l for l in out.splitlines() if re.search(r'CP_MAP|^OK \(|^Time:|FAILURES|Tests run|AssertionError|Reason|rendered', l)]
    print('\n'.join(keep[:40]))
    return 'OK (1 test)' in out


def cleanup(a):
    led = json.load(open(ledger_path(a.work)))
    ids = [f['id'] for f in led['fixtures'].values()]
    for i in ids:
        uuid.UUID(i)
    idlist = ','.join(f"'{i}'" for i in ids)
    sql(f"delete from public.report_history where report_id in ({idlist})", write=True)
    sql(f"delete from public.reports where id in ({idlist}) and description like '{PREFIX}%'", write=True)
    after = counts()
    ids_after = [r['id'] for r in sql("select id::text id from public.reports order by created_at desc, id desc")]
    ok = after == led['baseline_counts'] and ids_after == led['baseline_report_ids']
    led['status'] = 'cleaned' if ok else 'CLEANUP-MISMATCH'
    led['after_cleanup_counts'] = after
    json.dump(led, open(ledger_path(a.work), 'w'), indent=1)
    json.dump({'baseline': led['baseline_counts'], 'after': after, 'ids_equal': ids_after == led['baseline_report_ids']},
              open(os.path.join(a.work, 'map-nav-cleanup-verification.json'), 'w'), indent=1)
    print(json.dumps({'cleanup': led['status'], 'baseline': led['baseline_counts'], 'after': after}))
    return ok


def main():
    p = argparse.ArgumentParser()
    p.add_argument('cmd', choices=['prepare', 'run', 'cleanup', 'full'])
    p.add_argument('--credentials', required=True)
    p.add_argument('--work', required=True)
    p.add_argument('--serial', default='emulator-5556')
    p.add_argument('--filter-category', default='Garbage')
    a = p.parse_args()
    if not re.fullmatch(r'emulator-\d+', a.serial):
        raise SystemExit('Use an isolated emulator serial.')
    creds = json.load(open(a.credentials, encoding='utf-8-sig')); cfg = props()
    if a.cmd == 'prepare': prepare(a, creds, cfg)
    elif a.cmd == 'run': sys.exit(0 if run(a, creds) else 1)
    elif a.cmd == 'cleanup': sys.exit(0 if cleanup(a) else 1)
    else:
        prepare(a, creds, cfg)
        try: passed = run(a, creds)
        finally: cleaned = cleanup(a)
        sys.exit(0 if passed and cleaned else 1)


if __name__ == '__main__':
    main()
