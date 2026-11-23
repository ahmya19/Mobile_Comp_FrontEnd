"""Unit tests for the maintained Alangilan synthetic demonstration dataset driver (Batch 1C.4)."""
import importlib
import json
import os
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
demos = importlib.import_module('manage_demo_dataset')

REF = 'jaysxjjdvaitgrbqjlhe'
BASE = f'https://{REF}.supabase.co'
CUID = '11111111-1111-4111-8111-111111111111'
OUID = '33333333-3333-4333-8333-333333333333'
CREDS = {
    'citizen': {'email': 'c@example.com', 'password': 'pw-c', 'uid': CUID},
    'official': {'email': 'o@example.com', 'password': 'pw-o', 'uid': OUID},
}


class DemoDatasetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.work = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def test_catalog_covers_all_six_categories_and_alangilan_with_deterministic_ids(self):
        cat = demos.demo_catalog()
        self.assertEqual(len(cat), 6)
        self.assertEqual({d['category'] for d in cat}, {'Road Damage', 'Flooding', 'Streetlight', 'Garbage', 'Water Interruption', 'Other'})
        self.assertTrue(all(d['barangay'] == 'Alangilan' for d in cat))
        self.assertTrue(all(d['description'].startswith('[DEMO]') for d in cat))
        self.assertEqual([d['id'] for d in cat], [d['id'] for d in demos.demo_catalog()])
        jpeg = demos.synthetic_jpeg((124, 77, 204))
        self.assertTrue(jpeg.startswith(b'\xff\xd8') and jpeg.endswith(b'\xff\xd9') and len(jpeg) <= 256000)

    def test_seed_is_idempotent_and_logs_out_both_sessions(self):
        objects = {}
        reports = {}
        logouts = []

        def call_fn(method, path, token=None, body=None, content_type='application/json'):
            if path.startswith('/auth/v1/token'):
                uid = CUID if body['email'] == 'c@example.com' else OUID
                return 200, {'access_token': 'tok-' + uid, 'user': {'id': uid}}
            if path.startswith('/auth/v1/logout'):
                logouts.append(token)
                return 204, None
            if method == 'POST' and path.startswith('/storage/v1/object/report-images/'):
                key = path.split('/report-images/')[1]
                if key in objects:
                    return 409, {'error': 'Duplicate'}
                objects[key] = bytes(body)
                return 200, {'Key': key}
            if method == 'GET' and path.startswith('/storage/v1/object/authenticated/report-images/'):
                key = path.split('/report-images/')[1]
                return 200, objects[key]
            if path == '/rest/v1/rpc/submit_report':
                rid = body['p_id']
                reports.setdefault(rid, {'status': 'Pending', 'official_priority': 'Low', 'photo_path': body['p_photo_path']})
                return 200, reports[rid]
            if path == '/rest/v1/rpc/update_report_status':
                rid = body['p_report_id']
                reports[rid]['status'] = body['p_status']
                reports[rid]['official_priority'] = body['p_priority']
                return 200, reports[rid]
            raise AssertionError((method, path))

        def sql_fn(q, write=False):
            rid = q.split("'")[1]
            return [reports[rid]] if rid in reports else []

        w1 = os.path.join(self.work, 'run1')
        w2 = os.path.join(self.work, 'run2')
        res1 = demos.seed_demos(creds=CREDS, base_url=BASE, api_key='sb_publishable_test', expected_project_ref=REF, work=w1, apply=True, call_fn=call_fn, sql_fn=sql_fn)
        res2 = demos.seed_demos(creds=CREDS, base_url=BASE, api_key='sb_publishable_test', expected_project_ref=REF, work=w2, apply=True, call_fn=call_fn, sql_fn=sql_fn)
        self.assertEqual(res1['seeded'], 6)
        self.assertEqual(res2['seeded'], 6)
        self.assertEqual(res1['session_cleanup'], 'confirmed')
        self.assertEqual(res2['session_cleanup'], 'confirmed')
        self.assertEqual(len(logouts), 4)
        self.assertEqual(len(reports), 6)
        self.assertEqual(len(objects), 6)

    def test_cleanup_removes_only_exact_demo_ids_and_photos(self):
        writes, deleted = [], []

        def call_fn(method, path, token=None, body=None, content_type='application/json'):
            if path.startswith('/auth/v1/token'):
                return 200, {'access_token': 'tok-c', 'user': {'id': CUID}}
            if path.startswith('/auth/v1/logout'):
                return 204, None
            if method == 'DELETE' and '/storage/v1/object/report-images/' in path:
                deleted.append(path)
                return 200, {}
            raise AssertionError((method, path))

        def sql_fn(q, write=False):
            if write:
                writes.append(q)
                return []
            return [{'c': '0'}]

        res = demos.cleanup_demos(creds=CREDS, base_url=BASE, api_key='sb_publishable_test', expected_project_ref=REF, work=self.work, apply=True, call_fn=call_fn, sql_fn=sql_fn)
        self.assertEqual(res['cleaned'], 6)
        self.assertEqual(res['session_cleanup'], 'confirmed')
        self.assertEqual(len(writes), 1)
        self.assertIn('Demo identity mismatch; cleanup refused', writes[0])
        self.assertNotIn('\n', writes[0])
        self.assertEqual(len(deleted), 6)


if __name__ == '__main__':
    unittest.main()
