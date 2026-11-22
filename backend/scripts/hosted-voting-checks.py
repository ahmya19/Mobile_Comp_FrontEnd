"""Opt-in hosted CP-22 checks; host seeds and cleans the private fixture ledger.

Python 3.9+. No service credentials, schema operations, phone access or setup writes.
"""
import argparse
import concurrent.futures
import json
import pathlib
import re
import urllib.error
import urllib.request
import uuid


def main():
    if not __debug__:
        raise RuntimeError('Run without -O; fixture safety assertions must be active')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials', required=True)
    parser.add_argument('--fixtures', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[2]
    for name in ('credentials', 'fixtures', 'output'):
        path = pathlib.Path(getattr(args, name)).resolve()
        if repo == path or repo in path.parents:
            parser.error('Keep credentials, fixture ledger and evidence outside Git')
    output = pathlib.Path(args.output)
    if output.exists():
        parser.error('Choose an unused evidence archive')
    accounts = json.loads(pathlib.Path(args.credentials).read_text())
    ledger = json.loads(pathlib.Path(args.fixtures).read_text())
    prefix = ledger['prefix']
    assert re.fullmatch(r'CP22-\d{8}-[A-Z]\d{2}', prefix)
    ids = ledger['reports']
    assert set(ids) == {'active', 'medium', 'high', 'resolved', 'rejected', 'foreign'}
    assert len({str(uuid.UUID(v)) for v in ids.values()}) == 6
    config = dict(line.split('=', 1) for line in (repo/'local.properties').read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
    url = config['SUPABASE_URL'].replace('\\:', ':').rstrip('/')
    assert url == 'https://jaysxjjdvaitgrbqjlhe.supabase.co'
    key = config['SUPABASE_PUBLISHABLE_KEY']
    assert key.startswith('sb_publishable_'), 'Use the public publishable key only'
    tokens = {}
    checks = []

    def call(method, path, body=None, role='citizen'):
        headers = {'apikey': key, 'Content-Type': 'application/json'}
        if role in tokens:
            headers['Authorization'] = 'Bearer '+tokens[role]
        request = urllib.request.Request(url+path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else None

    def expect(name, method, path, body=None, role='citizen', code=None):
        status, data = call(method, path, body, role)
        if code:
            assert status >= 400 and data.get('code') == code, (name, status, data.get('code'))
            checks.append({'case': name, 'http': status, 'code': code, 'message': data.get('message')})
        else:
            assert 200 <= status < 300, (name, status, data.get('code') if isinstance(data, dict) else None)
            checks.append({'case': name, 'http': status})
        return data

    def vote(name, target, value, role='citizen', code=None):
        return expect(name, 'POST', '/rest/v1/rpc/vote_report',
                      {'p_report_id': ids[target], 'p_voted': value}, role, code)

    def state(target, count, priority):
        status, rows = call('GET', '/rest/v1/reports?id=eq.'+ids[target]+'&select=vote_count,priority')
        assert status == 200 and rows == [{'vote_count': count, 'priority': priority}], (target, rows)

    try:
        for role in ('citizen', 'official'):
            status, data = call('POST', '/auth/v1/token?grant_type=password',
                                {k: accounts[role][k] for k in ('email', 'password')}, role='anonymous')
            assert status == 200 and data['user']['id'] == accounts[role]['uid'], 'Demo sign-in failed'
            tokens[role] = data['access_token']
        # Validate every target before any mutation; refuse unrelated or already-used reports.
        for name, report_id in ids.items():
            status, rows = call('GET', '/rest/v1/reports?id=eq.'+report_id+'&select=*')
            assert status == 200 and len(rows) == 1
            row = rows[0]
            assert row['description'].startswith(prefix+' synthetic voting fixture ')
            assert row['location'] == 'CP22 synthetic test location'
            assert row['photo_path'] is None and row['original_path'] is None
            assert row['vote_count'] == 0 and row['official_priority'] == row['priority'] == 'Low'
            expected = 'Resolved' if name == 'resolved' else 'Rejected' if name == 'rejected' else 'Pending'
            assert row['status'] == expected
            assert row['owner_id'] == (accounts['citizen']['uid'] if name in ('active','resolved','rejected') else None)
            assert row['barangay'] == ('CP22 synthetic foreign barangay' if name == 'foreign' else 'Alangilan')
        vote('owner-add', 'active', True)
        vote('sequential-add-retry', 'active', True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            replies = list(pool.map(lambda _: call('POST','/rest/v1/rpc/vote_report',
                {'p_report_id':ids['active'],'p_voted':True}), range(8)))
        assert all(s == 200 and d['vote_count'] == 1 for s,d in replies)
        checks.append({'case':'eight-concurrent-add-retries','requests':8,'count':1})
        vote('second-account-add','active',True,'official')
        state('active',2,'Low')
        for role in ('citizen','official'):
            rows=expect(role+'-private-vote-read','GET','/rest/v1/report_votes?report_id=eq.'+ids['active'],role=role)
            assert len(rows)==1 and rows[0]['user_id']==accounts[role]['uid']
        expect('anonymous-read-denied','GET','/rest/v1/report_votes?report_id=eq.'+ids['active'],role='anonymous',code='42501')
        vote('anonymous-vote-denied','active',True,'anonymous','42501')
        vote('null-choice-denied','active',None,code='22023')
        for name in ('resolved','rejected'):
            vote(name+'-add-denied',name,True,code='22023')
            vote(name+'-remove-denied',name,False,code='22023')
            state(name,0,'Low')
        missing=str(uuid.uuid4())
        assert call('GET','/rest/v1/reports?id=eq.'+missing)[1] == []
        expect('missing-report-denied','POST','/rest/v1/rpc/vote_report',{'p_report_id':missing,'p_voted':True},code='P0002')
        for method, body in [('POST',{'report_id':ids['active'],'user_id':accounts['official']['uid']}),
                             ('PATCH',{'user_id':accounts['official']['uid']}),('DELETE',None)]:
            expect('direct-vote-'+method+'-denied',method,'/rest/v1/report_votes?report_id=eq.'+ids['active'],body,code='42501')
        expect('direct-priority-denied','PATCH','/rest/v1/reports?id=eq.'+ids['active'],{'vote_count':10,'priority':'High'},code='42501')
        for role,target in [('citizen','medium'),('official','foreign')]:
            expect(role+'-official-priority-denied','POST','/rest/v1/rpc/update_report_status',
                   {'p_report_id':ids[target],'p_status':'Pending','p_priority':'High'},role,code='42501')
        for target, priority in [('medium','Medium'),('high','High')]:
            payload={'p_report_id':ids[target],'p_status':'Pending','p_priority':priority}
            expect(target+'-official-assessment','POST','/rest/v1/rpc/update_report_status',payload,'official')
            expect(target+'-official-retry','POST','/rest/v1/rpc/update_report_status',payload,'official')
            vote(target+'-add',target,True);state(target,1,priority)
            vote(target+'-remove',target,False);state(target,0,priority)
        vote('remove-owner','active',False);vote('remove-owner-retry','active',False)
        state('active',1,'Low')
        vote('other-vote-still-present','active',False)
        state('active',1,'Low')
        vote('remove-second-account','active',False,'official')
        state('active',0,'Low')
        result={'phase':'completed','prefix':prefix,'checks':checks,'check_count':len(checks),
                'concurrent_requests':8,'remaining_fixture_votes':0}
    finally:
        # Local scope preserves the user's existing phone/demo sessions.
        for role in tokens:
            status,_=call('POST','/auth/v1/logout?scope=local',role=role)
            assert status in (200,204), 'Local test-session sign-out failed'
    with output.open('x',encoding='utf8') as stream:
        json.dump(result,stream,indent=2)
    print('Hosted voting checks passed:',result['check_count'],'cases; independent SQL verification/cleanup still required')


if __name__ == '__main__':
    main()
