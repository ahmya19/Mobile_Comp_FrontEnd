"""Read-only Storage access matrix for existing private test-labelled assets; Python 3.9+."""
import argparse
import hashlib
import json
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-hosted', action='store_true')
    parser.add_argument('--credentials', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not args.allow_hosted:
        parser.error('Explicit --allow-hosted is required before any network operation')
    repo = pathlib.Path(__file__).resolve().parents[2]
    for name in ('credentials', 'manifest', 'output'):
        target = pathlib.Path(getattr(args, name)).resolve()
        if target == repo or repo in target.parents:
            parser.error('Keep credentials, manifest and evidence outside the repository')
    output = pathlib.Path(args.output)
    if output.exists():
        parser.error('Choose an unused evidence archive')
    try:
        accounts = json.loads(pathlib.Path(args.credentials).read_text(encoding='utf8'))
        manifest = json.loads(pathlib.Path(args.manifest).read_text(encoding='utf8'))
    except Exception:
        raise SystemExit('Could not read private JSON inputs') from None
    config = dict(line.split('=', 1) for line in (repo/'local.properties').read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
    url = config['SUPABASE_URL'].replace('\\:', ':').rstrip('/')
    key = config['SUPABASE_PUBLISHABLE_KEY']
    require(url == manifest['project_url'] == 'https://jaysxjjdvaitgrbqjlhe.supabase.co', 'Project mismatch')
    require(key.startswith('sb_publishable_'), 'Only the public publishable key is allowed')
    require(manifest['schema'] == 1 and len(manifest['samples']) == 2, 'Use exactly two existing test samples')
    require(manifest['owner_uid'] == accounts['citizen']['uid'], 'Owner identity mismatch')
    require(manifest['official_uid'] == accounts['official']['uid'], 'Official identity mismatch')
    require(len({s['report_id'] for s in manifest['samples']}) == 2, 'Use two distinct existing reports')
    require(sum(s['barangay'] == manifest['official_barangay'] for s in manifest['samples']) == 1,
            'Both assigned and unassigned barangay samples are required')
    for sample in manifest['samples']:
        report_id = str(uuid.UUID(sample['report_id']))
        require(sample['owner_id'] == manifest['owner_uid'], 'Sample owner mismatch')
        require(len(sample['assets']) == 2 and {a['kind'] for a in sample['assets']} == {'compressed', 'original'}, 'Both variants required exactly once')
        for asset in sample['assets']:
            original = asset['kind'] == 'original'
            require(asset['bucket'] == ('report-originals' if original else 'report-images'), 'Unexpected bucket')
            require(asset['path'] == f"{sample['owner_id']}/{report_id}/"+('original' if original else 'photo.jpg'), 'Unexpected asset path')
            require(0 < asset['size'] <= 1_048_576, 'Select assets no larger than 1 MiB')
            require(asset['mime'] == 'image/jpeg', 'This scenario expects existing JPEG fixtures')
    tokens = {}
    checks = []
    failure = None
    cleanup = {}
    started = time.monotonic()

    def request(method, path, role=None, body=None):
        require((method == 'GET' and path.startswith(('/storage/v1/object/', '/auth/v1/user', '/rest/v1/reports?'))) or
                (method == 'POST' and path in ('/auth/v1/token?grant_type=password', '/auth/v1/logout?scope=local', '/rest/v1/rpc/my_profile')),
                'Unexpected operation; uploads/deletes/business mutations are forbidden')
        headers = {'apikey': key, 'Content-Type': 'application/json'}
        if role in tokens:
            headers['Authorization'] = 'Bearer '+tokens[role]
        req = urllib.request.Request(url+path, data=None if body is None else json.dumps(body).encode(),
                                     headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read(1_048_577)
            require(len(data) <= 1_048_576, 'Response exceeded the bounded download limit')
            return response.status, response.headers.get_content_type(), data

    def json_request(method, path, role=None, body=None):
        status, _, data = request(method, path, role, body)
        return status, json.loads(data) if data else None

    def identity(role):
        status, user = json_request('GET', '/auth/v1/user', role)
        expected = manifest['owner_uid'] if role == 'owner' else manifest['official_uid']
        require(status == 200 and user['id'] == expected, 'Authenticated positive control failed')

    def download(sample, asset, role, public=False, allowed=False, expected_hash=None):
        route = 'public' if public else 'authenticated'
        path = '/storage/v1/object/'+route+'/'+asset['bucket']+'/'+urllib.parse.quote(asset['path'], safe='/')
        status, mime, data = request('GET', path, role)
        case = f"{sample['label']}/{asset['kind']}/{role or 'anonymous'}/{route}"
        entry = {'case': case, 'http': status, 'expected_access': 'allowed' if allowed else 'denied'}
        if allowed:
            require(status == 200 and mime == asset['mime'], case+': allowed JPEG read failed')
            require(len(data) == asset['size'] and data.startswith(b'\xff\xd8') and data.endswith(b'\xff\xd9'), case+': file type/size mismatch')
            digest = hashlib.sha256(data).hexdigest()
            require(expected_hash is None or digest == expected_hash, case+': allowed readers received different bytes')
            entry.update(bytes=len(data), sha256=digest, mime=mime)
            checks.append(entry)
            return digest
        require(status in (400, 401, 403, 404) and mime == 'application/json', case+': expected denial was not received')
        error = json.loads(data)
        entry['error_code'] = error.get('code') or error.get('error') or error.get('statusCode')
        if role:
            # A valid official session plus an owner read of this exact path distinguish policy denial from expiry/missing data.
            require(status != 401 and 'jwt' not in str(error).lower(), case+': denial may be an invalid session')
        checks.append(entry)
        return None

    with output.open('x', encoding='utf8') as archive:
        try:
            for role, account_name in (('owner', 'citizen'), ('official', 'official')):
                account = accounts[account_name]
                status, session = json_request('POST', '/auth/v1/token?grant_type=password', body={k: account[k] for k in ('email', 'password')})
                require(status == 200, 'Demo sign-in failed')
                tokens[role] = session['access_token']
                identity(role)
                status, profile = json_request('POST', '/rest/v1/rpc/my_profile', role, {})
                require(status == 200, 'Server profile read failed')
                require(profile['official'] == (role == 'official'), 'Unexpected server-assigned role')
                if role == 'official':
                    require(profile['barangay'] == manifest['official_barangay'], 'Unexpected official assignment')
            for sample in manifest['samples']:
                for role in ('owner', 'official'):
                    status, rows = json_request('GET', '/rest/v1/reports?id=eq.'+sample['report_id']+'&select=id,owner_id,barangay,photo_path,original_path', role)
                    require(status == 200 and len(rows) == 1, 'Existing report positive control failed')
                    row = rows[0]
                    require(row['owner_id'] == sample['owner_id'] and row['barangay'] == sample['barangay'], 'Report metadata changed')
                    for asset in sample['assets']:
                        require(row['original_path' if asset['kind'] == 'original' else 'photo_path'] == asset['path'], 'Object/report binding changed')
                for asset in sample['assets']:
                    owner_hash = download(sample, asset, 'owner', allowed=True)
                    official_allowed = asset['kind'] == 'compressed' or sample['barangay'] == manifest['official_barangay']
                    download(sample, asset, 'official', allowed=official_allowed, expected_hash=owner_hash)
                    download(sample, asset, None)
                    download(sample, asset, None, public=True)
            for role in tokens:
                identity(role)
        except Exception as error:
            # Do not put URLs, credentials, response bodies or raw image data in console output.
            failure = str(error)
            for account in accounts.values():
                if isinstance(account, dict):
                    for field in ('email', 'password'):
                        if account.get(field): failure = failure.replace(account[field], '[redacted]')
            for token in tokens.values(): failure = failure.replace(token, '[redacted token]')
        finally:
            for role in tokens:
                try:
                    status, _, _ = request('POST', '/auth/v1/logout?scope=local', role)
                    cleanup[role] = {'http': status, 'ok': status in (200, 204)}
                except Exception as error:
                    cleanup[role] = {'ok': False, 'error_type': type(error).__name__}
            passed = failure is None and all(item['ok'] for item in cleanup.values()) and len(cleanup) == 2
            json.dump({'phase': 'passed' if passed else 'failed', 'checks': checks, 'storage_checks': len(checks),
                       'session_cleanup': cleanup, 'failure': failure, 'duration_seconds': round(time.monotonic()-started, 3),
                       'new_assets': 0, 'raw_images_saved': False}, archive, indent=2)
    if not passed:
        raise SystemExit('Storage access check failed; inspect private evidence and session cleanup')
    print('Storage access checks passed:', len(checks), 'cases; both test sessions signed out locally')


if __name__ == '__main__':
    main()
