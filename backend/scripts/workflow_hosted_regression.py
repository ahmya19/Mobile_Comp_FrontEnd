#!/usr/bin/env python3
"""Guarded whole-app workflow regression on an isolated emulator, with independent server verification and cleanup.

Runs backend/scripts/run-emulator-workflow.ps1 (citizen form + photo + offline queue -> official status change and
history -> citizen notification and read state, all through the real UI and live Supabase) and then:
  * proves the identity of the report this run created from a fresh ON-DEVICE RECEIPT written by the test itself
    (report id + unique description), cleared before the run, and requires the baseline difference to be exactly that
    one report. "Exactly one new report" alone is never ownership proof;
  * verifies owner, label, category, status, history, notification, photo binding and the stored photo bytes;
  * removes ONLY that report, its recorded notifications and history, in ONE guarded transaction that re-verifies the
    identity and every dependent reference under a row lock and refuses (deleting nothing) on any mismatch; then deletes
    the object through the ordinary Storage API and verifies it is gone;
  * reports FIXTURE cleanup and SESSION cleanup separately. Owned sessions are always torn down (local scope), even when
    verification, evidence writing or cleanup raises; an unresolved logout is never reported as cleaned.
The ledger (identifiers and outcomes, never tokens) is exclusive and written before anything is created; a failed or
interrupted run can be finished with --cleanup-only using the same guards. Tokens, passwords and private IDs are never
printed. A fresh --work directory outside the repository is required.
"""
import argparse, hashlib, json, os, re, struct, subprocess, sys, time, urllib.request, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hosted_safety as safety  # noqa: E402
from hosted_authz_probe import BASE, KEY, REPO, call, sign_in  # noqa: E402
from map_nav_hosted import sql  # noqa: E402  (read-only by default; write=True sends one --yes statement)

PKG = 'com.example.pinpoint'
ADB = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk', 'platform-tools', 'adb.exe')
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


def adb(serial, *args):
    return subprocess.run([ADB, '-s', serial, *args], capture_output=True, text=True, encoding='utf-8', errors='replace')


def clear_receipt(serial):
    """Remove the previous run's on-device receipt so a surviving one can only come from THIS run. Raises if not provable."""
    adb(serial, 'shell', f'run-as {PKG} rm -f shared_prefs/checkpoint_rehearsal.xml')
    if read_receipt(serial) != {}: raise RuntimeError('Could not clear the previous on-device receipt; refusing to run.')


def read_receipt(serial):
    out = adb(serial, 'shell', f'run-as {PKG} cat shared_prefs/checkpoint_rehearsal.xml')
    text = out.stdout if out.returncode == 0 else ''
    return {m.group(1): m.group(2).replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
            for m in re.finditer(r'<string name="(report_id|description)">([^<]*)</string>', text)}


def cleanup_sql(report_id, owner, description, photo_path, notification_ids, history_count):
    """ONE transaction. Every identity and reference check runs under a lock on the report row and raises (rolling
    back, deleting nothing) on any mismatch. No comments and a single logical line per statement set (npx.cmd hazard)."""
    for value in (report_id, owner, *notification_ids):
        if not safety.valid_uuid(value): raise ValueError('Invalid identifier in cleanup scope.')
    if not re.fullmatch(r'CP16 [0-9a-f-]{36}: water service issue\.', description): raise ValueError('Unexpected fixture description.')
    if photo_path != f'{owner}/{report_id}/photo.jpg': raise ValueError('Photo path is not bound to this owner and report.')
    if not (isinstance(history_count, int) and 0 <= history_count <= 5): raise ValueError('Unexpected history count.')
    ids = ','.join(f"'{n}'" for n in notification_ids) or "'00000000-0000-0000-0000-000000000000'"
    desc = description.replace("'", "''")
    return (f"begin; do $$ begin "
            f"perform 1 from public.reports where id='{report_id}' for update; "
            f"if (select count(*) from public.reports where id='{report_id}' and owner_id='{owner}' and description='{desc}' "
            f"and photo_path='{photo_path}' and original_path is null and source='app' and vote_count=0)<>1 "
            f"then raise exception 'Fixture identity mismatch; cleanup refused'; end if; "
            f"if (select count(*) from public.notifications where report_id='{report_id}')<>{len(notification_ids)} "
            f"or exists(select 1 from public.notifications where report_id='{report_id}' and (user_id<>'{owner}' or id not in ({ids}))) "
            f"then raise exception 'Unexpected notification references; cleanup refused'; end if; "
            f"if (select count(*) from public.report_history where report_id='{report_id}')<>{history_count} "
            f"then raise exception 'Unexpected history references; cleanup refused'; end if; "
            f"if exists(select 1 from public.report_votes where report_id='{report_id}') "
            f"or exists(select 1 from private.sms_receipts where report_id='{report_id}') "
            f"then raise exception 'Unexpected vote or SMS references; cleanup refused'; end if; "
            f"delete from public.notifications where report_id='{report_id}'; "
            f"delete from public.report_history where report_id='{report_id}'; "
            f"delete from public.reports where id='{report_id}'; end $$; commit;")


def fetch_object(path, token):
    req = urllib.request.Request(f"{BASE}/storage/v1/object/authenticated/report-images/{path}", headers={'apikey': KEY, 'Authorization': f'Bearer {token}'})
    return urllib.request.urlopen(req, timeout=30).read()


def verify_identity(receipt, new_reports, cuid):
    """Return (identity|None, reason). Ownership comes from the on-device receipt AND the hosted row, never from a count."""
    rid, desc = receipt.get('report_id'), receipt.get('description')
    if not safety.valid_uuid(rid) or not desc: return None, 'no fresh on-device receipt for this run'
    if new_reports != [rid]: return None, 'new hosted reports do not equal the receipt report (unrelated or missing report)'
    rows = sql(f"select owner_id::text owner, description, photo_path from public.reports where id='{rid}'")
    if len(rows) != 1: return None, 'receipt report not found on the host'
    row = rows[0]
    if row['owner'] != cuid: return None, 'report owner is not the demo citizen'
    if row['description'] != desc or not re.fullmatch(r'CP16 [0-9a-f-]{36}: water service issue\.', desc): return None, 'run marker does not match'
    if row['photo_path'] != f'{cuid}/{rid}/photo.jpg': return None, 'photo path is not bound to owner and report'
    return {'report': rid, 'description': desc, 'photo': row['photo_path']}, 'ok'


def run_cleanup(ledger, cuid, sessions):
    """Guarded cleanup from ledger scope. Returns nothing; records outcomes in the ledger. Never deletes without identity."""
    d = ledger.data; ident = d.get('identity')
    if not ident: ledger.update(fixture_cleanup='REFUSED'); return
    notes = [r['id'] for r in sql(f"select id::text id from public.notifications where report_id='{ident['report']}'")]
    hist = int(sql(f"select count(*) c from public.report_history where report_id='{ident['report']}'")[0]['c'])
    ledger.update(dependents={'notifications': notes, 'history_rows': hist}, fixture_cleanup='pending-delete')
    try:
        sql(cleanup_sql(ident['report'], cuid, ident['description'], ident['photo'], notes, hist), write=True)
    except SystemExit as error:
        ledger.update(fixture_cleanup='REFUSED-by-guard', cleanup_error=str(error)[:300]); return
    gone = int(sql(f"select count(*) c from public.reports where id='{ident['report']}'")[0]['c']) == 0
    ledger.update(fixture_cleanup='report-removed' if gone else 'FAILED-report-still-present')


def run_object_cleanup(ledger, creds, sessions):
    ident = ledger.data.get('identity')
    if not ident or ledger.data.get('fixture_cleanup') != 'report-removed': return
    present = int(sql(f"select count(*) c from storage.objects where bucket_id='report-images' and name='{ident['photo']}'")[0]['c'])
    if present:
        token = sessions.add('citizen-cleanup', sign_in(creds['citizen']['email'], creds['citizen']['password'])[0])
        status, _ = call('DELETE', f"/storage/v1/object/report-images/{ident['photo']}", token=token)
        ledger.update(object_delete_http=status)
        present = int(sql(f"select count(*) c from storage.objects where bucket_id='report-images' and name='{ident['photo']}'")[0]['c'])
    ledger.update(object_cleanup='absent' if not present else 'FAILED-object-still-present',
                  fixture_cleanup='cleaned' if not present else 'FAILED-object-still-present')


def leftovers_vs(baseline):
    final = snapshot()
    diff = {k: {'extra': sorted(set(final[k]) - set(baseline[k])), 'missing': sorted(set(baseline[k]) - set(final[k]))} for k in baseline}
    return final, {k: {kk: len(vv) for kk, vv in d.items() if vv} for k, d in diff.items() if d['extra'] or d['missing']}, diff


def execute(a, creds):
    work = a.work; cuid = creds['citizen']['uid']
    ledger = safety.RunLedger(os.path.join(work, 'workflow-ledger.json'), intent='one citizen report with photo, one history row, one notification',
                              serial=a.serial, citizen_uid=cuid)
    sessions = safety.SessionRegistry(call)
    test_ok, baseline, failure = False, None, None
    try:
        baseline = snapshot()
        json.dump(baseline, open(os.path.join(work, 'workflow-baseline.json'), 'x'), indent=1)
        ledger.update(baseline_counts={k: len(v) for k, v in baseline.items()}, status='intent-recorded')
        print('baseline:', {k: len(v) for k, v in baseline.items()})
        clear_receipt(a.serial)
        run = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', os.path.join(REPO, 'backend', 'scripts', 'run-emulator-workflow.ps1'),
                              '-EmulatorSerial', a.serial, '-CredentialFile', a.credentials], capture_output=True, text=True, encoding='utf-8')
        log = run.stdout + run.stderr
        for role in ('citizen', 'official'):
            log = log.replace(creds[role]['password'], '[redacted password]').replace(creds[role]['email'], '[demo email]')
        open(os.path.join(work, 'workflow-run.log'), 'w', encoding='utf-8').write(log)
        phases = re.findall(r'CP-16 phase: (\w+)[\s\S]*?Time: ([0-9.]+)\s+OK \(1 test\)', log)
        print('runner exit', run.returncode, '| phases passed:', phases)
        after = snapshot()
        new = {k: sorted(set(after[k]) - set(baseline[k])) for k in baseline}
        removed = {k: sorted(set(baseline[k]) - set(after[k])) for k in baseline}
        ledger.update(new_ids=new, removed_ids=removed, runner_exit=run.returncode, phases_passed=len(phases))
        ok = run.returncode == 0 and len(phases) == 3 and not any(removed.values())
        identity, reason = verify_identity(read_receipt(a.serial), new['reports'], cuid)
        if identity is None:
            ledger.update(fixture_cleanup='REFUSED', refusal=reason)
            print('identity not proven; nothing will be deleted:', reason, '| unreviewed new IDs recorded in the ledger')
            ok = False
        else:
            ledger.update(identity=identity, status='identity-proven')   # recorded BEFORE any further step that can raise
            rid = identity['report']
            row = sql(f"select category, status, original_path, source from public.reports where id='{rid}'")[0]
            hist = sql(f"select previous_status, status from public.report_history where report_id='{rid}'")
            note = sql(f"select id::text id, read from public.notifications where report_id='{rid}'")
            outbox = sql(f"select delivered_at is not null delivered, attempts from private.push_outbox where notification_id in (select id from public.notifications where report_id='{rid}')")
            checks = {'category Water Interruption': row['category'] == 'Water Interruption', 'status Under Review': row['status'] == 'Under Review',
                      'no original': row['original_path'] is None, 'source app': row['source'] == 'app',
                      'one history row Pending->Under Review': hist == [{'previous_status': 'Pending', 'status': 'Under Review'}],
                      'one notification, read after opening': len(note) == 1 and note[0]['read'] is True}
            token = sessions.add('citizen', sign_in(creds['citizen']['email'], creds['citizen']['password'])[0])
            data = fetch_object(identity['photo'], token)
            checks.update({'photo is a JPEG': data[:3] == b'\xff\xd8\xff', 'photo within 250 KB': 0 < len(data) <= 256000})
            evidence = {'photo_sha256': hashlib.sha256(data).hexdigest(), 'photo_bytes': len(data), 'photo_dimensions': jpeg_size(data),
                        'outbox': outbox, 'history': hist, 'checks': checks}
            json.dump(evidence, open(os.path.join(work, 'workflow-verification.json'), 'w'), indent=1)
            for name, passed in checks.items(): print(('PASS ' if passed else 'FAIL ') + name)
            ok = ok and all(checks.values())
        test_ok = ok
    except BaseException as error:  # keep the ORIGINAL failure; cleanup below must not hide or replace it
        failure = error
        try: ledger.error('run', error)
        except Exception: pass
    finally:
        steps = [('report-cleanup', lambda: run_cleanup(ledger, cuid, sessions)),
                 ('object-cleanup', lambda: run_object_cleanup(ledger, creds, sessions))]
        results = safety.run_all(steps, ledger)
        fixture_state = ledger.data.get('fixture_cleanup')
        if fixture_state == 'not-started': ledger.update(fixture_cleanup='nothing-created-or-unproven')
        leftovers = {}
        if baseline is not None:
            try:
                final, leftovers, diff = leftovers_vs(baseline)
                json.dump({'leftover_counts': leftovers, 'final_counts': {k: len(v) for k, v in final.items()},
                           'extra_ids': {k: d['extra'] for k, d in diff.items() if d['extra']}}, open(os.path.join(work, 'workflow-cleanup.json'), 'w'), indent=1)
            except BaseException as error:
                leftovers = {'unverified': {'final-snapshot-failed': 1}}; ledger.error('final-snapshot', error)
        sessions.teardown()
        ledger.update(session_cleanup=sessions.summary(), sessions={r: {'outcome': o, 'detail': d} for r, (o, d) in sessions.outcomes.items()},
                      leftover_counts=leftovers, test_ok=test_ok)
        fixture_ok = ledger.data.get('fixture_cleanup') in ('cleaned', 'nothing-created-or-unproven') and not leftovers
        print('fixture cleanup:', ledger.data.get('fixture_cleanup'), '| leftovers vs baseline:', leftovers or 'none')
        print('session cleanup:', ledger.data['session_cleanup'], {r: o for r, (o, _) in sessions.outcomes.items()})
    if failure is not None and not isinstance(failure, SystemExit): print('run failed:', type(failure).__name__)
    return safety.final_exit(fixture_ok, ledger.data['session_cleanup'], test_ok)


def cleanup_only(a, creds):
    path = os.path.join(a.work, 'workflow-ledger.json')
    ledger = safety.RunLedger.load(path); cuid = creds['citizen']['uid']
    if ledger.data.get('citizen_uid') != cuid: raise SystemExit('Ledger belongs to another account; refusing.')
    sessions = safety.SessionRegistry(call)
    try:
        safety.run_all([('report-cleanup', lambda: run_cleanup(ledger, cuid, sessions)), ('object-cleanup', lambda: run_object_cleanup(ledger, creds, sessions))], ledger)
    finally:
        sessions.teardown()
        ledger.update(session_cleanup=sessions.summary(), sessions={r: {'outcome': o, 'detail': d} for r, (o, d) in sessions.outcomes.items()})
    print('fixture cleanup:', ledger.data.get('fixture_cleanup'), '| session cleanup:', ledger.data['session_cleanup'])
    return safety.final_exit(ledger.data.get('fixture_cleanup') == 'cleaned', ledger.data['session_cleanup'], True)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--credentials', required=True)
    ap.add_argument('--work', required=True)
    ap.add_argument('--serial', default='emulator-5556')
    ap.add_argument('--cleanup-only', action='store_true', help='finish a failed/interrupted run from its ledger, with the same guards')
    a = ap.parse_args(argv)
    if not re.fullmatch(r'emulator-\d+', a.serial): raise SystemExit('Use an isolated emulator serial.')
    a.work = os.path.abspath(a.work)
    if os.path.commonpath([a.work, REPO]) == REPO: raise SystemExit('Use a private --work directory outside the repository.')
    os.makedirs(a.work, exist_ok=True)
    creds = json.load(open(a.credentials, encoding='utf-8-sig'))
    return cleanup_only(a, creds) if a.cleanup_only else execute(a, creds)


if __name__ == '__main__':
    sys.exit(main())
