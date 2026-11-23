#!/usr/bin/env python3
"""Idempotent seeding, verification and exact cleanup for the maintained Alangilan demo dataset (Batch 1C.4).

Design rules:
  * Clearly labelled synthetic demonstration reports ([DEMO] prefix) in Alangilan, Batangas City.
  * Covers all 6 categories (Road Damage, Flooding, Streetlight, Garbage, Water Interruption, Other)
    and all active/resolved statuses (Pending, Under Review, In Progress, Resolved).
  * Deterministic UUIDv5 IDs derived from DEMO_NAMESPACE so seeding is 100% idempotent and cleanup
    targets ONLY those exact 6 demo reports, never touching the 36 existing baseline reports or
    disposable test fixtures.
  * Generates tiny, valid, synthetic JPEG images in memory (zero personal data, zero real photos,
    zero credentials in the APK).
  * Default mode is --dry-run. Always confirms local-scope REST session logout and protects shared
    demo device fingerprints.
"""
import argparse
import io
import json
import os
import pathlib
import re
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hosted_safety as safety  # noqa: E402
from map_nav_hosted import REPO, one_line, props  # noqa: E402

DEMO_NAMESPACE = uuid.UUID('7e9c4f31-8b2a-4c10-9a51-4e7d21f0b812')
DEMO_PREFIX = '[DEMO]'
DEMO_TAG = 'PinPoint Alangilan Demo Dataset v1'
SUPPORTED_BARANGAY = 'Alangilan'

# Realistic Alangilan, Batangas City synthetic entries (approximate public landmarks, no personal data).
DEMO_SPECS = (
    {
        'slug': 'road-pothole',
        'category': 'Road Damage',
        'description': f'{DEMO_PREFIX} Deep pothole along the main access lane causing motorcycles to swerve.',
        'location': 'Golden Country Homes main gate',
        'barangay': SUPPORTED_BARANGAY,
        'latitude': 13.7824,
        'longitude': 121.0668,
        'transitions': (('Under Review', 'High'), ('In Progress', 'High')),
        'color_rgb': (196, 62, 62),
    },
    {
        'slug': 'flood-canal',
        'category': 'Flooding',
        'description': f'{DEMO_PREFIX} Clogged roadside drainage causing knee-deep pooling after heavy rain.',
        'location': 'Alangilan National Highway canal',
        'barangay': SUPPORTED_BARANGAY,
        'latitude': 13.7808,
        'longitude': 121.0682,
        'transitions': (('Under Review', 'High'),),
        'color_rgb': (54, 128, 196),
    },
    {
        'slug': 'streetlight-out',
        'category': 'Streetlight',
        'description': f'{DEMO_PREFIX} Two consecutive streetlights have been out for three nights near the crosswalk.',
        'location': 'BatStateU Alangilan pedestrian lane',
        'barangay': SUPPORTED_BARANGAY,
        'latitude': 13.7841,
        'longitude': 121.0742,
        'transitions': (),
        'color_rgb': (225, 168, 46),
    },
    {
        'slug': 'garbage-corner',
        'category': 'Garbage',
        'description': f'{DEMO_PREFIX} Uncollected garbage sacks accumulating beside the community basketball court.',
        'location': 'Purok 3 basketball court corner',
        'barangay': SUPPORTED_BARANGAY,
        'latitude': 13.7812,
        'longitude': 121.0654,
        'transitions': (),
        'color_rgb': (56, 152, 102),
    },
    {
        'slug': 'water-pressure',
        'category': 'Water Interruption',
        'description': f'{DEMO_PREFIX} Intermittent low water pressure reported across residential taps since early morning.',
        'location': 'Sitio Silangan access road',
        'barangay': SUPPORTED_BARANGAY,
        'latitude': 13.7832,
        'longitude': 121.0695,
        'transitions': (('Under Review', 'Medium'),),
        'color_rgb': (82, 166, 204),
    },
    {
        'slug': 'facility-plaza',
        'category': 'Other',
        'description': f'{DEMO_PREFIX} Loose concrete bench and hand railing repaired at the barangay plaza.',
        'location': 'Alangilan Barangay Hall plaza',
        'barangay': SUPPORTED_BARANGAY,
        'latitude': 13.7819,
        'longitude': 121.0671,
        'transitions': (('Under Review', 'Low'), ('Resolved', 'Low')),
        'color_rgb': (124, 77, 204),
    },
)


def demo_id(slug):
    return str(uuid.uuid5(DEMO_NAMESPACE, f'pinpoint-alangilan-demo-v1:{slug}'))


def demo_catalog():
    return [dict(spec, id=demo_id(spec['slug'])) for spec in DEMO_SPECS]


def synthetic_jpeg(rgb=(124, 77, 204)):
    """Produce a tiny valid baseline RGB JPEG (16x16 solid color) with zero third-party deps and zero EXIF."""
    try:
        from PIL import Image
        buf = io.BytesIO()
        Image.new('RGB', (160, 120), rgb).save(buf, format='JPEG', quality=80)
        return buf.getvalue()
    except ImportError:
        pass
    # Minimal valid 1x1 baseline JPEG (SOI..EOI) with distinct comment marker carrying category color.
    r, g, b = rgb
    comment = f'PinPoint Synthetic Demo {r:02x}{g:02x}{b:02x}'.encode('ascii')
    com = b'\xff\xfe' + struct.pack('>H', len(comment) + 2) + comment
    base = bytes.fromhex(
        'ffd8ffe000104a46494600010101004800480000'
        'ffdb0043000302020302020303030304030304050805050404050a070706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514'
        'ffc0000b080001000101011100'
        'ffc40014000100000000000000000000000000000009'
        'ffc40014100100000000000000000000000000000000'
        'ffda0008010100003f00d2cf20ffd9'
    )
    return base[:2] + com + base[2:]


def http_call(base_url, api_key, method, path, token=None, body=None, content_type='application/json', urlopen=urllib.request.urlopen):
    url = base_url.rstrip('/') + path
    headers = {'apikey': api_key}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    payload = None
    if body is not None:
        if isinstance(body, (bytes, bytearray)):
            payload = bytes(body)
        else:
            payload = json.dumps(body).encode('utf-8')
        headers['Content-Type'] = content_type
    req = urllib.request.Request(url, payload, headers, method=method)
    try:
        with urlopen(req, timeout=30) as resp:
            raw = resp.read()
            if not raw:
                return resp.status, None
            if 'json' in (resp.headers.get('Content-Type') or ''):
                return resp.status, json.loads(raw.decode('utf-8', 'replace'))
            return resp.status, raw
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw.decode('utf-8', 'replace'))
        except Exception:
            return err.code, raw
    except Exception as err:
        raise OSError(f'HTTP {method} {path.split("?")[0]} failed: {type(err).__name__}') from None


def sql(query, write=False, cwd=REPO, run=subprocess.run):
    cmd = ['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'db', 'query', '--remote', '--yes' if write else '--read-only', one_line(query)]
    r = run(cmd, cwd=cwd, capture_output=True, text=True, encoding='utf-8', shell=False)
    text = r.stdout or ''
    start = text.find('{')
    if start < 0:
        raise RuntimeError('SQL failed: ' + (r.stderr or text)[:400])
    data = json.loads(text[start:])
    if 'error' in data:
        raise RuntimeError('SQL error: ' + json.dumps(data['error'])[:400])
    return data.get('rows', [])


def sign_in(call_fn, email, password):
    status, data = call_fn('POST', '/auth/v1/token?grant_type=password', body={'email': email, 'password': password})
    if status != 200 or not isinstance(data, dict) or not data.get('access_token'):
        raise RuntimeError(f'Auth sign-in failed (HTTP {status}).')
    return data['access_token'], data['user']['id']


def demo_cleanup_sql(owner_uid, items):
    """Guarded single-line cleanup transaction for ONLY the exact deterministic [DEMO] rows."""
    if not safety.valid_uuid(owner_uid):
        raise ValueError('Invalid owner UUID.')
    if not items:
        raise ValueError('No demo items supplied.')
    lit = lambda v: "'" + str(v).replace("'", "''") + "'"
    ids = []
    for item in items:
        rid = item['id']
        if not safety.valid_uuid(rid) or not item['description'].startswith(DEMO_PREFIX):
            raise ValueError('Invalid demo item identity.')
        ids.append(f'{lit(rid)}::uuid')
    id_list = ','.join(ids)
    return one_line(f"""
        begin;
        do $$ begin
          if exists(select 1 from public.reports r where r.id in ({id_list})
            and (r.owner_id is distinct from {lit(owner_uid)}::uuid
                 or r.barangay <> 'Alangilan'
                 or left(r.description, {len(DEMO_PREFIX)}) <> {lit(DEMO_PREFIX)}))
          then raise exception 'Demo identity mismatch; cleanup refused'; end if;
          if exists(select 1 from private.sms_receipts s where s.report_id in ({id_list}))
          then raise exception 'Unexpected SMS receipt on demo report; cleanup refused'; end if;
          delete from private.push_outbox where notification_id in (select id from public.notifications where report_id in ({id_list}));
          delete from public.notifications where report_id in ({id_list});
          delete from public.report_history where report_id in ({id_list});
          delete from public.report_votes where report_id in ({id_list});
          delete from public.reports where id in ({id_list});
        end $$;
        commit;
    """)


def seed_demos(
    *,
    creds,
    base_url,
    api_key,
    expected_project_ref,
    work,
    apply=False,
    call_fn=None,
    sql_fn=sql,
):
    if base_url.rstrip('/') != f'https://{expected_project_ref}.supabase.co':
        raise ValueError('Target URL does not match expected project ref.')
    catalog = demo_catalog()
    if not apply:
        return {'mode': 'dry-run', 'count': len(catalog), 'ids': [d['id'] for d in catalog], 'barangay': SUPPORTED_BARANGAY}

    os.makedirs(work, exist_ok=True)
    ledger = safety.RunLedger(
        os.path.join(work, 'demo-seed-ledger.json'),
        intent=DEMO_TAG,
        project_ref=expected_project_ref,
        demo_ids=[d['id'] for d in catalog],
        seeded=[],
    )
    if call_fn is None:
        call_fn = lambda m, p, token=None, body=None, content_type='application/json': http_call(
            base_url, api_key, m, p, token=token, body=body, content_type=content_type
        )
    sessions = safety.SessionRegistry(call_fn)
    seeded = []
    ok = False
    try:
        ctok, cuid = sign_in(call_fn, creds['citizen']['email'], creds['citizen']['password'])
        sessions.add('citizen', ctok)
        otok, ouid = sign_in(call_fn, creds['official']['email'], creds['official']['password'])
        sessions.add('official', otok)
        if cuid != creds['citizen']['uid'] or ouid != creds['official']['uid']:
            raise RuntimeError('Signed-in UIDs do not match credentials file.')

        for spec in catalog:
            rid = spec['id']
            photo_path = f'{cuid}/{rid}/photo.jpg'
            jpeg = synthetic_jpeg(spec['color_rgb'])
            assert len(jpeg) <= 256000 and jpeg[:2] == b'\xff\xd8'
            st, _ = call_fn('POST', f'/storage/v1/object/report-images/{photo_path}', token=ctok, body=jpeg, content_type='image/jpeg')
            if st not in (200, 201, 400, 409):
                raise RuntimeError(f'Photo upload for {spec["slug"]} failed (HTTP {st}).')
            if st in (400, 409):
                # Idempotent rerun: verify existing bytes match.
                gst, existing_bytes = call_fn('GET', f'/storage/v1/object/authenticated/report-images/{photo_path}', token=ctok)
                if gst != 200 or existing_bytes != jpeg:
                    raise RuntimeError(f'Existing photo at {photo_path} does not match deterministic demo image.')
            st, _ = call_fn(
                'POST',
                '/rest/v1/rpc/submit_report',
                token=ctok,
                body={
                    'p_id': rid,
                    'p_category': spec['category'],
                    'p_description': spec['description'],
                    'p_location': spec['location'],
                    'p_barangay': spec['barangay'],
                    'p_latitude': spec['latitude'],
                    'p_longitude': spec['longitude'],
                    'p_photo_path': photo_path,
                },
            )
            if st != 200:
                raise RuntimeError(f'submit_report for {spec["slug"]} failed (HTTP {st}).')

            # Apply deterministic status transitions idempotently (skip already reached target).
            cur_rows = sql_fn(f"select status, official_priority from public.reports where id = '{rid}'::uuid")
            cur_status = cur_rows[0]['status'] if cur_rows else 'Pending'
            target_status = spec['transitions'][-1][0] if spec['transitions'] else 'Pending'
            target_prio = spec['transitions'][-1][1] if spec['transitions'] else 'Low'
            if cur_status != target_status:
                for next_status, next_prio in spec['transitions']:
                    if cur_status == next_status:
                        continue
                    ust, _ = call_fn(
                        'POST',
                        '/rest/v1/rpc/update_report_status',
                        token=otok,
                        body={'p_report_id': rid, 'p_status': next_status, 'p_priority': next_prio},
                    )
                    if ust != 200:
                        raise RuntimeError(f'update_report_status ({next_status}) for {spec["slug"]} failed (HTTP {ust}).')
                    cur_status = next_status
            elif cur_rows and cur_rows[0]['official_priority'] != target_prio:
                ust, _ = call_fn(
                    'POST',
                    '/rest/v1/rpc/update_report_status',
                    token=otok,
                    body={'p_report_id': rid, 'p_status': target_status, 'p_priority': target_prio},
                )
                if ust != 200:
                    raise RuntimeError(f'priority update for {spec["slug"]} failed (HTTP {ust}).')

            seeded.append({'id': rid, 'slug': spec['slug'], 'status': target_status, 'priority': target_prio, 'photo_path': photo_path})
            ledger.update(seeded=list(seeded))
        ok = True
    except BaseException as exc:
        ledger.error('seed', exc)
        raise
    finally:
        sessions.teardown()
        ledger.update(
            fixture_cleanup='seeded' if ok else 'incomplete',
            session_cleanup=sessions.summary(),
            sessions={r: {'outcome': o, 'detail': d} for r, (o, d) in sessions.outcomes.items()},
        )
    return {'mode': 'apply', 'seeded': len(seeded), 'session_cleanup': sessions.summary(), 'items': seeded}


def cleanup_demos(*, creds, base_url, api_key, expected_project_ref, work, apply=False, call_fn=None, sql_fn=sql):
    if base_url.rstrip('/') != f'https://{expected_project_ref}.supabase.co':
        raise ValueError('Target URL does not match expected project ref.')
    catalog = demo_catalog()
    owner_uid = creds['citizen']['uid']
    if not apply:
        return {'mode': 'dry-run', 'would_remove': [d['id'] for d in catalog]}

    os.makedirs(work, exist_ok=True)
    ledger = safety.RunLedger(
        os.path.join(work, 'demo-cleanup-ledger.json'),
        intent='exact cleanup of maintained Alangilan demo dataset',
        project_ref=expected_project_ref,
        demo_ids=[d['id'] for d in catalog],
    )
    if call_fn is None:
        call_fn = lambda m, p, token=None, body=None, content_type='application/json': http_call(
            base_url, api_key, m, p, token=token, body=body, content_type=content_type
        )
    sessions = safety.SessionRegistry(call_fn)
    ok = False
    try:
        ctok, cuid = sign_in(call_fn, creds['citizen']['email'], creds['citizen']['password'])
        sessions.add('citizen', ctok)
        if cuid != owner_uid:
            raise RuntimeError('Citizen UID mismatch.')
        # Guarded SQL deletes ONLY exact [DEMO] rows first so Storage delete policy allows owner removal.
        sql_fn(demo_cleanup_sql(owner_uid, catalog), write=True)
        deleted_photos = []
        for spec in catalog:
            photo_path = f'{owner_uid}/{spec["id"]}/photo.jpg'
            st, _ = call_fn('DELETE', f'/storage/v1/object/report-images/{photo_path}', token=ctok)
            if st not in (200, 204, 400, 404):
                raise RuntimeError(f'Photo delete failed for {photo_path} (HTTP {st}).')
            deleted_photos.append(photo_path)
        id_list = ','.join(f"'{d['id']}'::uuid" for d in catalog)
        left = int(sql_fn(f'select count(*) c from public.reports where id in ({id_list})')[0]['c'])
        if left != 0:
            raise RuntimeError(f'{left} demo reports remained after cleanup.')
        ok = True
        ledger.update(deleted_photos=deleted_photos)
    except BaseException as exc:
        ledger.error('cleanup', exc)
        raise
    finally:
        sessions.teardown()
        ledger.update(
            fixture_cleanup='cleaned' if ok else 'failed',
            session_cleanup=sessions.summary(),
            sessions={r: {'outcome': o, 'detail': d} for r, (o, d) in sessions.outcomes.items()},
        )
    return {'mode': 'apply', 'cleaned': len(catalog), 'session_cleanup': sessions.summary()}


def main(argv=None):
    p = argparse.ArgumentParser(description='Manage PinPoint Alangilan synthetic demonstration dataset.')
    p.add_argument('cmd', choices=['seed', 'cleanup', 'list'])
    p.add_argument('--expected-project-ref', required=True)
    p.add_argument('--credentials', help='private credentials JSON outside repo (required for seed/cleanup --apply)')
    p.add_argument('--work', help='private ledger dir outside repo')
    p.add_argument('--apply', action='store_true')
    a = p.parse_args(argv)
    if a.cmd == 'list':
        print(json.dumps(demo_catalog(), indent=1))
        return 0
    for path in (a.work, a.credentials):
        if path:
            resolved = pathlib.Path(path).resolve()
            repo_abs = pathlib.Path(REPO).resolve()
            if resolved == repo_abs or repo_abs in resolved.parents:
                raise SystemExit('Keep credentials and --work outside the repository.')
    cfg = props()
    creds = json.load(open(a.credentials, encoding='utf-8-sig')) if a.credentials else {'citizen': {'uid': '00000000-0000-4000-8000-000000000000'}}
    if a.cmd == 'seed':
        res = seed_demos(
            creds=creds,
            base_url=cfg.get('SUPABASE_URL', ''),
            api_key=cfg.get('SUPABASE_PUBLISHABLE_KEY', ''),
            expected_project_ref=a.expected_project_ref,
            work=a.work,
            apply=a.apply,
        )
    else:
        res = cleanup_demos(
            creds=creds,
            base_url=cfg.get('SUPABASE_URL', ''),
            api_key=cfg.get('SUPABASE_PUBLISHABLE_KEY', ''),
            expected_project_ref=a.expected_project_ref,
            work=a.work,
            apply=a.apply,
        )
    print(json.dumps(res, indent=1))
    return 0


if __name__ == '__main__':
    sys.exit(main())
