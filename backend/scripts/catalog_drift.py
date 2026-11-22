#!/usr/bin/env python3
"""Read-only drift check: compare the linked hosted project's PinPoint catalog with the replay snapshot.

Runs backend/contracts/catalog-fingerprint.sql through `supabase db query --remote --read-only` (no data or
secret values are read) and compares it with backend/contracts/final-catalog.json, which the CI replay test
keeps equal to the result of replaying all nine migrations. A match shows the deployed objects equal the
migrations' final state; it is NOT a fresh-project restore (nothing is created).
Exit code 0 = identical, 1 = differences (printed), 2 = could not query.
"""
import json, os, subprocess, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))


def diff(a, b, path='$'):
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a: out.append(f'{path}.{k}: only on the project')
            elif k not in b: out.append(f'{path}.{k}: only in the migrations snapshot')
            else: out += diff(a[k], b[k], f'{path}.{k}')
    elif isinstance(a, list) and isinstance(b, list):
        key = lambda x: x.get('signature') or x.get('name') or x.get('table', '') + x.get('name', '') if isinstance(x, dict) else str(x)
        ma, mb = {key(x): x for x in a}, {key(x): x for x in b}
        if len(ma) == len(a) and len(mb) == len(b):
            for k in sorted(set(ma) | set(mb)):
                if k not in ma: out.append(f'{path}[{k}]: only in the migrations snapshot')
                elif k not in mb: out.append(f'{path}[{k}]: only on the project')
                else: out += diff(ma[k], mb[k], f'{path}[{k}]')
        elif a != b:
            out.append(f'{path}: lists differ')
    elif a != b:
        out.append(f'{path}: project={a!r} snapshot={b!r}')
    return out


def main():
    sql = open(os.path.join(ROOT, 'backend', 'contracts', 'catalog-fingerprint.sql'), encoding='utf-8').read()
    # Drop comment lines (a leading '--' parses as a CLI flag) and use one line (cmd.exe truncates arguments at a newline).
    sql = ' '.join(line.strip() for line in sql.splitlines() if line.strip() and not line.lstrip().startswith('--'))
    expected = json.load(open(os.path.join(ROOT, 'backend', 'contracts', 'final-catalog.json'), encoding='utf-8'))
    r = subprocess.run(['npx.cmd' if os.name == 'nt' else 'npx', 'supabase', 'db', 'query', '--remote', '--read-only', sql],
                       cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
    text = r.stdout
    start = text.find('{')
    if start < 0:
        print('Could not query the project (is the CLI logged in and linked?):', (r.stderr or text)[:300]); return 2
    data = json.loads(text[start:])
    if 'error' in data:
        print('Query error:', json.dumps(data['error'])[:300]); return 2
    actual = json.loads(data['rows'][0]['fingerprint'])
    problems = diff(actual, expected)
    print(json.dumps({'identical': not problems, 'differences': len(problems),
                      'objects': {k: len(v) for k, v in actual.items()}}))
    for p in problems: print(' -', p)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
