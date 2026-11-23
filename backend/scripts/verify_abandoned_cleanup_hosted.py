#!/usr/bin/env python3
"""Live hosted synthetic proof for Batch 1C abandoned-upload cleanup.

Uses ONLY the dedicated fixture citizen identity (refuses non-fixture credentials).
Proves against real Supabase Storage + PostgreSQL:
  1. Unreferenced old compressed upload (report-images) -> eligible -> deleted via Storage API -> byte absence (GET 400/404).
  2. Unattached old original upload on a committed report (report-originals, attach_original NOT called) -> eligible -> deleted via Storage API -> byte absence.
  3. Committed referenced compressed photo on that same report -> PRESERVED (GET 200, bytes intact).
  4. Newly created unreferenced object within grace period -> PRESERVED.
  5. Rerun of cleanup is idempotent (0 additional deletes, 0 failures).
  6. Temporary synthetic fixtures are removed with exact identity guards; full baseline counts & fingerprints restored; all REST sessions confirmed HTTP 204.
"""
import argparse
import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cleanup_abandoned_uploads as cleaner  # noqa: E402
import hosted_original_tus_smoke as tus_smoke  # noqa: E402
import hosted_safety as safety  # noqa: E402
import manage_demo_dataset as demos  # noqa: E402
from hosted_authz_probe import BASE, KEY, call, sign_in  # noqa: E402
from map_nav_hosted import REPO  # noqa: E402

REF = 'jaysxjjdvaitgrbqjlhe'


def main(argv=None):
    p = argparse.ArgumentParser(description='Live hosted synthetic verification of Batch 1C abandoned-upload cleanup.')
    p.add_argument('--credentials', required=True)
    p.add_argument('--work', required=True)
    p.add_argument('--supabase-workdir', default=REPO)
    a = p.parse_args(argv)

    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO:
        raise SystemExit('Keep --work outside the repository.')
    os.makedirs(work, exist_ok=True)

    creds = json.load(open(a.credentials, encoding='utf-8-sig'))
    if creds.get('fixture') is not True:
        raise SystemExit('Refusing non-fixture credentials.')
    cuid = creds['citizen']['uid']

    before = tus_smoke.baseline_snapshot(a.supabase_workdir)
    ledger = safety.RunLedger(os.path.join(work, 'cleanup-live-proof-ledger.json'), baseline_before=before)
    sessions = safety.SessionRegistry(call)

    r_abandoned_img = str(uuid.uuid4())
    r_committed = str(uuid.uuid4())
    r_recent_img = str(uuid.uuid4())
    marker = f'CLEAN1C-{uuid.uuid4().hex[:8]} synthetic cleanup verification'

    p_abandoned_img = f'{cuid}/{r_abandoned_img}/photo.jpg'
    p_committed_img = f'{cuid}/{r_committed}/photo.jpg'
    p_unattached_orig = f'{cuid}/{r_committed}/original'
    p_recent_img = f'{cuid}/{r_recent_img}/photo.jpg'

    jpeg = demos.synthetic_jpeg((124, 77, 204))
    test_ok = False

    def http(method, path, token=None, body=None, content_type='application/json'):
        return demos.http_call(BASE, KEY, method, path, token=token, body=body, content_type=content_type)

    try:
        ctok, uid = sign_in(creds['citizen']['email'], creds['citizen']['password'])
        sessions.add('fixture-citizen', ctok)
        assert uid == cuid

        # 1. Upload unreferenced compressed photo (will become "old" after sleep).
        st, _ = http('POST', f'/storage/v1/object/report-images/{p_abandoned_img}', token=ctok, body=jpeg, content_type='image/jpeg')
        assert st in (200, 201), f'abandoned img upload HTTP {st}'

        # 2. Upload compressed photo & commit temporary fixture report, then upload UNATTACHED original.
        st, _ = http('POST', f'/storage/v1/object/report-images/{p_committed_img}', token=ctok, body=jpeg, content_type='image/jpeg')
        assert st in (200, 201), f'committed img upload HTTP {st}'
        st, _ = http(
            'POST', '/rest/v1/rpc/submit_report', token=ctok,
            body={
                'p_id': r_committed, 'p_category': 'Other', 'p_description': marker,
                'p_location': 'Alangilan cleanup test', 'p_barangay': 'Alangilan',
                'p_latitude': None, 'p_longitude': None, 'p_photo_path': p_committed_img,
            },
        )
        assert st == 200, f'submit_report HTTP {st}'
        st, _ = http('POST', f'/storage/v1/object/report-originals/{p_unattached_orig}', token=ctok, body=jpeg, content_type='image/jpeg')
        assert st in (200, 201), f'unattached original upload HTTP {st}'

        # Wait 10 s so the two abandoned objects cross an 8 s synthetic grace period, then upload a fresh object.
        time.sleep(10)
        st, _ = http('POST', f'/storage/v1/object/report-images/{p_recent_img}', token=ctok, body=jpeg, content_type='image/jpeg')
        assert st in (200, 201), f'recent img upload HTTP {st}'

        sql_fn = lambda q: cleaner.sql_query(q, cwd=a.supabase_workdir)
        key_fn = lambda: cleaner.fetch_secret_key(cwd=a.supabase_workdir)

        # 3. Dry run with 8 s cutoff scoped strictly to fixture citizen.
        dry_summary, dry_actions = cleaner.run_cleanup(
            base_url=BASE, expected_project_ref=REF, min_age_seconds=8, owner_scope=cuid,
            apply=False, work=os.path.join(work, 'dry'), sql_fn=sql_fn, fetch_key_fn=key_fn,
        )
        assert dry_summary['eligible'] == 2, f'Expected 2 eligible in dry-run, got {dry_summary}'
        assert dry_summary['preserved_referenced'] == 1, f'Expected 1 referenced preserved, got {dry_summary}'
        assert dry_summary['preserved_within_grace_period'] == 1, f'Expected 1 recent preserved, got {dry_summary}'
        assert dry_summary['deleted'] == 0

        # 4. Live apply: removes ONLY the 2 old unreferenced objects (1 compressed + 1 original) via Storage API.
        apply_summary, apply_actions = cleaner.run_cleanup(
            base_url=BASE, expected_project_ref=REF, min_age_seconds=8, owner_scope=cuid,
            apply=True, work=os.path.join(work, 'apply1'), sql_fn=sql_fn, fetch_key_fn=key_fn,
        )
        assert apply_summary['deleted'] == 2 and apply_summary['byte_absence_verified'] == 2 and apply_summary['failed'] == 0, apply_summary
        assert apply_summary['preserved_referenced'] == 1
        assert apply_summary['preserved_within_grace_period'] == 1

        # Verify committed photo STILL reads HTTP 200 with exact bytes!
        gst, body_bytes = http('GET', f'/storage/v1/object/authenticated/report-images/{p_committed_img}', token=ctok)
        assert gst == 200 and body_bytes == jpeg, f'Committed photo damaged: HTTP {gst}'

        # Verify recent object STILL reads HTTP 200!
        gst2, _ = http('GET', f'/storage/v1/object/authenticated/report-images/{p_recent_img}', token=ctok)
        assert gst2 == 200, f'Recent object unexpectedly deleted: HTTP {gst2}'

        # 5. Idempotent rerun with 120 s cutoff (so recent object stays preserved and already-deleted objects are absent).
        rerun_summary, _ = cleaner.run_cleanup(
            base_url=BASE, expected_project_ref=REF, min_age_seconds=120, owner_scope=cuid,
            apply=True, work=os.path.join(work, 'apply2'), sql_fn=sql_fn, fetch_key_fn=key_fn,
        )
        assert rerun_summary['deleted'] == 0 and rerun_summary['failed'] == 0 and rerun_summary['eligible'] == 0, rerun_summary

        ledger.update(dry_summary=dry_summary, apply_summary=apply_summary, rerun_summary=rerun_summary, committed_photo_survived=True, recent_photo_survived=True)
        test_ok = True
    except BaseException as exc:
        ledger.error('verify', exc)
    finally:
        def clean_temp_report():
            tus_smoke.sql_run(
                f"begin; do $$ begin "
                f"if (select count(*) from public.reports where id='{r_committed}'::uuid and owner_id='{cuid}'::uuid and description='{marker}')=1 then "
                f"if exists(select 1 from public.report_votes where report_id='{r_committed}'::uuid) or exists(select 1 from public.report_history where report_id='{r_committed}'::uuid) or exists(select 1 from public.notifications where report_id='{r_committed}'::uuid) then raise exception 'unexpected refs'; end if; "
                f"delete from public.reports where id='{r_committed}'::uuid; end if; end $$; commit;",
                write=True, cwd=a.supabase_workdir,
            )

        def clean_temp_objects():
            secret = cleaner.fetch_secret_key(cwd=a.supabase_workdir)
            for bucket, path in (
                ('report-images', p_abandoned_img),
                ('report-images', p_committed_img),
                ('report-images', p_recent_img),
                ('report-originals', p_unattached_orig),
            ):
                cleaner.storage_api(BASE, 'DELETE', f'/storage/v1/object/{bucket}/{path}', secret)

        safety.run_all([('clean_temp_report', clean_temp_report), ('clean_temp_objects', clean_temp_objects)], ledger=ledger)
        sessions.teardown()
        after = tus_smoke.baseline_snapshot(a.supabase_workdir)
        baseline_ok = (after == before)
        ledger.update(
            baseline_after=after,
            baseline_preserved=baseline_ok,
            fixture_cleanup='cleaned' if baseline_ok else 'CLEANUP-MISMATCH',
            session_cleanup=sessions.summary(),
        )
        print(json.dumps({
            'test_ok': test_ok,
            'baseline_preserved': baseline_ok,
            'session_cleanup': sessions.summary(),
            'dry_summary': ledger.data.get('dry_summary'),
            'apply_summary': ledger.data.get('apply_summary'),
            'rerun_summary': ledger.data.get('rerun_summary'),
        }, indent=1))

    return safety.final_exit(baseline_ok, sessions.summary(), test_ok)


if __name__ == '__main__':
    sys.exit(main())
