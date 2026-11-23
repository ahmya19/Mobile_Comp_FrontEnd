#!/usr/bin/env python3
"""Isolated disposable Supabase Free fresh-restore harness (Batch 1B).

Strict isolation guarantees:
  1. Unconditionally refuses the production project ref (jaysxjjdvaitgrbqjlhe) and production URL.
  2. Never reads or mutates the main repository's supabase/.temp/linked-project.json; all CLI calls
     run against an isolated --workdir outside the repository.
  3. Never creates a Supabase project without explicit --confirmed-zero-dollar-quote (because the
     Supabase CLI cannot display a project creation price quote; the owner must verify $0 in the
     dashboard when creating a second Free project).
  4. Never copies production Auth credentials, device rows, business data, FCM private keys or SMS
     secrets. Leaves push cron and SMS receiver disabled until test-only recipients are configured.
"""
import argparse
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hosted_safety as safety  # noqa: E402
from map_nav_hosted import REPO, one_line  # noqa: E402

PRODUCTION_PROJECT_REF = 'jaysxjjdvaitgrbqjlhe'
PRODUCTION_URL = f'https://{PRODUCTION_PROJECT_REF}.supabase.co'
REQUIRED_EXTENSIONS = ('pg_cron', 'pg_net', 'pgcrypto', 'supabase_vault')
REQUIRED_NET_TABLES = ('net.http_request_queue', 'net._http_response')


def validate_disposable_target(project_ref, project_url):
    if not project_ref or not re.fullmatch(r'[a-z0-9]{20}', project_ref):
        raise ValueError('Expected a 20-character lowercase alphanumeric Supabase project ref.')
    if project_ref == PRODUCTION_PROJECT_REF:
        raise ValueError('REFUSED: cannot target the production PinPoint project ref for fresh restore.')
    expected_url = f'https://{project_ref}.supabase.co'
    if not project_url or project_url.rstrip('/') != expected_url:
        raise ValueError(f'REFUSED: target URL {project_url!r} does not match expected disposable project {expected_url!r}.')
    if project_url.rstrip('/') == PRODUCTION_URL:
        raise ValueError('REFUSED: cannot target the production PinPoint URL.')
    return project_ref, expected_url


def validate_isolated_workdir(workdir, repo_root=REPO):
    wd = pathlib.Path(workdir).resolve()
    repo = pathlib.Path(repo_root).resolve()
    if wd == repo or repo in wd.parents:
        raise ValueError('REFUSED: isolated restore --workdir must live outside the repository.')
    return str(wd)


def run_cli(args, cwd, run=subprocess.run):
    cmd = ['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', *args]
    return run(cmd, cwd=cwd, capture_output=True, text=True, encoding='utf-8', shell=False)


def parse_json_output(raw):
    for ch in ('{', '['):
        idx = raw.find(ch)
        if idx >= 0:
            try:
                return json.loads(raw[idx:])
            except json.JSONDecodeError:
                pass
    return None


def check_quota(run=subprocess.run, cwd=REPO):
    """Read-only inspection of logged-in Supabase organizations and projects. Never creates anything."""
    orgs_r = run_cli(['orgs', 'list'], cwd=cwd, run=run)
    proj_r = run_cli(['projects', 'list'], cwd=cwd, run=run)
    orgs_data = parse_json_output(orgs_r.stdout or '') or {}
    proj_data = parse_json_output(proj_r.stdout or '') or {}
    orgs = orgs_data.get('organizations', orgs_data if isinstance(orgs_data, list) else [])
    projects = proj_data.get('projects', proj_data if isinstance(proj_data, list) else [])
    return {
        'organizations_count': len(orgs),
        'projects_count': len(projects),
        'cli_can_quote_price': False,
        'status': 'blocked-needs-owner-zero-dollar-quote',
        'owner_action': (
            'Open Supabase Dashboard -> New Project in the same Free organization and confirm the '
            'price line reads $0/month (Free) before proceeding with --confirmed-zero-dollar-quote.'
        ),
    }


def stage_isolated_workdir(workdir, repo_root=REPO):
    """Copy only migrations, functions, templates and config.toml into an isolated workdir (NO .temp link)."""
    wd = validate_isolated_workdir(workdir, repo_root=repo_root)
    src_supabase = pathlib.Path(repo_root) / 'supabase'
    dst_supabase = pathlib.Path(wd) / 'supabase'
    os.makedirs(dst_supabase, exist_ok=True)
    for sub in ('migrations', 'functions', 'templates'):
        src = src_supabase / sub
        dst = dst_supabase / sub
        if src.exists():
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
    cfg = src_supabase / 'config.toml'
    if cfg.exists():
        shutil.copy2(cfg, dst_supabase / 'config.toml')
    # Guarantee no production link file ever exists inside the isolated workdir.
    temp_dir = dst_supabase / '.temp'
    if temp_dir.exists():
        shutil.rmtree(temp_dir)
    return wd


def query_isolated(workdir, query, write=False, run=subprocess.run):
    flag = '--yes' if write else '--read-only'
    r = run_cli(['db', 'query', '--remote', flag, one_line(query)], cwd=workdir, run=run)
    data = parse_json_output(r.stdout or '')
    if not isinstance(data, dict) or 'error' in data:
        raise RuntimeError('Isolated SQL failed: ' + (r.stderr or r.stdout or '')[:300])
    return data.get('rows', [])


def verify_extensions(workdir, query_fn=query_isolated):
    ext_list = ','.join(f"'{e}'" for e in REQUIRED_EXTENSIONS)
    rows = query_fn(workdir, f'select extname, extversion from pg_extension where extname in ({ext_list}) order by extname')
    found = {r['extname']: r['extversion'] for r in rows}
    missing_ext = [e for e in REQUIRED_EXTENSIONS if e not in found]
    net_rows = query_fn(
        workdir,
        "select to_regclass('net.http_request_queue') is not null as has_queue, "
        "to_regclass('net._http_response') is not null as has_resp",
    )
    has_queue = bool(net_rows and net_rows[0].get('has_queue'))
    has_resp = bool(net_rows and net_rows[0].get('has_resp'))
    return {
        'extensions': found,
        'missing_extensions': missing_ext,
        'has_net_queue': has_queue,
        'has_net_response': has_resp,
        'ok': not missing_ext and has_queue and has_resp,
    }


def disable_outgoing_cron_sql():
    """Keep scheduled outgoing push dispatch paused on a disposable restore until test recipients are verified."""
    return one_line("update cron.job set active = false where jobname = 'pinpoint-push-outbox';")


def verify_catalog_against_snapshot(workdir, repo_root=REPO, query_fn=query_isolated):
    from catalog_drift import diff

    fp_sql = open(pathlib.Path(repo_root) / 'backend' / 'contracts' / 'catalog-fingerprint.sql', encoding='utf-8').read()
    fp_sql = ' '.join(line.strip() for line in fp_sql.splitlines() if line.strip() and not line.lstrip().startswith('--'))
    expected = json.load(open(pathlib.Path(repo_root) / 'backend' / 'contracts' / 'final-catalog.json', encoding='utf-8'))
    rows = query_fn(workdir, fp_sql)
    actual = json.loads(rows[0]['fingerprint'])
    problems = diff(actual, expected)
    return {'identical': not problems, 'differences': len(problems), 'problems': problems, 'objects': {k: len(v) for k, v in actual.items()}}


def teardown_disposable_project(project_ref, project_url, workdir, *, delete_project=False, run=subprocess.run, query_fn=query_isolated):
    ref, _ = validate_disposable_target(project_ref, project_url)
    wd = validate_isolated_workdir(workdir)
    query_fn(wd, disable_outgoing_cron_sql(), write=True)
    deleted = False
    if delete_project:
        r = run_cli(['projects', 'delete', ref, '--yes'], cwd=wd, run=run)
        if r.returncode != 0:
            raise RuntimeError('Disposable project delete failed: ' + (r.stderr or r.stdout or '')[:300])
        deleted = True
    return {'disposable_ref_redacted': ref[:4] + '...' + ref[-4:], 'cron_disabled': True, 'project_deleted': deleted}


def main(argv=None):
    p = argparse.ArgumentParser(description='Isolated fresh Supabase Free restore helper (never touches production).')
    p.add_argument('cmd', choices=['check-quota', 'stage', 'verify', 'teardown'])
    p.add_argument('--expected-project-ref', default=None)
    p.add_argument('--expected-url', default=None)
    p.add_argument('--workdir', default=None)
    p.add_argument('--delete-project', action='store_true')
    a = p.parse_args(argv)

    if a.cmd == 'check-quota':
        print(json.dumps(check_quota(), indent=1))
        return 0

    ref, url = validate_disposable_target(a.expected_project_ref, a.expected_url)
    if not a.workdir:
        raise SystemExit('--workdir (outside the repository) is required.')
    wd = validate_isolated_workdir(a.workdir)

    if a.cmd == 'stage':
        staged = stage_isolated_workdir(wd)
        print(json.dumps({'staged_workdir': staged, 'disposable_ref_redacted': ref[:4] + '...' + ref[-4:], 'production_link_untouched': True}, indent=1))
        return 0
    if a.cmd == 'verify':
        ext = verify_extensions(wd)
        cat = verify_catalog_against_snapshot(wd)
        print(json.dumps({'extensions': ext, 'catalog': cat}, indent=1))
        return 0 if (ext['ok'] and cat['identical']) else 1
    if a.cmd == 'teardown':
        res = teardown_disposable_project(ref, url, wd, delete_project=a.delete_project)
        print(json.dumps(res, indent=1))
        return 0
    return 1


if __name__ == '__main__':
    sys.exit(main())
