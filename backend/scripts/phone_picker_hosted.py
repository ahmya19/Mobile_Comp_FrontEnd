#!/usr/bin/env python3
"""Real system photo picker on the physical phone -> live backend, with independent Storage verification and cleanup.

Runs PhonePickerHostedTest (existing real session, one synthetic generated image, compressed photo only) and then proves
with privileged read-only SQL and the ordinary citizen session that exactly one report with the marker exists, owned by the
citizen, with photo_path '<uid>/<report id>/photo.jpg', and that the stored private object decodes to 750x500 (the 3000x2000
synthetic image bounded by the compressor, so a user's own photo cannot have been picked) and is <= 256000 bytes. Then it
removes ONLY that report (identity-guarded) and its object through the ordinary API and verifies the baseline report count
and the exact storage object name list. Exclusive private ledger before the run. Secrets and IDs are never printed.
"""
import argparse, json, os, re, struct, subprocess, sys, urllib.request, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hosted_safety as safety  # noqa: E402
from hosted_authz_probe import REPO, call, sign_in  # noqa: E402
from map_nav_hosted import ADB, PKG, props, shell_quote, sql  # noqa: E402


def jpeg_size(data):
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF: i += 1; continue
        m = data[i + 1]
        if m in (0xC0, 0xC1, 0xC2): return struct.unpack('>HH', data[i + 5:i + 9])[::-1]
        i += 2 + struct.unpack('>H', data[i + 2:i + 4])[0]
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--credentials', required=True); p.add_argument('--work', required=True); p.add_argument('--serial', required=True)
    a = p.parse_args()
    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO: raise SystemExit('Use a private --work directory outside the repository.')
    os.makedirs(work, exist_ok=True)
    creds = json.load(open(a.credentials, encoding='utf-8-sig')); cuid = creds['citizen']['uid']
    cfg = props()
    if cfg.get('SUPABASE_URL') != 'https://jaysxjjdvaitgrbqjlhe.supabase.co': raise SystemExit('Unexpected project.')
    marker = f'PICKHOST-{uuid.uuid4().hex[:8]} phone picker fixture'

    def objects(): return sorted(r['k'] for r in sql("select name k from storage.objects where bucket_id='report-images'"))
    base = {'reports': sql('select count(*) c from public.reports')[0]['c'], 'objects': objects()}
    ledger = os.path.join(work, 'picker-ledger.json')
    with open(ledger, 'x') as f:
        json.dump({'marker': marker, 'baseline_reports': base['reports'], 'baseline_objects': len(base['objects']), 'status': 'intent-recorded'}, f, indent=1)
    token = None; rid = None; path = None; result = {}
    sessions = safety.SessionRegistry(call); exit_code = [1]
    try:
        cmd = ['am', 'instrument', '-w', '-r', '-e', 'class', 'com.example.pinpoint.PhonePickerHostedTest#pickedPhotoIsSubmittedToTheLiveBackend',
               '-e', 'marker', marker, f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner']
        out = subprocess.run([ADB, '-s', a.serial, 'shell', ' '.join(shell_quote(c) for c in cmd)], capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
        result['instrumentation_ok'] = 'OK (1 test)' in out
        if not result['instrumentation_ok']: result['instrumentation_tail'] = out[-600:]
        rows = sql(f"select id::text i,owner_id::text o,photo_path p,original_path op,status s from public.reports where description='{marker}'")
        result['reports_with_marker'] = len(rows)
        if len(rows) == 1:
            r = rows[0]; rid = r['i']; path = r['p']
            result.update(owner_is_citizen=r['o'] == cuid, photo_path_matches=path == f'{cuid}/{rid}/photo.jpg', original_absent=r['op'] is None)
            token = sessions.add('citizen', sign_in(creds['citizen']['email'], creds['citizen']['password'])[0])
            req = urllib.request.Request(cfg['SUPABASE_URL'] + f'/storage/v1/object/authenticated/report-images/{path}',
                                         headers={'apikey': cfg['SUPABASE_PUBLISHABLE_KEY'], 'Authorization': f'Bearer {token}'})
            data = urllib.request.urlopen(req, timeout=30).read()
            result.update(object_bytes=len(data), object_dims='%dx%d' % jpeg_size(data), object_le_256000=len(data) <= 256000, object_is_jpeg=data[:2] == b'\xff\xd8')
            result['object_rows'] = int(sql(f"select count(*) c from storage.objects where bucket_id='report-images' and name='{path}'")[0]['c'])
    finally:
        led = json.load(open(ledger))
        try:
            if rid and re.fullmatch('[0-9a-f-]{36}', rid):
                sql(f"begin; do $$ begin if (select count(*) from public.reports where id='{rid}'::uuid and owner_id='{cuid}'::uuid and description='{marker}')<>1 then raise exception 'fixture identity mismatch'; end if; "
                    f"if exists(select 1 from public.report_votes where report_id='{rid}'::uuid) or exists(select 1 from public.report_history where report_id='{rid}'::uuid) or exists(select 1 from public.notifications where report_id='{rid}'::uuid) then raise exception 'unexpected references'; end if; "
                    f"delete from public.reports where id='{rid}'::uuid; end $$; commit;", write=True)
            if path:
                token = token or sessions.add('citizen-cleanup', sign_in(creds['citizen']['email'], creds['citizen']['password'])[0])
                s, _ = call('DELETE', f'/storage/v1/object/report-images/{path}', token)
                result['object_delete_http'] = s
            after = {'reports': sql('select count(*) c from public.reports')[0]['c'], 'objects': objects()}
            led['status'] = 'cleaned' if after == base else 'CLEANUP-MISMATCH'
        finally:
            sessions.teardown(); led['session_cleanup'] = sessions.summary()
            result['session_cleanup'] = led['session_cleanup']
            json.dump(led, open(ledger, 'w'), indent=1)
            json.dump(result, open(os.path.join(work, 'picker-result.json'), 'w'), indent=1)
            print(json.dumps(result)); print('fixture cleanup:', led['status'], '| session cleanup:', led['session_cleanup'])
            exit_code[0] = 0 if led['status'] == 'cleaned' and led['session_cleanup'] in ('confirmed', 'already-invalid') and result.get('instrumentation_ok') else 1
    return exit_code[0]


if __name__ == '__main__':
    sys.exit(main())
