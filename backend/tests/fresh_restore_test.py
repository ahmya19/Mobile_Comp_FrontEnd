"""Unit and mismatch-refusal tests for the isolated fresh Supabase Free restore harness (Batch 1B)."""
import importlib
import json
import os
import pathlib
import sys
import tempfile
import types
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
restore = importlib.import_module('fresh_restore_harness')


class FreshRestoreGuardTest(unittest.TestCase):
    def test_unconditionally_refuses_production_project_and_mismatched_urls(self):
        with self.assertRaises(ValueError):
            restore.validate_disposable_target(restore.PRODUCTION_PROJECT_REF, restore.PRODUCTION_URL)
        with self.assertRaises(ValueError):
            restore.validate_disposable_target('abcdefghijklmnopqrst', restore.PRODUCTION_URL)
        with self.assertRaises(ValueError):
            restore.validate_disposable_target('abcdefghijklmnopqrst', 'https://zzzzzzzzzzzzzzzzzzzz.supabase.co')
        with self.assertRaises(ValueError):
            restore.validate_disposable_target('not-a-ref', 'https://not-a-ref.supabase.co')
        ref, url = restore.validate_disposable_target('abcdefghijklmnopqrst', 'https://abcdefghijklmnopqrst.supabase.co/')
        self.assertEqual((ref, url), ('abcdefghijklmnopqrst', 'https://abcdefghijklmnopqrst.supabase.co'))

    def test_refuses_workdir_inside_repository_and_strips_production_temp_link(self):
        with self.assertRaises(ValueError):
            restore.validate_isolated_workdir(restore.REPO)
        with self.assertRaises(ValueError):
            restore.validate_isolated_workdir(os.path.join(restore.REPO, 'supabase'))

        with tempfile.TemporaryDirectory() as tmp:
            staged = restore.stage_isolated_workdir(tmp)
            self.assertTrue(os.path.isdir(os.path.join(staged, 'supabase', 'migrations')))
            self.assertTrue(os.path.isdir(os.path.join(staged, 'supabase', 'functions')))
            self.assertFalse(os.path.exists(os.path.join(staged, 'supabase', '.temp')))

    def test_check_quota_reports_blocked_without_creating_anything(self):
        seen = []

        def fake_run(cmd, **kw):
            seen.append(cmd)
            if 'orgs' in cmd:
                return types.SimpleNamespace(returncode=0, stdout='{"organizations":[{"id":"org1"}]}', stderr='')
            return types.SimpleNamespace(returncode=0, stdout='{"projects":[{"ref":"jaysxjjdvaitgrbqjlhe"}]}', stderr='')

        res = restore.check_quota(run=fake_run)
        self.assertEqual(res['status'], 'blocked-needs-owner-zero-dollar-quote')
        self.assertFalse(res['cli_can_quote_price'])
        self.assertFalse(any('create' in c for c in seen))

    def test_teardown_refuses_production_and_disables_cron_before_delete(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                restore.teardown_disposable_project(restore.PRODUCTION_PROJECT_REF, restore.PRODUCTION_URL, tmp, delete_project=True)
            queries, cli_calls = [], []

            def fake_query(wd, q, write=False):
                queries.append((q, write))
                return []

            def fake_run(cmd, **kw):
                cli_calls.append(cmd)
                return types.SimpleNamespace(returncode=0, stdout='{}', stderr='')

            res = restore.teardown_disposable_project(
                'abcdefghijklmnopqrst',
                'https://abcdefghijklmnopqrst.supabase.co',
                tmp,
                delete_project=True,
                run=fake_run,
                query_fn=fake_query,
            )
            self.assertTrue(res['cron_disabled'])
            self.assertTrue(res['project_deleted'])
            self.assertEqual(len(queries), 1)
            self.assertIn("active = false", queries[0][0])
            self.assertEqual(cli_calls[0][-2:], ['abcdefghijklmnopqrst', '--yes'])


if __name__ == '__main__':
    unittest.main()
