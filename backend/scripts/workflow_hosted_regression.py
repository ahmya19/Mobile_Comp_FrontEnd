#!/usr/bin/env python3
"""Guarded whole-app workflow regression on an isolated emulator, with independent server verification and cleanup.

Runs backend/scripts/run-emulator-workflow.ps1 (citizen form + photo + offline queue -> official status change and
history -> citizen notification and read state, all through the real UI and live Supabase) and then:
  * proves, with privileged read-only SQL and the ordinary citizen session, that exactly one new report appeared
    with the expected owner, category, status, history, notification and read flag;
  * downloads the stored photo and records its SHA-256, size and JPEG dimensions (the picker result is a generated
    image; Auth, queue, Storage and SQL are real);
  * removes ONLY the new rows, by exact ID, plus the photo through the ordinary API, and verifies that every
    baseline count, ID list and storage object name is restored.
The baseline and the intended scope are written to an exclusive private ledger BEFORE anything is created. A fresh
--work directory outside the repository is required. Tokens, passwords and private IDs are never printed.
"""
import argparse, hashlib, json, os, re, struct, subprocess, sys, time, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hosted_authz_probe import REPO, call, sign_in, sign_out_local, sql  # noqa: E402

TABLES = {
    'reports': 'select id::text k from public.reports',
    'notifications': 'select id::text k from public.notifications',
    'history': 'select id::text k from public.report_history',
    'outbox': 'select notification_id::text k from private.push_outbox',
    'votes': "select report_id::text||':'||user_id::text k from public.report_votes",
    'devices': "select user_id::text||':'||installation_id::text k from public.devices",
    'photo_objects': "select name k from storage.objects where bucket_id='report-images'",
    'original_objects': "select name k from storage.objects where bucket_id='report-originals'",
}


def snapshot():
    return {name: sorted(r['k'] for r in sql(q)) for name, q in TABLES.items()}


def jpeg_size(data):
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF: i += 1; continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2): return struct.unpack('>HH', data[i + 5:i + 9])[::-1]
        i += 2 + struct.unpack('>H', data[i + 2:i + 4])[0]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--credentials', required=True)
    ap.add_argument('--work', required=True)
    ap.add_argument('--serial', default='emulator-5556')
    a = ap.parse_args()
    if not re.fullmatch(r'emulator-\d+', a.serial): raise SystemExit('Use an isolated emulator serial.')
    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO: raise SystemExit('Use a private --work directory outside the repository.')
    os.makedirs(work, exist_ok=True)
    creds = json.load(open(a.credentials, encoding='utf-8-sig'))
    cuid = creds['citizen']['uid']
    baseline = snapshot()
    ledger_path = os.path.join(work, 'workflow-ledger.json')
    with open(ledger_path, 'x') as f:  # exclusive: refuses an earlier ledger
        json.dump({'intent': 'one citizen report with photo, one history row, one notification', 'started': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                   'baseline_counts': {k: len(v) for k, v in baseline.items()}, 'status': 'intent-recorded'}, f, indent=1)
    json.dump(baseline, open(os.path.join(work, 'workflow-baseline.json'), 'x'), indent=1)
    print('baseline:', {k: len(v) for k, v in baseline.items()})

    run = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                          os.path.join(REPO, 'backend', 'scripts', 'run-emulator-workflow.ps1'),
                          '-EmulatorSerial', a.serial, '-CredentialFile', a.credentials],
                         capture_output=True, text=True, encoding='utf-8')
    log = run.stdout + run.stderr
    for role in ('citizen', 'official'):
        log = log.replace(creds[role]['password'], '[redacted password]').replace(creds[role]['email'], '[demo email]')
    open(os.path.join(work, 'workflow-run.log'), 'w', encoding='utf-8').write(log)
    phases = re.findall(r'CP-16 phase: (\w+)[\s\S]*?Time: ([0-9.]+)\s+OK \(1 test\)', log)
    print('runner exit', run.returncode, '| phases passed:', phases)

    after = snapshot()
    new = {k: sorted(set(after[k]) - set(baseline[k])) for k in baseline}
    removed = {k: sorted(set(baseline[k]) - set(after[k])) for k in baseline}
    print('new rows:', {k: len(v) for k, v in new.items() if v}, '| rows removed by the run:', {k: len(v) for k, v in removed.items() if v})
    ok = run.returncode == 0 and len(phases) == 3
    report_id = None
    cleanup_ok = False
    try:
        if len(new['reports']) == 1:
            report_id = new['reports'][0]; uuid.UUID(report_id)
            row = sql(f"select owner_id::text owner, category, status, photo_path, original_path, source, description like 'CP16 %' labelled from public.reports where id='{report_id}'")[0]
            hist = sql(f"select previous_status, status from public.report_history where report_id='{report_id}'")
            note = sql(f"select id::text id, read from public.notifications where report_id='{report_id}'")
            outbox = sql(f"select delivered_at is not null delivered, attempts from private.push_outbox where notification_id in (select id from public.notifications where report_id='{report_id}')")
            checks = {
                'owner is the demo citizen': row['owner'] == cuid, 'labelled CP16 fixture': row['labelled'] is True,
                'category Water Interruption': row['category'] == 'Water Interruption', 'status Under Review': row['status'] == 'Under Review',
                'photo path bound to owner and report': row['photo_path'] == f'{cuid}/{report_id}/photo.jpg', 'no original': row['original_path'] is None,
                'source app': row['source'] == 'app', 'one history row Pending->Under Review': hist == [{'previous_status': 'Pending', 'status': 'Under Review'}],
                'one notification, read after opening': len(note) == 1 and note[0]['read'] is True,
            }
            ctok, _ = sign_in(creds['citizen']['email'], creds['citizen']['password'])
            # call() decodes text, so fetch the raw bytes directly for an exact hash.
            import urllib.request
            from hosted_authz_probe import BASE, KEY
            req = urllib.request.Request(f"{BASE}/storage/v1/object/authenticated/report-images/{row['photo_path']}", headers={'apikey': KEY, 'Authorization': f'Bearer {ctok}'})
            data = urllib.request.urlopen(req, timeout=30).read()
            size = jpeg_size(data)
            checks.update({'photo is a JPEG': data[:3] == b'\xff\xd8\xff', 'photo within 250 KB': 0 < len(data) <= 256000})
            evidence = {'photo_sha256': hashlib.sha256(data).hexdigest(), 'photo_bytes': len(data), 'photo_dimensions': size,
                        'outbox': outbox, 'history': hist, 'checks': checks}
            json.dump(evidence, open(os.path.join(work, 'workflow-verification.json'), 'w'), indent=1)
            for name, passed in checks.items(): print(('PASS ' if passed else 'FAIL ') + name)
            print('photo:', evidence['photo_bytes'], 'bytes, sha256', evidence['photo_sha256'][:16] + '...', 'dimensions', size)
            ok = ok and all(checks.values())
            # ---- cleanup, scoped by exact IDs
            ids = {'report': report_id, 'notifications': [n['id'] for n in note]}
            json.dump({'report': report_id, 'notifications': ids['notifications'], 'photo': row['photo_path'], 'status': 'verified-pending-cleanup'}, open(ledger_path, 'w'), indent=1)
            nlist = ','.join(f"'{uuid.UUID(n)}'" for n in ids['notifications']) or "'00000000-0000-0000-0000-000000000000'"
            for stmt in (f"delete from public.notifications where id in ({nlist}) and report_id='{report_id}'",
                         f"delete from public.report_history where report_id='{report_id}'",
                         f"delete from public.reports where id='{report_id}' and owner_id='{cuid}' and description like 'CP16 %'"):
                subprocess.run(['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'db', 'query', '--remote', '--yes', stmt], cwd=REPO, capture_output=True, text=True)
            s, _ = call('DELETE', f"/storage/v1/object/report-images/{row['photo_path']}", token=ctok)
            print('photo delete via the ordinary API: HTTP', s)
            sign_out_local(ctok)
    finally:
        # Any other new row (e.g. an emulator device registration) is reported, never deleted by prefix.
        final = snapshot()
        diff = {k: {'extra': sorted(set(final[k]) - set(baseline[k])), 'missing': sorted(set(baseline[k]) - set(final[k]))} for k in baseline}
        leftovers = {k: {kk: len(vv) for kk, vv in d.items() if vv} for k, d in diff.items() if d['extra'] or d['missing']}
        cleanup_ok = not leftovers
        json.dump({'ok_run': ok, 'cleanup_ok': cleanup_ok, 'leftover_counts': leftovers, 'final_counts': {k: len(v) for k, v in final.items()},
                   'extra_ids': {k: d['extra'] for k, d in diff.items() if d['extra']}}, open(os.path.join(work, 'workflow-cleanup.json'), 'w'), indent=1)
        print('final counts:', {k: len(v) for k, v in final.items()}, '| leftovers vs baseline:', leftovers or 'none')
    return 0 if ok and cleanup_ok else 1


if __name__ == '__main__':
    sys.exit(main())
