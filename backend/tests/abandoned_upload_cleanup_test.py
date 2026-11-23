"""Mocked-transport failure-path and concurrency tests for abandoned-upload cleanup (Batch 1C)."""
import importlib
import json
import os
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
cleanup = importlib.import_module('cleanup_abandoned_uploads')

REF = 'jaysxjjdvaitgrbqjlhe'
BASE = f'https://{REF}.supabase.co'
OWNER_A = '11111111-1111-4111-8111-111111111111'
OWNER_B = '22222222-2222-4222-8222-222222222222'
R1 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000001'
R2 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000002'
R3 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000003'
R4 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000004'
R5 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000005'
R6 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000006'
R7 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000007'
R8 = 'aaaaaaaa-aaaa-4aaa-8aaa-000000000008'


def make_row(rid, bucket, name, owner_id, *, age=100000, referenced=False, multipart=False, created_at='2026-11-20T00:00:00.000000Z'):
    return {
        'id': rid,
        'bucket_id': bucket,
        'name': name,
        'owner_id': owner_id,
        'created_at': created_at,
        'age_seconds': age,
        'is_referenced': referenced,
        'has_active_multipart': multipart,
    }


class AbandonedCleanupGuardsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def test_refuses_mismatched_project_ref_and_short_grace_without_owner_scope(self):
        with self.assertRaises(ValueError):
            cleanup.run_cleanup(base_url=BASE, expected_project_ref='aaaaaaaaaaaaaaaaaaaa')
        with self.assertRaises(ValueError):
            cleanup.run_cleanup(base_url=BASE, expected_project_ref=REF, min_age_seconds=60)
        with self.assertRaises(ValueError):
            cleanup.run_cleanup(base_url=BASE, expected_project_ref=REF, apply=True, work=None)

    def test_dry_run_never_fetches_secret_or_deletes_and_classifies_all_safety_cases(self):
        rows = [
            make_row(R1, 'report-images', f'{OWNER_A}/{R1}/photo.jpg', OWNER_A, referenced=True),
            make_row(R2, 'report-originals', f'{OWNER_A}/{R2}/original', OWNER_A, age=120),
            make_row(R3, 'report-originals', f'{OWNER_A}/{R3}/original', OWNER_A, multipart=True),
            make_row(R4, 'report-images', f'{OWNER_B}/{R4}/photo.jpg', OWNER_B),
            make_row(R5, 'report-images', f'{OWNER_A}/{R5}/photo.jpg', OWNER_B),  # unexpected owner mismatch
            make_row(R6, 'report-images', f'{OWNER_A}/unexpected-name.jpg', OWNER_A),  # malformed path
            make_row(R7, 'report-images', f'{OWNER_A}/{R7}/photo.jpg', OWNER_A),  # eligible abandoned compressed
            make_row(R8, 'report-originals', f'{OWNER_A}/{R8}/original', OWNER_A),  # eligible abandoned original
        ]
        calls = []
        summary, actions = cleanup.run_cleanup(
            base_url=BASE,
            expected_project_ref=REF,
            min_age_seconds=3600,
            owner_scope=OWNER_A,
            apply=False,
            work=self.work,
            sql_fn=lambda q: rows if 'limit' in q else [],
            fetch_key_fn=lambda: (_ for _ in ()).throw(AssertionError('dry-run must never fetch secret key')),
            storage_fn=lambda *a, **k: calls.append(a),
        )
        self.assertEqual(calls, [])
        self.assertEqual(summary['scanned'], 8)
        self.assertEqual(summary['eligible'], 2)
        self.assertEqual(summary['deleted'], 0)
        self.assertEqual(summary['preserved_referenced'], 1)
        self.assertEqual(summary['preserved_within_grace_period'], 1)
        self.assertEqual(summary['preserved_active_session'], 1)
        self.assertEqual(summary['skipped_foreign_owner'], 1)
        self.assertEqual(summary['skipped_unexpected_owner'], 1)
        self.assertEqual(summary['skipped_unexpected_path'], 1)
        self.assertEqual([a['name'] for a in actions if a.get('action') == 'would-delete'], [f'{OWNER_A}/{R7}/photo.jpg', f'{OWNER_A}/{R8}/original'])

    def test_concurrent_attach_and_active_session_preserve_object_before_delete(self):
        cand_attach = make_row(R1, 'report-images', f'{OWNER_A}/{R1}/photo.jpg', OWNER_A)
        cand_session = make_row(R2, 'report-originals', f'{OWNER_A}/{R2}/original', OWNER_A)
        cand_old = make_row(R3, 'report-originals', f'{OWNER_A}/{R3}/original', OWNER_A)
        present = {f"report-originals/{OWNER_A}/{R3}/original": b'bytes'}
        api_calls = []

        def sql_fn(q):
            if 'order by o.created_at' in q:
                return [cand_attach, cand_session, cand_old]
            if R1 in q:
                # Attached by a concurrent submit_report while cleanup was running!
                return [make_row(R1, 'report-images', f'{OWNER_A}/{R1}/photo.jpg', OWNER_A, referenced=True)]
            if R2 in q:
                # Active multipart upload appeared before delete!
                return [make_row(R2, 'report-originals', f'{OWNER_A}/{R2}/original', OWNER_A, multipart=True)]
            if R3 in q:
                key = f'report-originals/{OWNER_A}/{R3}/original'
                return [cand_old] if key in present else []
            raise AssertionError('unexpected query: ' + q)

        def storage_fn(base, method, path, key, body=None):
            api_calls.append((method, path))
            obj = path.replace('/storage/v1/object/authenticated/', '').replace('/storage/v1/object/', '')
            if method == 'DELETE':
                present.pop(obj, None)
                return 200, b'{}'
            if method == 'GET':
                return (200, present[obj]) if obj in present else (400, b'{"error":"not_found"}')
            raise AssertionError(method)

        summary, actions = cleanup.run_cleanup(
            base_url=BASE,
            expected_project_ref=REF,
            min_age_seconds=3600,
            owner_scope=OWNER_A,
            apply=True,
            work=self.work,
            sql_fn=sql_fn,
            fetch_key_fn=lambda: 'mock-secret-in-memory-only',
            storage_fn=storage_fn,
        )
        self.assertEqual(summary['deleted'], 1)
        self.assertEqual(summary['byte_absence_verified'], 1)
        self.assertEqual(summary['preserved_concurrent_attach'], 1)
        self.assertEqual(summary['preserved_active_session'], 1)
        self.assertEqual(summary['failed'], 0)
        self.assertEqual(api_calls, [
            ('DELETE', f'/storage/v1/object/report-originals/{OWNER_A}/{R3}/original'),
            ('GET', f'/storage/v1/object/authenticated/report-originals/{OWNER_A}/{R3}/original'),
        ])
        ledger = json.load(open(os.path.join(self.work, 'abandoned-cleanup-ledger.json')))
        self.assertNotIn('mock-secret-in-memory-only', json.dumps(ledger))
        self.assertEqual(ledger['fixture_cleanup'], 'cleaned')

    def test_idempotent_missing_object_and_partial_network_failure_recovery(self):
        c1 = make_row(R1, 'report-images', f'{OWNER_A}/{R1}/photo.jpg', OWNER_A)
        c2 = make_row(R2, 'report-images', f'{OWNER_A}/{R2}/photo.jpg', OWNER_A)
        c3 = make_row(R3, 'report-images', f'{OWNER_A}/{R3}/photo.jpg', OWNER_A)
        present = {f'report-images/{OWNER_A}/{R3}/photo.jpg': b'old'}

        def sql_fn(q):
            if 'order by o.created_at' in q:
                return [c1, c2, c3]
            if R1 in q:
                return []  # already disappeared before delete (idempotent)
            if R2 in q:
                return [c2]
            if R3 in q:
                return [c3] if f'report-images/{OWNER_A}/{R3}/photo.jpg' in present else []
            return []

        def storage_fn(base, method, path, key, body=None):
            if R2 in path:
                raise OSError('transient network error')
            if R3 in path and method == 'DELETE':
                present.clear()
                return 200, b'{}'
            if R3 in path and method == 'GET':
                return 404, b''
            raise AssertionError(path)

        summary, actions = cleanup.run_cleanup(
            base_url=BASE,
            expected_project_ref=REF,
            min_age_seconds=3600,
            owner_scope=OWNER_A,
            apply=True,
            work=self.work,
            sql_fn=sql_fn,
            fetch_key_fn=lambda: 'mock-secret-test',
            storage_fn=storage_fn,
        )
        self.assertEqual(summary['already_absent'], 1)
        self.assertEqual(summary['failed'], 1)
        self.assertEqual(summary['deleted'], 1)
        ledger = json.load(open(os.path.join(self.work, 'abandoned-cleanup-ledger.json')))
        self.assertEqual(ledger['fixture_cleanup'], 'partial-failure')
        self.assertEqual(len(ledger['errors']), 1)

    def test_pagination_traverses_multiple_batches_deterministically(self):
        page1 = [
            make_row(R1, 'report-images', f'{OWNER_A}/{R1}/photo.jpg', OWNER_A, created_at='2026-11-20T00:00:01.000000Z'),
            make_row(R2, 'report-images', f'{OWNER_A}/{R2}/photo.jpg', OWNER_A, created_at='2026-11-20T00:00:02.000000Z'),
        ]
        page2 = [
            make_row(R3, 'report-images', f'{OWNER_A}/{R3}/photo.jpg', OWNER_A, created_at='2026-11-20T00:00:03.000000Z'),
        ]
        seen_queries = []

        def sql_fn(q):
            seen_queries.append(q)
            return page1 if len(seen_queries) == 1 else page2

        summary, _ = cleanup.run_cleanup(
            base_url=BASE,
            expected_project_ref=REF,
            batch_size=2,
            apply=False,
            sql_fn=sql_fn,
        )
        self.assertEqual(summary['pages'], 2)
        self.assertEqual(summary['scanned'], 3)
        self.assertEqual(summary['eligible'], 3)
        self.assertIn('2026-11-20T00:00:02.000000Z', seen_queries[1])


class TusSmokeGuardTest(unittest.TestCase):
    def test_net_delta_separates_wifi_and_cellular_bytes(self):
        tus_smoke = importlib.import_module('hosted_original_tus_smoke')
        before = {'wlan0': {'rx_bytes': 1000, 'tx_bytes': 2000}, 'rmnet_data0': {'rx_bytes': 500, 'tx_bytes': 800}}
        after = {'wlan0': {'rx_bytes': 8000000, 'tx_bytes': 7002000}, 'rmnet_data0': {'rx_bytes': 500, 'tx_bytes': 800}}
        delta = tus_smoke.net_delta(before, after)
        self.assertEqual(delta['wifi_or_eth_tx_bytes'], 7000000)
        self.assertEqual(delta['cellular_tx_bytes'], 0)

    def test_refuses_physical_serial_without_opt_in_and_non_fixture_credentials(self):
        tus_smoke = importlib.import_module('hosted_original_tus_smoke')
        with tempfile.TemporaryDirectory() as tmp:
            bad_creds = os.path.join(tmp, 'demo.json')
            json.dump({'citizen': {'uid': OWNER_A}, 'official': {'uid': OWNER_B}}, open(bad_creds, 'w'))
            with self.assertRaises(SystemExit):
                tus_smoke.main(['--serial', 'FAKE_PHYSICAL_SERIAL_01', '--credentials', bad_creds, '--work', os.path.join(tmp, 'w')])
            with self.assertRaises(SystemExit):
                tus_smoke.main(['--serial', 'emulator-5554', '--credentials', bad_creds, '--work', os.path.join(tmp, 'w2')])


if __name__ == '__main__':
    unittest.main()
