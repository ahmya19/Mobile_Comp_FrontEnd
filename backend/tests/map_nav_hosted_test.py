import importlib.util
import json
import pathlib
import tempfile
import types
import unittest
from unittest.mock import patch

path = pathlib.Path(__file__).resolve().parents[1]/'scripts/map_nav_hosted.py'
spec = importlib.util.spec_from_file_location('map_driver', path)
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
OWNER = '11111111-1111-4111-8111-111111111111'
CFG = {'SUPABASE_URL': 'https://jaysxjjdvaitgrbqjlhe.supabase.co', 'SUPABASE_PUBLISHABLE_KEY': 'sb_publishable_test'}


def ledger():
    return {'schema': 1, 'prefix': driver.PREFIX, 'citizen_uid': OWNER, 'baseline_counts': {},
            'baseline_report_ids': ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'], 'baseline_report_digest': '0'*32,
            'fixtures': {name: dict(value, id=f'bbbbbbbb-bbbb-4bbb-8bbb-{i:012d}', description=f'{driver.PREFIX} {name} marker fixture')
                         for i,(name,value) in enumerate(driver.FIXTURES.items(),1)}}


class MapDriverSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='pinpoint-map-driver-')
        self.addCleanup(self.temp.cleanup)
        root = pathlib.Path(self.temp.name)
        self.work = root/'evidence'
        self.work.mkdir()
        self.credentials = root/'accounts.json'
        self.credentials.write_text(json.dumps({'citizen': {'uid': OWNER}}), encoding='utf8')
        self.args = types.SimpleNamespace(work=str(self.work), credentials=str(self.credentials), serial='emulator-5556', intent_recorded=False)

    def main_args(self, command):
        return ['map_nav_hosted.py', command, '--work', str(self.work), '--credentials', str(self.credentials)]

    def test_existing_ledger_is_preserved_before_any_remote_operation(self):
        saved = self.work/'map-nav-ledger.json'
        saved.write_text('{"status":"intent-recorded","valuable":"scope"}')
        original = saved.read_bytes()
        with patch('sys.argv', self.main_args('full')), patch.object(driver,'prepare') as prepare, patch.object(driver,'cleanup') as cleanup:
            with self.assertRaises(SystemExit): driver.main()
            prepare.assert_not_called();cleanup.assert_not_called()
        self.assertEqual(saved.read_bytes(), original)

    def test_repository_work_is_rejected(self):
        self.args.work = driver.REPO
        with self.assertRaises(SystemExit): driver.validate_paths(self.args)

    def test_repository_credentials_are_rejected(self):
        self.args.credentials = str(pathlib.Path(driver.REPO)/'accounts.json')
        with self.assertRaises(SystemExit): driver.validate_paths(self.args)

    def failed_prepare(self, args, *_):
        args.intent_recorded = True
        raise OSError('failure after the first submission')

    def test_full_cleans_partial_preparation(self):
        with patch('sys.argv',self.main_args('full')), patch.object(driver,'props',return_value=CFG), \
             patch.object(driver,'prepare',side_effect=self.failed_prepare), patch.object(driver,'cleanup',return_value=True) as cleanup:
            with self.assertRaises(OSError): driver.main()
            cleanup.assert_called_once()

    def test_standalone_prepare_cleans_partial_preparation(self):
        with patch('sys.argv',self.main_args('prepare')), patch.object(driver,'props',return_value=CFG), \
             patch.object(driver,'prepare',side_effect=self.failed_prepare), patch.object(driver,'cleanup',return_value=True) as cleanup:
            with self.assertRaises(OSError): driver.main()
            cleanup.assert_called_once()

    def test_exclusive_create_collision_does_not_clean_someone_elses_ledger(self):
        with patch('sys.argv',self.main_args('full')), patch.object(driver,'props',return_value=CFG), \
             patch.object(driver,'prepare',side_effect=FileExistsError()), patch.object(driver,'cleanup') as cleanup:
            with self.assertRaises(FileExistsError): driver.main()
            cleanup.assert_not_called()

    def test_host_session_is_locally_logged_out_when_second_submission_fails(self):
        data = ledger()
        driver.save_ledger(self.args,data,exclusive=True)
        calls = []
        def http(url,body,headers):
            calls.append(url)
            on_disk = json.loads((self.work/'map-nav-ledger.json').read_text())
            self.assertEqual(len(on_disk['fixtures']),2)
            if len(calls)==2: raise OSError('second submission failed')
        with patch.object(driver,'citizen_session',return_value=(CFG['SUPABASE_URL'],'key','test-token',OWNER)), patch.object(driver,'http_json',side_effect=http):
            with self.assertRaises(OSError): driver.submit_fixtures(self.args,{},CFG,data)
        self.assertEqual(len(calls),3)
        self.assertTrue(calls[-1].endswith('/auth/v1/logout?scope=local'))
        self.assertTrue(json.loads((self.work/'map-nav-ledger.json').read_text())['host_session_cleanup'])

    def test_wrong_citizen_is_logged_out_without_submission(self):
        data = ledger();driver.save_ledger(self.args,data,exclusive=True)
        with patch.object(driver,'citizen_session',return_value=(CFG['SUPABASE_URL'],'key','test-token','wrong-user')), patch.object(driver,'http_json') as http:
            with self.assertRaises(RuntimeError): driver.submit_fixtures(self.args,{},CFG,data)
            self.assertEqual(http.call_count,1)
            self.assertTrue(http.call_args.args[0].endswith('/auth/v1/logout?scope=local'))

    def test_cleanup_refuses_baseline_id_overlap(self):
        data = ledger();data['fixtures']['road']['id'] = data['baseline_report_ids'][0]
        with self.assertRaises(ValueError): driver.cleanup_sql(data)

    def test_cleanup_refuses_changed_fixture_identity(self):
        data = ledger();data['fixtures']['road']['latitude'] = 1
        with self.assertRaises(ValueError): driver.cleanup_sql(data)

    def test_run_preserves_existing_archive_before_adb(self):
        saved = self.work/'map-nav-test.log';saved.write_text('previous evidence')
        with patch.object(driver,'adb') as adb:
            with self.assertRaises(SystemExit): driver.run(self.args,{})
            adb.assert_not_called()
        self.assertEqual(saved.read_text(),'previous evidence')


if __name__ == '__main__': unittest.main()
