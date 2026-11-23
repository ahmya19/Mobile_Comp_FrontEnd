#!/usr/bin/env python3
"""Guarded host driver for Batch 1A real Wi-Fi-bound HTTPS TUS original-upload smoke.

Safety invariants:
  * Refuses any credentials file without "fixture": true.
  * Refuses if either fixture identity has unexpected device registrations or protected demo devices drift.
  * Refuses physical serials unless --allow-physical-device is passed explicitly.
  * Captures /proc/net/dev cellular vs Wi-Fi byte counters before and after to verify no full original
    crossed cellular interfaces.
  * Independently verifies report row, compressed photo, private original download SHA-256 via ordinary
    fixture citizen session.
  * Removes ONLY the exact ledgered report (guarded single-line SQL), compressed object (ordinary DELETE),
    original object (trusted Storage API DELETE with secret key in memory only + GET 400/404 byte-absence
    verification), and any fixture device registrations. Verifies exact baseline counts/fingerprints and
    separate REST session logout outcomes.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cleanup_abandoned_uploads as cleaner  # noqa: E402
import hosted_safety as safety  # noqa: E402
from hosted_authz_probe import BASE, KEY, call, sign_in  # noqa: E402
from map_nav_hosted import ADB, PKG, REPO, one_line, shell_quote  # noqa: E402


def read_net_dev(serial):
    r = subprocess.run([ADB, '-s', serial, 'shell', 'cat /proc/net/dev'], capture_output=True, text=True, encoding='utf-8', errors='replace')
    stats = {}
    for line in (r.stdout or '').splitlines():
        if ':' not in line:
            continue
        iface, rest = line.split(':', 1)
        cols = rest.split()
        if len(cols) >= 9 and cols[0].isdigit() and cols[8].isdigit():
            stats[iface.strip()] = {'rx_bytes': int(cols[0]), 'tx_bytes': int(cols[8])}
    return stats


def net_delta(before, after):
    wifi_tx = 0
    cell_tx = 0
    per_iface = {}
    for iface, cur in after.items():
        prev = before.get(iface, {'rx_bytes': 0, 'tx_bytes': 0})
        dx = max(0, cur['tx_bytes'] - prev['tx_bytes'])
        dr = max(0, cur['rx_bytes'] - prev['rx_bytes'])
        if dx or dr:
            per_iface[iface] = {'rx_delta': dr, 'tx_delta': dx}
        if re.match(r'^(wlan|eth)', iface):
            wifi_tx += dx
        elif re.match(r'^(rmnet|ccmni|pdp|wwan|radio)', iface):
            cell_tx += dx
    return {'wifi_or_eth_tx_bytes': wifi_tx, 'cellular_tx_bytes': cell_tx, 'interfaces': per_iface}


def sql_run(query, write=False, cwd=REPO):
    cmd = ['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'db', 'query', '--remote', '--yes' if write else '--read-only', one_line(query)]
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding='utf-8', shell=False)
    text = r.stdout or ''
    start = text.find('{')
    if start < 0:
        raise RuntimeError('SQL failed: ' + (r.stderr or text)[:400])
    data = json.loads(text[start:])
    if 'error' in data:
        raise RuntimeError('SQL error: ' + json.dumps(data['error'])[:400])
    return data.get('rows', [])


def baseline_snapshot(cwd):
    q = """
        select
          (select count(*) from public.reports) as reports,
          (select count(*) from public.notifications) as notifications,
          (select count(*) from public.report_history) as history,
          (select count(*) from private.push_outbox) as outbox,
          (select count(*) from public.devices) as devices,
          (select count(*) from public.report_votes) as votes,
          (select count(*) from storage.objects where bucket_id='report-images') as compressed_objects,
          (select count(*) from storage.objects where bucket_id='report-originals') as original_objects,
          (select md5(coalesce(string_agg(to_jsonb(r)::text,',' order by id),'')) from public.reports r) as reports_md5,
          (select md5(coalesce(string_agg(to_jsonb(d)::text,',' order by installation_id),'')) from public.devices d) as devices_md5
    """
    row = sql_run(q, write=False, cwd=cwd)[0]
    return {k: (int(v) if k not in ('reports_md5', 'devices_md5') else v) for k, v in row.items()}


def main(argv=None):
    p = argparse.ArgumentParser(description='Run Batch 1A real Wi-Fi HTTPS TUS smoke with strict fixture guards.')
    p.add_argument('--serial', required=True)
    p.add_argument('--credentials', required=True)
    p.add_argument('--work', required=True)
    p.add_argument('--supabase-workdir', default=REPO)
    p.add_argument('--allow-physical-device', action='store_true')
    a = p.parse_args(argv)

    if not re.fullmatch(r'emulator-\d+', a.serial):
        if not (a.allow_physical_device and re.fullmatch(r'[A-Za-z0-9]{6,32}', a.serial)):
            raise SystemExit('Refusing non-emulator serial without --allow-physical-device.')

    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO:
        raise SystemExit('Keep --work outside the repository.')
    os.makedirs(work, exist_ok=True)

    creds = json.load(open(a.credentials, encoding='utf-8-sig'))
    if creds.get('fixture') is not True:
        raise SystemExit('Refusing non-fixture credentials file; use dedicated fixture identities.')

    cuid = creds['citizen']['uid']
    ouid = creds['official']['uid']
    marker = f'TUS1A-{uuid.uuid4().hex[:8]} real Wi-Fi resumable original smoke'

    before = baseline_snapshot(a.supabase_workdir)
    existing_fixture_devs = int(
        sql_run(f"select count(*) c from public.devices where user_id in ('{cuid}'::uuid,'{ouid}'::uuid)", cwd=a.supabase_workdir)[0]['c']
    )
    if existing_fixture_devs != 0:
        raise SystemExit(f'Refusing to start: fixture identities already have {existing_fixture_devs} device row(s).')

    ledger = safety.RunLedger(
        os.path.join(work, 'tus-smoke-ledger.json'),
        marker=marker,
        serial_kind='emulator' if a.serial.startswith('emulator-') else 'physical-phone',
        baseline_before=before,
    )
    sessions = safety.SessionRegistry(call)
    receipt = {}
    rid = None
    photo_path = None
    orig_path = None
    test_ok = False
    fixture_cleaned = False

    try:
        # Clear any stale receipt on device
        subprocess.run([ADB, '-s', a.serial, 'shell', f'run-as {PKG} rm -f files/tus-smoke-receipt.json'], capture_output=True)
        net_before = read_net_dev(a.serial)

        cmd = [
            'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.example.pinpoint.HostedOriginalTusSmokeTest#realWifiTusUploadInterruptResumeAndVerifyHash',
            '-e', 'allowHostedOriginalTusSmoke', 'true',
            '-e', 'fixture_email', creds['citizen']['email'],
            '-e', 'fixture_password', creds['citizen']['password'],
            '-e', 'marker', marker,
            f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner',
        ]
        inst = subprocess.run(
            [ADB, '-s', a.serial, 'shell', ' '.join(shell_quote(c) for c in cmd)],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300,
        )
        net_after = read_net_dev(a.serial)
        redacted_out = (inst.stdout or '').replace(creds['citizen']['password'], '[REDACTED]').replace(creds['citizen']['email'], '[FIXTURE-CITIZEN]')
        open(os.path.join(work, 'tus-smoke-instrumentation.log'), 'w', encoding='utf-8').write(redacted_out)

        raw_receipt = subprocess.run(
            [ADB, '-s', a.serial, 'exec-out', 'run-as', PKG, 'cat', 'files/tus-smoke-receipt.json'],
            capture_output=True, text=True, encoding='utf-8', errors='replace',
        ).stdout
        if raw_receipt.strip().startswith('{'):
            receipt = json.loads(raw_receipt)
            rid = receipt.get('report_id')
            photo_path = receipt.get('photo_path') or (f'{cuid}/{rid}/photo.jpg' if safety.valid_uuid(rid) else None)
            orig_path = receipt.get('original_path') or (f'{cuid}/{rid}/original' if safety.valid_uuid(rid) else None)
        subprocess.run([ADB, '-s', a.serial, 'shell', f'run-as {PKG} rm -f files/tus-smoke-receipt.json'], capture_output=True)

        delta = net_delta(net_before, net_after)
        ledger.update(instrumentation_ok=('OK (1 test)' in redacted_out), receipt=receipt, net_delta=delta)

        if 'OK (1 test)' in redacted_out and safety.valid_uuid(rid):
            rows = sql_run(
                f"select id::text id, owner_id::text owner, description, photo_path, original_path, status from public.reports where id='{rid}'::uuid",
                cwd=a.supabase_workdir,
            )
            assert len(rows) == 1, f'Expected 1 hosted report, got {len(rows)}'
            row = rows[0]
            assert row['owner'] == cuid and row['description'] == marker
            photo_path = row['photo_path']
            orig_path = row['original_path']
            assert photo_path == f'{cuid}/{rid}/photo.jpg'
            assert orig_path == f'{cuid}/{rid}/original'

            # Independently download the private original via ordinary fixture citizen session and compare SHA-256.
            ctok, _ = sign_in(creds['citizen']['email'], creds['citizen']['password'])
            sessions.add('citizen-verify', ctok)
            req = urllib.request.Request(
                f'{BASE}/storage/v1/object/authenticated/report-originals/{orig_path}',
                headers={'apikey': KEY, 'Authorization': f'Bearer {ctok}'},
            )
            with urllib.request.urlopen(req, timeout=60) as resp:
                dl_bytes = resp.read()
            host_sha = hashlib.sha256(dl_bytes).hexdigest()
            assert host_sha == receipt['original_sha256'], 'Host-downloaded original SHA mismatch'
            assert delta['cellular_tx_bytes'] < 100_000, f"Unexpected cellular TX bytes: {delta['cellular_tx_bytes']}"
            ledger.update(
                host_verification={
                    'report_found': True,
                    'photo_path_matches': True,
                    'original_path_matches': True,
                    'host_download_bytes': len(dl_bytes),
                    'host_download_sha256': host_sha,
                    'host_sha_matches_device_receipt': True,
                    'cellular_excluded': delta['cellular_tx_bytes'] < 100_000,
                }
            )
            test_ok = True
    except BaseException as exc:
        ledger.error('run', exc)
    finally:
        def clean_report():
            if not safety.valid_uuid(rid):
                return
            sql_run(
                f"begin; do $$ begin "
                f"if (select count(*) from public.reports where id='{rid}'::uuid and owner_id='{cuid}'::uuid and description='{marker}')<>1 then raise exception 'fixture identity mismatch'; end if; "
                f"if exists(select 1 from public.report_votes where report_id='{rid}'::uuid) or exists(select 1 from public.report_history where report_id='{rid}'::uuid) or exists(select 1 from public.notifications where report_id='{rid}'::uuid) or exists(select 1 from private.sms_receipts where report_id='{rid}'::uuid) then raise exception 'unexpected references'; end if; "
                f"delete from public.reports where id='{rid}'::uuid; end $$; commit;",
                write=True, cwd=a.supabase_workdir,
            )

        def clean_objects():
            secret = cleaner.fetch_secret_key(cwd=a.supabase_workdir)
            for bucket, path in (('report-images', photo_path), ('report-originals', orig_path)):
                if not path:
                    continue
                st, _ = cleaner.storage_api(BASE, 'DELETE', f'/storage/v1/object/{bucket}/{path}', secret)
                assert st in (200, 204, 400, 404), f'Storage delete {bucket}/{path} returned HTTP {st}'
                gst, _ = cleaner.storage_api(BASE, 'GET', f'/storage/v1/object/authenticated/{bucket}/{path}', secret)
                assert gst in (400, 404), f'Byte-absence verify failed for {bucket}/{path}: HTTP {gst}'

        def clean_fixture_devices():
            sql_run(
                f"begin; do $$ begin "
                f"if exists(select 1 from public.devices d join auth.users u on u.id=d.user_id where d.user_id in ('{cuid}'::uuid,'{ouid}'::uuid) and coalesce(u.raw_app_meta_data->>'pinpoint_fixture','')<>'true') then raise exception 'device cleanup refused'; end if; "
                f"delete from public.devices where user_id in ('{cuid}'::uuid,'{ouid}'::uuid); end $$; commit;",
                write=True, cwd=a.supabase_workdir,
            )

        safety.run_all(
            [('clean_report', clean_report), ('clean_objects', clean_objects), ('clean_fixture_devices', clean_fixture_devices)],
            ledger=ledger,
        )
        sessions.teardown()
        after = baseline_snapshot(a.supabase_workdir)
        fixture_cleaned = (after == before)
        ledger.update(
            baseline_after=after,
            baseline_preserved=fixture_cleaned,
            fixture_cleanup='cleaned' if fixture_cleaned else 'CLEANUP-MISMATCH',
            session_cleanup=sessions.summary(),
            sessions={r: {'outcome': o, 'detail': d} for r, (o, d) in sessions.outcomes.items()},
        )
        print(json.dumps({
            'test_ok': test_ok,
            'baseline_preserved': fixture_cleaned,
            'session_cleanup': sessions.summary(),
            'receipt': receipt,
            'net_delta': ledger.data.get('net_delta'),
            'host_verification': ledger.data.get('host_verification'),
        }, indent=1))

    return safety.final_exit(fixture_cleaned, sessions.summary(), test_ok)


if __name__ == '__main__':
    sys.exit(main())
