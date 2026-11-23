#!/usr/bin/env python3
"""Physical-phone Batch 3B acceptance cohorts with the DEDICATED FIXTURE CITIZEN, preserving the owner's demo session.

Subcommands (all share one private work directory and ledger; a failed run can be finished with `finish`):
  prepare  backup the owner's session + installation id on the phone, give the app a TEMPORARY installation id (so the
           owner's device registration is never touched), sign the fixture citizen in WITHOUT signing anyone out, verify
           the fixture's only registration is that temporary id.
  tus      cohort A: real Wi-Fi-bound HTTPS TUS original upload with deliberate Wi-Fi off/on handoffs.
  submit   cohort B: compressed-report submission latency and payload on Wi-Fi and on cellular only.
  offline  cohort C: offline report -> connectivity returns -> time until the report is on the server, no Retry pressed.
  finish   remove every trial report/object/registration (exact IDs, guarded), sign the fixture out, restore the owner's
           saved session and installation id, restore radios, and compare the baseline.

Credentials never appear in output. The phone serial is read from --serial and never written to the ledger.
Observations are measurements of this phone, network and moment; they are not statistical claims beyond the declared N.
"""
import argparse, hashlib, json, os, re, subprocess, sys, time, urllib.request, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cleanup_abandoned_uploads as cleaner  # noqa: E402
import hosted_safety as safety  # noqa: E402
from hosted_authz_probe import BASE, KEY, call, sign_in  # noqa: E402
from hosted_original_tus_smoke import baseline_snapshot, net_delta, read_net_dev, sql_run  # noqa: E402
from map_nav_hosted import ADB, PKG, REPO, shell_quote  # noqa: E402

HOST = BASE.replace('https://', '')
RUNNER = f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner'
SESSION_FILE = 'shared_prefs/pinpoint_secure_session.xml'
DEVICE_FILE = 'shared_prefs/pinpoint_device.xml'


class Phone:
    def __init__(self, serial): self.s = serial

    def adb(self, *a, binary=False, timeout=180):
        r = subprocess.run([ADB, '-s', self.s, *a], capture_output=True, timeout=timeout)
        return r.stdout if binary else r.stdout.decode('utf-8', 'replace')

    def sh(self, cmd, timeout=180): return self.adb('shell', cmd, timeout=timeout)
    def runas(self, cmd): return self.adb('exec-out', f'run-as {PKG} {cmd}', binary=True)
    def wifi(self, on): self.sh('svc wifi ' + ('enable' if on else 'disable'))
    def data(self, on): self.sh('svc data ' + ('enable' if on else 'disable'))
    def net(self): return read_net_dev(self.s)

    def online(self, timeout=1.5):
        out = self.sh(f'ping -c 1 -W 1 {HOST}', timeout=10)
        return ' 0% packet loss' in out or '1 received' in out

    def wait_online(self, limit=60):
        t0 = time.time()
        while time.time() - t0 < limit:
            if self.online(): return time.time()
            time.sleep(0.4)
        return None

    def wait_offline(self, limit=20):
        t0 = time.time()
        while time.time() - t0 < limit:
            if not self.online(): return True
            time.sleep(0.5)
        return False

    def instrument(self, phase, **extra):
        cmd = ['am', 'instrument', '-w', '-r', '-e', 'class', f'{PKG}.PhoneAcceptanceTest', '-e', 'phase', phase]
        for k, v in extra.items(): cmd += ['-e', k, str(v)]
        out = self.adb('shell', ' '.join(shell_quote(str(c)) for c in cmd + [RUNNER]), timeout=420)
        return 'OK (1 test)' in out, out

    def receipt(self):
        raw = self.runas('cat files/acceptance-receipt.json').decode('utf-8', 'replace')
        try: return json.loads(raw)
        except Exception: return {}

    def launch(self): self.sh(f'monkey -p {PKG} -c android.intent.category.LAUNCHER 1')
    def home(self): self.sh('input keyevent KEYCODE_HOME')
    def sleep_screen(self): self.sh('input keyevent KEYCODE_SLEEP')
    def wake(self): self.sh('input keyevent KEYCODE_WAKEUP')
    def stop_app(self): self.sh(f'am force-stop {PKG}')

    def queue_rows(self, owner):
        import shutil, sqlite3, tempfile
        tmp = tempfile.mkdtemp()
        try:
            for n in ('pinpoint_queue.db', 'pinpoint_queue.db-wal', 'pinpoint_queue.db-shm'):
                d = self.runas('cat databases/' + n)
                if d and b'No such file' not in d[:300]: open(os.path.join(tmp, n), 'wb').write(d)
            db = os.path.join(tmp, 'pinpoint_queue.db')
            if not os.path.exists(db): return []
            c = sqlite3.connect(db); c.row_factory = sqlite3.Row
            rows = [dict(r) for r in c.execute('select id,state,original_state,upload_url is not null as has_session from queue where owner_id=?', (owner,))]
            c.close(); return rows
        finally: shutil.rmtree(tmp, ignore_errors=True)


def sha_file_remote(phone, path):
    return hashlib.sha256(phone.runas('cat ' + path)).hexdigest()


class Run:
    def __init__(self, a):
        self.a = a; self.work = os.path.abspath(a.work)
        if os.path.commonpath([self.work, REPO]) == REPO: raise SystemExit('Use a private --work directory outside the repository.')
        os.makedirs(self.work, exist_ok=True)
        self.creds = json.load(open(a.credentials, encoding='utf-8-sig'))
        if self.creds.get('fixture') is not True: raise SystemExit('Refusing: credentials file is not the project fixture identities.')
        self.demo = json.load(open(a.demo_credentials, encoding='utf-8-sig'))
        self.cuid, self.ouid = self.creds['citizen']['uid'], self.creds['official']['uid']
        self.phone = Phone(a.serial)
        path = os.path.join(self.work, 'phone-accept-ledger.json')
        self.ledger = safety.RunLedger.load(path) if os.path.exists(path) else safety.RunLedger(path, intent='physical acceptance cohorts with the fixture citizen', trials=[], phase='new')
        self.sessions = safety.SessionRegistry(call); self._tok = None
        self.run_id = self.ledger.data.setdefault('short', uuid.uuid4().hex[:6]); self.ledger.update(short=self.run_id)

    def ctok(self):
        if not self._tok: self._tok = self.sessions.add('citizen-rest', sign_in(self.creds['citizen']['email'], self.creds['citizen']['password'])[0])
        return self._tok

    def plan_trial(self, cohort, **kw):
        rid = str(uuid.uuid4()); marker = f'P4-{self.run_id}-{cohort}-{len(self.ledger.data["trials"]) + 1}'
        t = dict(id=rid, marker=marker, cohort=cohort, **kw); self.ledger.data['trials'].append(t); self.ledger.update(trials=self.ledger.data['trials'])   # BEFORE any hosted write
        return t

    def record(self, t, **kw): t.update(kw); self.ledger.update(trials=self.ledger.data['trials'])

    def row_visible(self, rid):
        s, rows = call('GET', f'/rest/v1/reports?id=eq.{rid}&select=id,original_path,photo_path', self.ctok())
        return rows[0] if s == 200 and rows else None

    def download(self, bucket, path):
        req = urllib.request.Request(f'{BASE}/storage/v1/object/authenticated/{bucket}/{path}', headers={'apikey': KEY, 'Authorization': f'Bearer {self.ctok()}'})
        return urllib.request.urlopen(req, timeout=90).read()

    def fixture_devices(self): return [r['i'] for r in sql_run(f"select installation_id::text i from public.devices where user_id in ('{self.cuid}'::uuid,'{self.ouid}'::uuid)")]

    def demo_devices(self):
        ids = ','.join(f"'{self.demo[k]['uid']}'::uuid" for k in ('citizen', 'official'))
        return sql_run(f"select user_id::text u,installation_id::text i,updated_at::text t,md5(token) k from public.devices where user_id in ({ids}) order by installation_id")

    def local_installation(self):
        m = re.search(r'name="id">([0-9a-f-]{36})<', self.phone.runas('cat ' + DEVICE_FILE).decode('utf-8', 'replace')); return m.group(1) if m else None


# ------------------------------------------------------------------ prepare
def prepare(r):
    p, led = r.phone, r.ledger
    if led.data.get('phase') not in ('new',): raise SystemExit(f"Ledger phase is {led.data.get('phase')}; use finish or a fresh --work directory.")
    battery = int(re.search(r'level: (\d+)', p.sh('dumpsys battery')).group(1))
    if battery < 30: raise SystemExit(f'Battery {battery}% is below 30%; charge first.')
    owner_queue = None
    led.update(battery_start=battery, wifi_before=p.sh('settings get global wifi_on').strip(), data_before=p.sh('settings get global mobile_data').strip())
    session_before = sha_file_remote(p, SESSION_FILE); device_before = sha_file_remote(p, DEVICE_FILE); orig_id = r.local_installation()
    base = baseline_snapshot(REPO); demo_rows = r.demo_devices()
    led.update(phase='backup', session_sha_before=session_before, device_prefs_sha_before=device_before, baseline_before=base, demo_devices_before=demo_rows,
               original_installation_matches_a_demo_row=any(d['i'] == orig_id for d in demo_rows))
    p.stop_app(); time.sleep(1)
    p.sh(f'run-as {PKG} sh -c "mkdir -p files/cp50-backup && cp {SESSION_FILE} files/cp50-backup/session.xml && cp {DEVICE_FILE} files/cp50-backup/device.xml"')
    if sha_file_remote(p, 'files/cp50-backup/session.xml') != session_before or sha_file_remote(p, 'files/cp50-backup/device.xml') != device_before:
        raise SystemExit('Backup verification failed; nothing was changed.')
    for name, src in (('session.xml.bak', SESSION_FILE), ('device.xml.bak', DEVICE_FILE)):   # second copy on the host, private, outside Git
        open(os.path.join(r.work, name), 'wb').write(p.runas('cat ' + src))
    led.update(phase='backup-verified')
    temp = str(uuid.uuid4()); xml = open(os.path.join(r.work, 'device.xml.bak'), encoding='utf-8').read()
    new = re.sub(r'(name="id">)[0-9a-f-]{36}(<)', r'\g<1>' + temp + r'\g<2>', xml); assert new != xml
    local = os.path.join(r.work, 'device-temp.xml'); open(local, 'w', encoding='utf-8').write(new)
    p.adb('push', local, '/data/local/tmp/pp-device-temp.xml'); p.sh('chmod 644 /data/local/tmp/pp-device-temp.xml')
    p.sh(f'run-as {PKG} sh -c "cp /data/local/tmp/pp-device-temp.xml {DEVICE_FILE}"'); p.sh('rm -f /data/local/tmp/pp-device-temp.xml'); os.remove(local)
    assert r.local_installation() == temp
    led.update(phase='temp-installation-set', temp_installation_prefix=temp[:8])
    ok, out = p.instrument('signin', email=r.creds['citizen']['email'], password=r.creds['citizen']['password'])
    if not ok: raise SystemExit('Fixture sign-in phase failed: ' + re.sub(re.escape(r.creds['citizen']['password']), '[redacted]', out)[-400:])
    led.update(phase='swapped')
    p.launch(); end = time.time() + 120
    while time.time() < end and not r.fixture_devices(): time.sleep(3)
    devs = r.fixture_devices()
    if devs != [temp]: raise SystemExit(f'Fixture registration is not exactly the temporary installation: {len(devs)} rows')
    led.update(phase='ready', fixture_registrations=1, demo_devices_after_swap=r.demo_devices())
    if led.data['demo_devices_after_swap'] != demo_rows: raise SystemExit('Owner device rows changed during the swap; stop and run finish.')
    print('prepared: fixture signed in on the temporary installation; owner session and device rows backed up and unchanged')
    p.home(); return 0


# ------------------------------------------------------------------ cohorts
def cohort_tus(r, trials, handoffs):
    p = r.phone; res = []
    r.ledger.update(tus_declared={'trials': trials, 'handoffs_per_trial': handoffs, 'original_bytes': 9_000_000, 'definition': 'Wi-Fi radio disabled while the original upload session exists and the original is not yet Uploaded'})
    idle0 = p.net(); time.sleep(20); idle = net_delta(idle0, p.net()); noise = idle['cellular_tx_bytes']
    for n in range(trials):
        t = r.plan_trial('tus', declared_handoffs=handoffs)
        ok, _ = p.instrument('enqueue', id=t['id'], marker=t['marker'], original='yes', orig_bytes=9_000_000, wait='no')
        rc = p.receipt(); p.launch(); net0 = p.net(); t0 = time.time(); done = False; did = []
        while time.time() - t0 < 300:
            rows = [x for x in p.queue_rows(r.cuid) if x['id'] == t['id']]
            row = rows[0] if rows else {}
            if row.get('original_state') == 'Uploaded': done = True; break
            if row.get('has_session') and len(did) < handoffs and (not did or time.time() - did[-1]['t'] > 25):
                c0 = p.net(); p.wifi(False); off_at = time.time(); time.sleep(9); c1 = p.net(); p.wifi(True); back = p.wait_online(60)
                did.append({'t': time.time(), 'off_s': round(time.time() - off_at, 1), 'cellular_delta_bytes_while_off': sum(v['tx_delta'] + v['rx_delta'] for k, v in net_delta(c0, c1)['interfaces'].items() if re.match(r'^(rmnet|ccmni)', k)),
                            'network_back_s': round(back - off_at, 1) if back else None})
            time.sleep(1.2)
        total = time.time() - t0; net1 = p.net(); d = net_delta(net0, net1)
        vis = r.row_visible(t['id']); verified = {}
        if vis:
            try:
                data = r.download('report-originals', f"{r.cuid}/{t['id']}/original"); verified = {'bytes': len(data), 'sha_matches': hashlib.sha256(data).hexdigest() == rc.get('original_sha256'), 'attach_recorded': bool(vis.get('original_path'))}
            except Exception as e: verified = {'error': type(e).__name__}
        cell_total = sum(v['tx_delta'] + v['rx_delta'] for k, v in d['interfaces'].items() if re.match(r'^(rmnet|ccmni)', k))
        entry = dict(n=n + 1, completed=done, seconds=round(total, 1), handoffs_done=len(did), handoffs=did, report_on_server=bool(vis), original=verified,
                     wifi_tx_bytes=d['wifi_or_eth_tx_bytes'], cellular_tx_bytes=d['cellular_tx_bytes'], cellular_total_bytes=cell_total, idle_cellular_tx_20s=noise)
        entry['pass'] = bool(done and vis and verified.get('sha_matches') and verified.get('attach_recorded') and cell_total < 1_000_000 and len(did) >= 1)
        r.record(t, **entry); res.append(entry); print(json.dumps({k: entry[k] for k in ('n', 'completed', 'seconds', 'handoffs_done', 'cellular_total_bytes', 'pass')}))
        p.wifi(True); p.data(True)
    r.ledger.update(tus_results=res); return res


def cohort_submit(r, per_condition):
    p = r.phone; res = []
    r.ledger.update(submit_declared={'per_condition': per_condition, 'conditions': ['wifi', 'cellular_only'], 'definition': 'client-observed time from enqueue start to the sync worker acknowledging a committed report; app payload = compressed photo + RPC JSON; interface bytes are an upper bound incl. TLS'})
    for cond in ('wifi', 'cellular_only'):
        if cond == 'cellular_only': p.wifi(False); p.data(True); time.sleep(6); assert p.wait_online(40), 'no cellular connectivity'
        else: p.wifi(True); time.sleep(3); assert p.wait_online(40)
        for n in range(per_condition):
            t = r.plan_trial('submit', condition=cond); net0 = p.net()
            ok, _ = p.instrument('enqueue', id=t['id'], marker=t['marker'], original='no', wait='yes', timeout_s=90)
            rc = p.receipt(); d = net_delta(net0, p.net()); vis = r.row_visible(t['id'])
            iface = d['cellular_tx_bytes'] if cond == 'cellular_only' else d['wifi_or_eth_tx_bytes']
            e = dict(condition=cond, ack_ms=rc.get('ack_duration_ms'), client_submit_ms=rc.get('client_submit_ms'), app_payload_bytes=rc.get('app_payload_bytes'), photo_bytes=rc.get('queued_photo_bytes'),
                     interface_tx_bytes=iface, on_server=bool(vis), synced=bool(rc.get('synced')))
            e['pass'] = bool(e['synced'] and e['on_server'] and (e['app_payload_bytes'] or 10**9) <= 300_000 and (e['client_submit_ms'] or 10**9) <= 10_000)
            r.record(t, **e); res.append(e); print(json.dumps({k: e[k] for k in ('condition', 'client_submit_ms', 'app_payload_bytes', 'on_server', 'pass')}))
    p.wifi(True); p.data(True); p.wait_online(40); r.ledger.update(submit_results=res); return res


def cohort_offline(r, repeats):
    p = r.phone; res = []
    conds = [(c, ret) for c in ('foreground', 'home', 'screen_off') for ret in ('wifi', 'cellular') for _ in range(repeats)]
    r.ledger.update(offline_declared={'trials': len(conds), 'conditions': ['foreground', 'home', 'screen_off'], 'returns': ['wifi', 'cellular'], 'limit_s': 60,
                    'definition': 'host clock: first successful ping after the radio returns -> report visible via ordinary REST; Retry is never pressed; both radios are off while offline'})
    for cond, ret in conds:
        t = r.plan_trial('offline', condition=cond, returns=ret)
        p.wifi(False); p.data(False); off_ok = p.wait_offline(25)
        ok, _ = p.instrument('enqueue', id=t['id'], marker=t['marker'], original='no', wait='no'); rc = p.receipt()
        if cond == 'foreground': p.launch(); time.sleep(3)
        elif cond == 'home': p.launch(); time.sleep(3); p.home(); time.sleep(2)
        else: p.launch(); time.sleep(3); p.home(); time.sleep(1); p.sleep_screen(); time.sleep(4)
        assert not r.row_visible(t['id']), 'report reached the server while offline'
        (p.wifi if ret == 'wifi' else p.data)(True); t_cmd = time.time(); t_net = p.wait_online(90); vis_at = None
        end = time.time() + 150
        while time.time() < end:
            if r.row_visible(t['id']): vis_at = time.time(); break
            time.sleep(1.0)
        p.wake(); lat = round(vis_at - t_net, 1) if (vis_at and t_net) else None
        e = dict(condition=cond, returns=ret, offline_confirmed=off_ok, acked_offline=bool(rc.get('acked')), network_back_s=round(t_net - t_cmd, 1) if t_net else None, seconds_after_network=lat, on_server=vis_at is not None)
        e['pass'] = bool(e['on_server'] and lat is not None and lat <= 60 and off_ok)
        r.record(t, **e); res.append(e); print(json.dumps({k: e[k] for k in ('condition', 'returns', 'seconds_after_network', 'pass')}))
        p.wifi(True); p.data(True); p.wait_online(60); time.sleep(2)
    r.ledger.update(offline_results=res); return res


# ------------------------------------------------------------------ finish
def finish(r):
    p, led = r.phone, r.ledger
    outcomes = {}

    def step(name, fn):
        for n, e in safety.run_all([(name, fn)], led):
            outcomes[n] = 'ok' if e is None else 'FAILED: ' + type(e).__name__ + ': ' + str(e)[:200]
    key = {}

    def radios():
        p.wifi(True); p.data(True); p.wait_online(60)

    def reports():
        for t in led.data['trials']:
            rid, marker = t['id'], t['marker']
            if not safety.valid_uuid(rid): continue
            sql_run(f"begin; do $$ begin if (select count(*) from public.reports where id='{rid}'::uuid)>1 or exists(select 1 from public.reports where id='{rid}'::uuid and (owner_id<>'{r.cuid}'::uuid or description<>'{marker}')) "
                    f"then raise exception 'fixture identity mismatch'; end if; if exists(select 1 from public.report_votes where report_id='{rid}'::uuid) or exists(select 1 from public.notifications where report_id='{rid}'::uuid) or exists(select 1 from public.report_history where report_id='{rid}'::uuid) "
                    f"then raise exception 'unexpected references'; end if; delete from public.reports where id='{rid}'::uuid; end $$; commit;", write=True)

    def objects():
        secret = cleaner.fetch_secret_key()
        for t in led.data['trials']:
            for bucket, path in (('report-images', f"{r.cuid}/{t['id']}/photo.jpg"), ('report-originals', f"{r.cuid}/{t['id']}/original")):
                st, _ = cleaner.storage_api(BASE, 'DELETE', f'/storage/v1/object/{bucket}/{path}', secret)
                assert st in (200, 204, 400, 404), f'delete {bucket} HTTP {st}'
                gst, _ = cleaner.storage_api(BASE, 'GET', f'/storage/v1/object/authenticated/{bucket}/{path}', secret)
                assert gst in (400, 404), f'byte absence failed {bucket}: HTTP {gst}'

    def phone_signout():
        p.launch(); time.sleep(3); ok, _ = p.instrument('signout'); assert ok, 'app sign-out phase failed'
        ok, _ = p.instrument('cleanup-queue', owner=r.cuid); assert ok

    def fixture_devices():
        sql_run(f"begin; do $$ begin if exists(select 1 from public.devices d join auth.users u on u.id=d.user_id where d.user_id in ('{r.cuid}'::uuid,'{r.ouid}'::uuid) and coalesce(u.raw_app_meta_data->>'pinpoint_fixture','')<>'true') then raise exception 'not fixture identities'; end if; "
                f"delete from public.devices where user_id in ('{r.cuid}'::uuid,'{r.ouid}'::uuid); end $$; commit;", write=True)

    def restore_owner():
        p.stop_app(); time.sleep(1)
        for src, dst in (('session.xml', SESSION_FILE), ('device.xml', DEVICE_FILE)):
            p.sh(f'run-as {PKG} sh -c "cp files/cp50-backup/{src} {dst}"')
        assert sha_file_remote(p, SESSION_FILE) == led.data['session_sha_before'], 'owner session file does not match the backup'
        assert sha_file_remote(p, DEVICE_FILE) == led.data['device_prefs_sha_before'], 'installation prefs do not match the backup'

    for name, fn in (('radios_restored', radios), ('trial_reports_removed', reports), ('trial_objects_removed', objects), ('app_signout_and_queue', phone_signout),
                     ('fixture_devices_removed', fixture_devices), ('owner_session_restored', restore_owner)):
        step(name, fn)
    # owner verification: launch with the restored session and read the visible account, then stop the app again
    def verify_owner():
        p.launch(); time.sleep(8)
        texts = p.sh('uiautomator dump /sdcard/pp-v.xml >/dev/null; cat /sdcard/pp-v.xml')
        assert 'Hello' in texts and 'Sign in' not in texts.split('Hello')[0][-200:], 'owner session is not active in the app'
        led.update(owner_ui_shows_signed_in_home=True); p.stop_app()
    step('owner_session_verified', verify_owner)
    def restore_updated_at():
        for d in led.data['demo_devices_before']:
            sql_run(f"update public.devices set updated_at='{d['t']}' where installation_id='{d['i']}'::uuid and user_id='{d['u']}'::uuid and md5(token)='{d['k']}'", write=True)
    step('owner_device_rows_timestamp_restored', restore_updated_at)
    after = baseline_snapshot(REPO); demo_after = r.demo_devices()
    r.sessions.teardown()
    led.update(phase='finished', baseline_after=after, demo_devices_after=demo_after, cleanup_outcomes=outcomes, session_cleanup=r.sessions.summary(),
               baseline_preserved=(after == led.data['baseline_before']), owner_device_rows_unchanged=(demo_after == led.data['demo_devices_before']),
               fixture_registrations_left=len(r.fixture_devices()), wifi_restored=p.sh('settings get global wifi_on').strip(), data_restored=p.sh('settings get global mobile_data').strip())
    print(json.dumps({'outcomes': outcomes, 'baseline_preserved': led.data['baseline_preserved'], 'owner_device_rows_unchanged': led.data['owner_device_rows_unchanged'],
                      'session_cleanup': led.data['session_cleanup'], 'fixture_registrations_left': led.data['fixture_registrations_left']}, indent=1))
    ok = all(v == 'ok' for v in outcomes.values()) and led.data['baseline_preserved'] and led.data['owner_device_rows_unchanged'] and led.data['session_cleanup'] in ('confirmed', 'already-invalid', 'none-created')
    return 0 if ok else 1


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['prepare', 'tus', 'submit', 'offline', 'finish'])
    ap.add_argument('--serial', required=True); ap.add_argument('--credentials', required=True); ap.add_argument('--demo-credentials', required=True); ap.add_argument('--work', required=True)
    ap.add_argument('--allow-physical-device', action='store_true'); ap.add_argument('--trials', type=int, default=5); ap.add_argument('--handoffs', type=int, default=2); ap.add_argument('--per-condition', type=int, default=10); ap.add_argument('--repeats', type=int, default=2)
    a = ap.parse_args(argv)
    if not re.fullmatch(r'emulator-\d+', a.serial) and not (a.allow_physical_device and re.fullmatch(r'[A-Za-z0-9]{6,32}', a.serial)): raise SystemExit('Physical devices need --allow-physical-device and a plain serial.')
    r = Run(a)
    try:
        if a.cmd == 'prepare': return prepare(r)
        if a.cmd == 'tus': cohort_tus(r, a.trials, a.handoffs)
        elif a.cmd == 'submit': cohort_submit(r, a.per_condition)
        elif a.cmd == 'offline': cohort_offline(r, a.repeats)
        elif a.cmd == 'finish': return finish(r)
        return 0
    except BaseException as error:
        r.ledger.error(a.cmd, error); print('FAILED:', type(error).__name__, str(error)[:300]); r.phone.wifi(True); r.phone.data(True); return 1
    finally:
        if a.cmd != 'finish': r.sessions.teardown()


if __name__ == '__main__':
    sys.exit(main())
