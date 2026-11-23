#!/usr/bin/env python3
"""Narrow, trusted, concurrency-safe cleanup for abandoned unreferenced Storage uploads (Batch 1C).

Inventory & reference model:
  * report-images:    <owner_uuid>/<report_uuid>/photo.jpg  -> referenced by public.reports.photo_path
  * report-originals: <owner_uuid>/<report_uuid>/original   -> referenced by public.reports.original_path
  * active resumable sessions: storage.s3_multipart_uploads (bucket_id, key)

Safety guarantees:
  1. Default mode is read-only --dry-run (no secret key fetched, no Storage DELETE).
  2. Never deletes storage.objects rows via SQL; always deletes bytes through the Storage API
     (DELETE /storage/v1/object/<bucket>/<name>) using the secret key held only in memory, then
     verifies byte absence (GET returns 400/404) and metadata absence.
  3. Committed reports and referenced objects are immutable and never touched.
  4. Strict eligibility: allowed bucket, exact UUID path format, owner_id matches path prefix,
     older than --min-age-seconds grace period (default 86400 s = 24 h), no reference in
     public.reports, and no active session in storage.s3_multipart_uploads.
  5. Bypassing the 24 h grace period (--min-age-seconds < 86400) requires an explicit --owner-id
     UUID filter so only scoped synthetic tests can use a short cutoff.
  6. Concurrency-safe final re-check immediately before every Storage API DELETE: if a report
     attached the object or a resumable session started while cleanup was considering it, the
     object is preserved (preserved-concurrent-attach / preserved-active-session).
  7. Paginated keyset traversal, atomic exclusive RunLedger outside the repository, idempotent
     reruns (already-missing objects are recorded as already-absent), and partial-failure reporting.
"""
import argparse
import json
import os
import pathlib
import re
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

ALLOWED_BUCKETS = ('report-images', 'report-originals')
DEFAULT_MIN_AGE_SECONDS = 86400
DEFAULT_BATCH_SIZE = 50
MAX_BATCH_SIZE = 200
UUID_RE = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
PATH_PATTERNS = {
    'report-images': re.compile(rf'^({UUID_RE})/({UUID_RE})/photo\.jpg$'),
    'report-originals': re.compile(rf'^({UUID_RE})/({UUID_RE})/original$'),
}


def sql_query(query, cwd=REPO, run=subprocess.run):
    """Read-only privileged SQL via the logged-in Supabase CLI. Never issues SQL writes."""
    cmd = ['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'db', 'query', '--remote', '--read-only', one_line(query)]
    r = run(cmd, cwd=cwd, capture_output=True, text=True, encoding='utf-8', shell=False)
    text = r.stdout or ''
    start = text.find('{')
    if start < 0:
        raise RuntimeError('Read-only SQL query failed: ' + (r.stderr or text)[:300])
    data = json.loads(text[start:])
    if 'error' in data:
        raise RuntimeError('Read-only SQL error: ' + json.dumps(data['error'])[:300])
    return data.get('rows', [])


def fetch_secret_key(cwd=REPO, run=subprocess.run):
    """Obtain the linked project's secret key in memory only. Never prints or persists it."""
    cmd = ['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'projects', 'api-keys', '--reveal']
    r = run(cmd, cwd=cwd, capture_output=True, text=True, encoding='utf-8', shell=False)
    text = r.stdout or ''
    i = text.find('[')
    try:
        keys = json.loads(text[i:text.rindex(']') + 1]) if i >= 0 else []
    except Exception:
        keys = []
    for k in keys:
        val = str(k.get('api_key', ''))
        if k.get('type') == 'secret' and val.startswith('sb_secret_') and '…' not in val:
            return val
    raise RuntimeError('Could not obtain the project secret key from the logged-in Supabase CLI.')


def storage_api(base_url, method, path, secret_key, body=None, urlopen=urllib.request.urlopen):
    """Call Supabase Storage API using the secret key in memory. Never leaks key in errors."""
    encoded = '/'.join(urllib.parse.quote(seg, safe='') for seg in path.lstrip('/').split('/'))
    url = base_url.rstrip('/') + '/' + encoded
    headers = {'apikey': secret_key, 'Authorization': f'Bearer {secret_key}'}
    payload = None
    if body is not None:
        payload = json.dumps(body).encode('utf-8')
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, payload, headers, method=method)
    try:
        with urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, raw
    except urllib.error.HTTPError as err:
        return err.code, err.read()
    except Exception as err:
        raise OSError(f'Storage API {method} failed: {type(err).__name__}') from None


def sql_lit(value):
    return "'" + str(value).replace("'", "''") + "'"


def parse_path(bucket, name):
    """Return (owner_uuid, report_uuid) if name matches the exact PinPoint bucket pattern, else None."""
    pat = PATH_PATTERNS.get(bucket)
    if not pat or not isinstance(name, str):
        return None
    m = pat.fullmatch(name)
    return (m.group(1), m.group(2)) if m else None


def classify_object(row, *, min_age_seconds, owner_scope=None):
    """Classify one candidate storage.objects row without mutating anything.

    Returns (eligible: bool, reason: str, parsed: tuple|None).
    """
    bucket = row.get('bucket_id')
    name = row.get('name')
    if bucket not in ALLOWED_BUCKETS:
        return False, 'skipped-disallowed-bucket', None
    parsed = parse_path(bucket, name)
    if not parsed:
        return False, 'skipped-unexpected-path', None
    path_owner, report_id = parsed
    row_owner = (row.get('owner_id') or row.get('owner') or '').strip().lower()
    if not row_owner or row_owner != path_owner:
        return False, 'skipped-unexpected-owner', parsed
    if owner_scope and path_owner != owner_scope.lower():
        return False, 'skipped-foreign-owner', parsed
    if bool(row.get('is_referenced')):
        return False, 'preserved-referenced', parsed
    if bool(row.get('has_active_multipart')):
        return False, 'preserved-active-session', parsed
    age_seconds = float(row.get('age_seconds') if row.get('age_seconds') is not None else -1)
    if age_seconds < float(min_age_seconds):
        return False, 'preserved-within-grace-period', parsed
    return True, 'eligible-abandoned', (path_owner, report_id)


def inventory_page_sql(buckets, batch_size, cursor=None, owner_scope=None):
    """Deterministic keyset-paginated read-only SQL over storage.objects with reference & multipart joins."""
    if not buckets or any(b not in ALLOWED_BUCKETS for b in buckets):
        raise ValueError('Invalid bucket selection.')
    if not (1 <= int(batch_size) <= MAX_BATCH_SIZE):
        raise ValueError(f'batch_size must be between 1 and {MAX_BATCH_SIZE}.')
    b_list = ','.join(sql_lit(b) for b in buckets)
    clauses = [f'o.bucket_id in ({b_list})']
    if owner_scope:
        if not safety.valid_uuid(owner_scope):
            raise ValueError('Invalid --owner-id UUID.')
        clauses.append(f"split_part(o.name, '/', 1) = {sql_lit(owner_scope.lower())}")
    if cursor:
        c_ts, c_id = cursor
        if not safety.valid_uuid(c_id) or not re.fullmatch(r'[0-9T:\-+. Z]+', str(c_ts)):
            raise ValueError('Invalid pagination cursor.')
        clauses.append(
            f"(o.created_at > {sql_lit(c_ts)}::timestamptz or (o.created_at = {sql_lit(c_ts)}::timestamptz and o.id > {sql_lit(c_id)}::uuid))"
        )
    where = ' and '.join(clauses)
    return one_line(f"""
        select o.id::text as id,
               o.bucket_id as bucket_id,
               o.name as name,
               coalesce(o.owner_id, o.owner::text, '') as owner_id,
               to_char(o.created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') as created_at,
               extract(epoch from (now() - greatest(o.created_at, coalesce(o.updated_at, o.created_at))))::float8 as age_seconds,
               exists(select 1 from public.reports r where (o.bucket_id = 'report-images' and r.photo_path = o.name)
                   or (o.bucket_id = 'report-originals' and r.original_path = o.name)) as is_referenced,
               exists(select 1 from storage.s3_multipart_uploads m where m.bucket_id = o.bucket_id and m.key = o.name) as has_active_multipart
        from storage.objects o
        where {where}
        order by o.created_at asc, o.id asc
        limit {int(batch_size)}
    """)


def final_recheck_sql(bucket, name):
    """Concurrency-safe point-in-time re-check right before calling Storage API DELETE."""
    if bucket not in ALLOWED_BUCKETS or not parse_path(bucket, name):
        raise ValueError('Invalid object target for final recheck.')
    return one_line(f"""
        select o.id::text as id,
               o.bucket_id as bucket_id,
               o.name as name,
               coalesce(o.owner_id, o.owner::text, '') as owner_id,
               extract(epoch from (now() - greatest(o.created_at, coalesce(o.updated_at, o.created_at))))::float8 as age_seconds,
               exists(select 1 from public.reports r where (o.bucket_id = 'report-images' and r.photo_path = o.name)
                   or (o.bucket_id = 'report-originals' and r.original_path = o.name)) as is_referenced,
               exists(select 1 from storage.s3_multipart_uploads m where m.bucket_id = o.bucket_id and m.key = o.name) as has_active_multipart
        from storage.objects o
        where o.bucket_id = {sql_lit(bucket)} and o.name = {sql_lit(name)}
        limit 1
    """)


def run_cleanup(
    *,
    base_url,
    expected_project_ref,
    buckets=ALLOWED_BUCKETS,
    min_age_seconds=DEFAULT_MIN_AGE_SECONDS,
    batch_size=DEFAULT_BATCH_SIZE,
    max_deletes=500,
    owner_scope=None,
    apply=False,
    work=None,
    sql_fn=sql_query,
    fetch_key_fn=fetch_secret_key,
    storage_fn=storage_api,
):
    if not expected_project_ref or not re.fullmatch(r'[a-z0-9]{20}', expected_project_ref):
        raise ValueError('A valid 20-character --expected-project-ref is required.')
    expected_host = f'https://{expected_project_ref}.supabase.co'
    if base_url.rstrip('/') != expected_host:
        raise ValueError(f'Target URL {base_url!r} does not match expected project ref {expected_project_ref!r}.')
    if int(min_age_seconds) < DEFAULT_MIN_AGE_SECONDS and not owner_scope:
        raise ValueError(
            f'A grace period shorter than {DEFAULT_MIN_AGE_SECONDS}s requires an explicit --owner-id scope.'
        )
    if owner_scope and not safety.valid_uuid(owner_scope):
        raise ValueError('Invalid --owner-id UUID.')
    if apply and not work:
        raise ValueError('--apply requires a private --work directory outside the repository.')

    ledger = None
    if work:
        os.makedirs(work, exist_ok=True)
        ledger = safety.RunLedger(
            os.path.join(work, 'abandoned-cleanup-ledger.json'),
            mode='apply' if apply else 'dry-run',
            project_ref=expected_project_ref,
            buckets=list(buckets),
            min_age_seconds=int(min_age_seconds),
            batch_size=int(batch_size),
            owner_scope=owner_scope,
            summary={},
            actions=[],
        )

    summary = {
        'scanned': 0,
        'pages': 0,
        'eligible': 0,
        'deleted': 0,
        'already_absent': 0,
        'preserved_referenced': 0,
        'preserved_concurrent_attach': 0,
        'preserved_active_session': 0,
        'preserved_within_grace_period': 0,
        'skipped_foreign_owner': 0,
        'skipped_unexpected_owner': 0,
        'skipped_unexpected_path': 0,
        'failed': 0,
        'byte_absence_verified': 0,
    }
    actions = []
    secret_key = None
    cursor = None

    while True:
        rows = sql_fn(inventory_page_sql(buckets, batch_size, cursor=cursor, owner_scope=owner_scope))
        if not rows:
            break
        summary['pages'] += 1
        for row in rows:
            summary['scanned'] += 1
            eligible, reason, _ = classify_object(row, min_age_seconds=min_age_seconds, owner_scope=owner_scope)
            entry = {
                'bucket': row.get('bucket_id'),
                'name': row.get('name'),
                'decision': reason,
                'age_seconds': round(float(row.get('age_seconds') or 0), 2),
            }
            if not eligible:
                key_name = reason.replace('-', '_')
                if key_name in summary:
                    summary[key_name] += 1
                actions.append(entry)
                continue

            summary['eligible'] += 1
            if not apply:
                entry['action'] = 'would-delete'
                actions.append(entry)
                continue

            if summary['deleted'] >= int(max_deletes):
                entry['action'] = 'deferred-max-deletes'
                actions.append(entry)
                continue

            # Concurrency-safe final check right before touching Storage API.
            recheck_rows = sql_fn(final_recheck_sql(row['bucket_id'], row['name']))
            if not recheck_rows:
                summary['already_absent'] += 1
                entry['action'] = 'already-absent'
                actions.append(entry)
                continue
            fresh = recheck_rows[0]
            still_eligible, fresh_reason, _ = classify_object(
                fresh, min_age_seconds=min_age_seconds, owner_scope=owner_scope
            )
            if not still_eligible:
                if fresh_reason == 'preserved-referenced':
                    summary['preserved_concurrent_attach'] += 1
                    entry['action'] = 'preserved-concurrent-attach'
                else:
                    k = fresh_reason.replace('-', '_')
                    if k in summary:
                        summary[k] += 1
                    entry['action'] = fresh_reason
                actions.append(entry)
                continue

            if secret_key is None:
                secret_key = fetch_key_fn()

            try:
                del_status, _ = storage_fn(
                    base_url, 'DELETE', f"/storage/v1/object/{row['bucket_id']}/{row['name']}", secret_key
                )
                if del_status in (400, 404):
                    # Verify object bytes are genuinely absent before calling it idempotent.
                    get_status, _ = storage_fn(
                        base_url,
                        'GET',
                        f"/storage/v1/object/authenticated/{row['bucket_id']}/{row['name']}",
                        secret_key,
                    )
                    if get_status in (400, 404):
                        summary['already_absent'] += 1
                        summary['byte_absence_verified'] += 1
                        entry['action'] = 'already-absent'
                    else:
                        summary['failed'] += 1
                        entry['action'] = f'delete-failed-still-readable-http-{get_status}'
                elif del_status in (200, 204):
                    # Prove bytes are gone through Storage API, not merely metadata.
                    get_status, _ = storage_fn(
                        base_url,
                        'GET',
                        f"/storage/v1/object/authenticated/{row['bucket_id']}/{row['name']}",
                        secret_key,
                    )
                    remaining = sql_fn(final_recheck_sql(row['bucket_id'], row['name']))
                    if get_status in (400, 404) and not remaining:
                        summary['deleted'] += 1
                        summary['byte_absence_verified'] += 1
                        entry['action'] = 'deleted-and-verified-absent'
                    else:
                        summary['failed'] += 1
                        entry['action'] = f'verify-absence-failed-http-{get_status}-rows-{len(remaining)}'
                else:
                    summary['failed'] += 1
                    entry['action'] = f'delete-failed-http-{del_status}'
            except Exception as exc:
                summary['failed'] += 1
                entry['action'] = f'delete-error-{type(exc).__name__}'
                if ledger is not None:
                    ledger.error('storage-delete', exc)
            actions.append(entry)
            if ledger is not None:
                ledger.update(summary=dict(summary), actions=list(actions))

        last = rows[-1]
        cursor = (last['created_at'], last['id'])
        if len(rows) < int(batch_size):
            break

    if ledger is not None:
        ledger.update(
            summary=dict(summary),
            actions=list(actions),
            fixture_cleanup='cleaned' if summary['failed'] == 0 else 'partial-failure',
            session_cleanup='none-created',
            finished=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        )
    return summary, actions


def main(argv=None):
    p = argparse.ArgumentParser(description='Safe, concurrency-checked cleanup for abandoned PinPoint Storage uploads.')
    p.add_argument('--expected-project-ref', required=True, help='20-char Supabase project ref guard')
    p.add_argument('--buckets', nargs='+', default=list(ALLOWED_BUCKETS), choices=ALLOWED_BUCKETS)
    p.add_argument('--min-age-seconds', type=int, default=DEFAULT_MIN_AGE_SECONDS)
    p.add_argument('--batch-size', type=int, default=DEFAULT_BATCH_SIZE)
    p.add_argument('--max-deletes', type=int, default=500)
    p.add_argument('--owner-id', default=None, help='optional UUID filter; mandatory if --min-age-seconds < 86400')
    p.add_argument('--work', default=None, help='private ledger directory outside the repository')
    p.add_argument('--supabase-workdir', default=REPO, help='directory containing supabase/.temp project link')
    p.add_argument('--apply', action='store_true', help='execute Storage API deletes (omit for read-only dry run)')
    a = p.parse_args(argv)

    if a.work:
        work_abs = pathlib.Path(a.work).resolve()
        repo_abs = pathlib.Path(REPO).resolve()
        if work_abs == repo_abs or repo_abs in work_abs.parents:
            raise SystemExit('Keep --work outside the repository.')
        a.work = str(work_abs)

    cfg = props()
    base_url = cfg.get('SUPABASE_URL', '')
    summary, _ = run_cleanup(
        base_url=base_url,
        expected_project_ref=a.expected_project_ref,
        buckets=tuple(a.buckets),
        min_age_seconds=a.min_age_seconds,
        batch_size=a.batch_size,
        max_deletes=a.max_deletes,
        owner_scope=a.owner_id,
        apply=a.apply,
        work=a.work,
        sql_fn=lambda q: sql_query(q, cwd=a.supabase_workdir),
        fetch_key_fn=lambda: fetch_secret_key(cwd=a.supabase_workdir),
    )
    print(json.dumps({'mode': 'apply' if a.apply else 'dry-run', 'summary': summary}, indent=1))
    return 0 if summary['failed'] == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
