#!/usr/bin/env python3
"""Real FCM delivery probe on ONE isolated emulator, with an independent hosted oracle and guarded cleanup.

Route under test (deployed, nothing added): ordinary report -> assigned-official status RPC -> history/notification/
push_outbox rows -> pg_cron dispatcher -> deliver-push Edge Function -> Google FCM -> emulator
PinPointMessagingService -> tray notification -> tap -> report details.

Recipient isolation: notifications go to the REPORT OWNER's registered devices. The fixture report is owned by the
demo OFFICIAL, who must have no device registration other than this emulator's. The demo citizen's two registrations
(possibly the user's phone) are never targeted: before every status change this script re-verifies, with privileged
read-only SQL, that the only device rows for the official are this emulator's installation and that the citizen's
rows are byte-for-byte unchanged. Any drift aborts before sending. No other account is signed in on the emulator.

Cases (each recorded with observed checkpoints; timestamps are observations, not CP-25 latency acceptance):
  foreground, background after activity destruction + rotation recreation, notification permission denied then
  granted, force-stop negative control, sign-out cleanup, sign-in device-row recovery.
Simulated-only cases (duplicate delivery, late payload after account switch) live in the local fault tests.

Secrets, tokens, FCM tokens and installation ids are never printed or stored. Use a fresh private --work directory.
"""
import argparse, hashlib, json, os, re, subprocess, sys, time, uuid
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hosted_authz_probe import REPO, call, sign_in, sign_out_local  # noqa: E402
from map_nav_hosted import ADB, PKG, props, shell_quote, sql  # noqa: E402

RUN = time.strftime('%Y%m%d', time.gmtime())
SERIAL_RE = re.compile(r'emulator-\d+')
MAX_WAIT = 150


def adb(serial, *args, check=False):
    r = subprocess.run([ADB, '-s', serial, *args], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if check and r.returncode: raise SystemExit(f'adb {args[0]} failed')
    return r.stdout


def now(): return time.monotonic()


class Probe:
    def __init__(self, a, creds):
        self.a, self.creds, self.s = a, creds, a.serial
        self.official = creds['official']; self.uid = creds['official']['uid']; self.cuid = creds['citizen']['uid']
        self.matrix = []; self.report_id = a.report_id; self.marker = f'PUSHFCM-{a.run_id} real FCM probe fixture'
        self.token = None; self.installation = None

    # ---------- hosted oracle (privileged, read-only) ----------
    def devices(self, uid):
        return sql(f"select installation_id::text i from public.devices where user_id='{uid}'::uuid order by 1")

    def citizen_fp(self):
        return sql(f"select md5(coalesce(string_agg(to_jsonb(d)::text,',' order by installation_id),'')) f,count(*) n from public.devices d where user_id='{self.cuid}'::uuid")[0]

    def guard(self, label):
        """Abort before any send unless the recipient is exclusively this emulator."""
        rows = [r['i'] for r in self.devices(self.uid)]
        if not self.installation: raise SystemExit(f'{label}: emulator installation unknown')
        if any(i != self.installation for i in rows): raise SystemExit(f'{label}: unexpected official device registration; aborting before send')
        if self.citizen_fp() != self.cfp: raise SystemExit(f'{label}: citizen device rows changed; aborting before send')
        return len(rows)

    def outbox(self, nid):
        r = sql(f"select delivered_at is not null d,attempts a,coalesce(last_error,'') e,"
                f"extract(epoch from (delivered_at-(select created_at from public.notifications where id='{nid}'::uuid))) lat "
                f"from private.push_outbox where notification_id='{nid}'::uuid")
        return r[0] if r else None

    # ---------- device ----------
    def installed_id(self):
        out = adb(self.s, 'shell', f'run-as {PKG} cat shared_prefs/pinpoint_device.xml')
        m = re.search(r'name="id">([0-9a-f-]{36})<', out)
        return m.group(1) if m else None

    def instrument(self, phase):
        cmd = ['am', 'instrument', '-w', '-r', '-e', 'class', 'com.example.pinpoint.PushRecipientSetupTest', '-e', 'phase', phase]
        if phase == 'signin': cmd += ['-e', 'email', self.official['email'], '-e', 'password', self.official['password']]
        cmd += [f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner']
        out = adb(self.s, 'shell', ' '.join(shell_quote(c) for c in cmd))
        out = out.replace(self.official['password'], '[redacted]').replace(self.official['email'], '[demo email]')
        if 'OK (1 test)' not in out: raise SystemExit(f'setup phase {phase} failed: ' + out[-800:])

    def wait_registration(self):
        """The registration worker may be pending when the instrumentation process ends; opening the app resumes it."""
        end = now() + 20
        while now() < end and not self.devices(self.uid): time.sleep(2)
        if not self.devices(self.uid):
            self.launch(); end = now() + 90
            while now() < end and not self.devices(self.uid): time.sleep(2)

    def ui(self):
        adb(self.s, 'shell', 'uiautomator dump /sdcard/pp-ui.xml')
        raw = adb(self.s, 'shell', 'cat /sdcard/pp-ui.xml')
        try: root = ET.fromstring(raw[raw.index('<'):])
        except Exception: return []
        return [(n.get('text', ''), n.get('content-desc', ''), n.get('bounds', ''), n.get('package', '')) for n in root.iter('node')]

    def tap_text(self, needle, timeout=20):
        end = now() + timeout
        while now() < end:
            for t, d, b, _ in self.ui():
                if needle.lower() in (t + ' ' + d).lower():
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b)); adb(self.s, 'shell', f'input tap {(x1+x2)//2} {(y1+y2)//2}'); return True
            time.sleep(1)
        return False

    def screen_has(self, needle, timeout=20):
        end = now() + timeout
        while now() < end:
            if any(needle.lower() in (t + ' ' + d).lower() for t, d, _, _ in self.ui()): return True
            time.sleep(1)
        return False

    def tray(self):
        out = adb(self.s, 'shell', 'dumpsys notification --noredact')
        found = []
        for m in re.finditer(r'NotificationRecord\(0x[0-9a-f]+: pkg=' + re.escape(PKG) + r' user=\S+ id=(-?\d+) tag=(\S+) ', out):
            found.append(m.group(2))
        return found

    # ---------- hosted actions as the ORDINARY official session ----------
    def session(self):
        if not self.token: self.token = sign_in(self.official["email"], self.official["password"])[0]
        return self.token

    def rpc(self, name, body):
        status, data = call('POST', f'/rest/v1/rpc/{name}', self.session(), body)
        if status >= 300: raise SystemExit(f'{name} -> HTTP {status}')
        return data

    def new_notice(self, before):
        status, rows = call('GET', f'/rest/v1/notifications?report_id=eq.{self.report_id}&select=id,message,created_at&order=created_at.desc', self.session())
        ids = [r for r in rows if r['id'] not in before]
        return ids[0] if ids else None

    def change(self, status, label):
        self.guard(label)
        _, rows = call('GET', f'/rest/v1/notifications?report_id=eq.{self.report_id}&select=id', self.session())
        before = {r['id'] for r in rows}
        t0 = now(); self.rpc('update_report_status', {'p_report_id': self.report_id, 'p_status': status})
        notice = self.new_notice(before)
        if not notice: raise SystemExit(f'{label}: status change produced no notification')
        return notice, t0

    def wait_tray(self, nid, timeout=MAX_WAIT):
        end = now() + timeout
        while now() < end:
            if nid in self.tray(): return True
            time.sleep(1)
        return False

    def wait_delivered(self, nid, timeout=MAX_WAIT):
        end = now() + timeout
        while now() < end:
            o = self.outbox(nid)
            if o and o['d'] in (True, 't', 'true'): return o
            time.sleep(3)
        return self.outbox(nid)

    def record(self, case, **kw):
        kw['case'] = case; self.matrix.append(kw); print(json.dumps(kw)); sys.stdout.flush()

    def home(self): adb(self.s, 'shell', 'input keyevent KEYCODE_HOME')
    def launch(self): adb(self.s, 'shell', f'am start -n {PKG}/.MainActivity')

    def deliver_and_tap(self, case, status, setup=None, expect_tray=True, rotate=False):
        if setup: setup()
        notice, t0 = self.change(status, case)
        nid = notice['id']
        out = adb(self.s, 'shell', 'true')  # keep adb warm
        seen = self.wait_tray(nid, MAX_WAIT if expect_tray else 45)
        t_seen = now() - t0
        ob = self.wait_delivered(nid, 60)
        res = dict(notification_in_tray=seen, tray_observed_seconds=round(t_seen, 1) if seen else None,
                   fcm_accepted_by_outbox=bool(ob and ob['d'] in (True, 't', 'true')), outbox_attempts=ob and ob['a'],
                   outbox_error=ob and ob['e'], created_to_dispatch_seconds=ob and ob['lat'], notification_id_correlated=seen)
        opened = None
        if seen:
            adb(self.s, 'shell', 'cmd statusbar expand-notifications')
            time.sleep(2)
            opened = self.tap_text('Report updated') or self.tap_text(notice['message'][:20])
            res['tap_found_notification'] = bool(opened)
            if opened:
                res['details_show_fixture'] = self.screen_has('Report Details', 20) and self.screen_has(self.marker[:20], 10)
                if rotate and res['details_show_fixture']:
                    adb(self.s, 'shell', 'settings put system accelerometer_rotation 0; settings put system user_rotation 1')
                    time.sleep(3)
                    res['details_after_rotation_recreate'] = self.screen_has(self.marker[:20], 15)
                    adb(self.s, 'shell', 'settings put system user_rotation 0; settings put system accelerometer_rotation 1')
                    time.sleep(2)
            adb(self.s, 'shell', 'cmd statusbar collapse')
        self.record(case, status=status, **res)
        return nid, seen

    # ---------- run ----------
    def run(self):
        cfg = props()
        if cfg.get('SUPABASE_URL') != 'https://jaysxjjdvaitgrbqjlhe.supabase.co': raise SystemExit('Unexpected project.')
        if adb(self.s, 'get-state').strip() != 'device': raise SystemExit('Emulator not ready.')
        if self.devices(self.uid): raise SystemExit('The official already has a device registration; real push would not be provably isolated.')
        self.cfp = self.citizen_fp()
        base = {k: sql(q)[0]['c'] for k, q in {
            'reports': 'select count(*) c from public.reports', 'notifications': 'select count(*) c from public.notifications',
            'outbox': 'select count(*) c from private.push_outbox', 'history': 'select count(*) c from public.report_history',
            'devices': 'select count(*) c from public.devices'}.items()}
        ledger = os.path.join(self.a.work, 'push-ledger.json')
        with open(ledger, 'x') as f:
            json.dump({'intent': 'one official-owned fixture report; status changes; one emulator registration', 'report_id': self.report_id,
                       'marker': self.marker, 'baseline': base, 'citizen_device_fp': self.cfp, 'status': 'intent-recorded'}, f, indent=1)
        print('baseline', base, 'official registrations: 0; citizen rows:', self.cfp['n'])
        try:
            adb(self.s, 'shell', f'pm grant {PKG} android.permission.POST_NOTIFICATIONS')
            self.instrument('signin')
            self.wait_registration()
            self.installation = self.installed_id()
            if not self.installation or [r['i'] for r in self.devices(self.uid)] != [self.installation]:
                raise SystemExit('Registration did not appear exactly once for this emulator.')
            self.record('registration', official_rows=1, emulator_row_only=True, citizen_rows_unchanged=self.citizen_fp() == self.cfp)
            self.rpc('submit_report', {'p_id': self.report_id, 'p_category': 'Road Damage', 'p_description': self.marker,
                                       'p_location': 'PUSHFCM probe location', 'p_barangay': 'Alangilan'})
            self.record('fixture', report_created=True)
            self.launch(); time.sleep(4)
            self.deliver_and_tap('1 foreground', 'Under Review')
            # 2: activity destroyed (BACK from the root), then delivery, tap into a cold activity, rotation recreate
            def destroy():
                self.launch(); time.sleep(3)
                adb(self.s, 'shell', 'input keyevent KEYCODE_BACK'); time.sleep(2)
            self.deliver_and_tap('2 background after activity destroyed + recreation', 'In Progress', setup=destroy, rotate=True)
            # 3: permission denied, then granted
            def deny():
                adb(self.s, 'shell', f'pm revoke {PKG} android.permission.POST_NOTIFICATIONS'); time.sleep(3)
            nid, seen = self.deliver_and_tap('3a notification permission denied', 'Resolved', setup=deny, expect_tray=False)
            self.launch(); time.sleep(3)
            adb(self.s, 'shell', 'cmd statusbar collapse')
            in_app = self.tap_text('Notifications', 10) and self.screen_has('is resolved', 15)
            self.record('3a in-app history while denied', in_app_notification_visible=bool(in_app))
            adb(self.s, 'shell', f'pm grant {PKG} android.permission.POST_NOTIFICATIONS'); time.sleep(3)
            self.home()
            self.deliver_and_tap('3b permission granted', 'Under Review')
            # 4: force-stop negative control
            def stop(): adb(self.s, 'shell', f'am force-stop {PKG}'); time.sleep(2)
            nid, seen = self.deliver_and_tap('4 force-stopped negative control', 'In Progress', setup=stop, expect_tray=False)
            stopped = 'stopped=true' in adb(self.s, 'shell', f'dumpsys package {PKG}')
            self.record('4 observation', package_stopped=stopped, note='No delivery is promised while force-stopped; not a pass/fail target')
            # 5: sign-out cleanup
            self.launch(); time.sleep(3)
            before_tray = self.tray()
            self.instrument('signout')
            time.sleep(3)
            self.record('5a sign-out cleanup', tray_before=len(before_tray), tray_after=len(self.tray()),
                        official_device_rows_after=len(self.devices(self.uid)), citizen_rows_unchanged=self.citizen_fp() == self.cfp)
            ok_signed_out = not self.devices(self.uid)
            if ok_signed_out:
                notice = None
                _, rows = call('GET', f'/rest/v1/notifications?report_id=eq.{self.report_id}&select=id', self.session())
                before = {r['id'] for r in rows}
                self.rpc('update_report_status', {'p_report_id': self.report_id, 'p_status': 'Under Review'})
                notice = self.new_notice(before)
                ob = self.wait_delivered(notice['id'], 60)
                self.record('5b status change while signed out (no devices)', tray_has_notice=notice['id'] in self.tray(),
                            outbox_marked_delivered=bool(ob and ob['d'] in (True, 't', 'true')), device_rows=len(self.devices(self.uid)))
            # 6: sign-in recovery
            self.instrument('signin')
            self.wait_registration()
            self.installation = self.installed_id()
            self.record('6a device-row recovery', official_rows=len(self.devices(self.uid)),
                        same_installation=[r['i'] for r in self.devices(self.uid)] == [self.installation])
            self.home()
            self.deliver_and_tap('6b delivery after recovery', 'Resolved')
        finally:
            self.finish(ledger)

    def finish(self, ledger, instrument=True):
        """Sign out the app session locally, then remove only the fixture after its outbox rows are delivered."""
        if instrument:
            try: self.instrument('signout')
            except SystemExit as e: print('signout phase:', e)
        adb(self.s, 'shell', 'settings put system user_rotation 0; settings put system accelerometer_rotation 1')
        adb(self.s, 'shell', f'pm grant {PKG} android.permission.POST_NOTIFICATIONS')
        if self.token:
            try: sign_out_local(self.token)
            except Exception: pass
        led = json.load(open(ledger)); led['status'] = 'run-finished'; self.cfp = led['citizen_device_fp']
        try:
            pending = sql(f"select count(*) c from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id='{self.report_id}'::uuid and o.delivered_at is null")[0]['c']
            end = now() + 120
            while int(pending) and now() < end:
                time.sleep(5)
                pending = sql(f"select count(*) c from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id='{self.report_id}'::uuid and o.delivered_at is null")[0]['c']
            if int(pending):
                led['status'] = 'CLEANUP-DEFERRED-outbox-not-delivered'; print('outbox still pending; fixture preserved')
            else:
                q = (f"begin; do $$ begin if (select count(*) from public.reports where id='{self.report_id}'::uuid and owner_id='{self.uid}'::uuid "
                     f"and description='{self.marker}')<>1 then raise exception 'fixture identity mismatch'; end if; "
                     f"if exists(select 1 from public.notifications where report_id='{self.report_id}'::uuid and user_id<>'{self.uid}'::uuid) "
                     f"or exists(select 1 from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id='{self.report_id}'::uuid and o.delivered_at is null) "
                     f"or exists(select 1 from public.report_votes where report_id='{self.report_id}'::uuid) "
                     f"then raise exception 'unexpected fixture references; cleanup refused'; end if; "
                     f"delete from public.notifications where report_id='{self.report_id}'::uuid; "
                     f"delete from public.report_history where report_id='{self.report_id}'::uuid; "
                     f"delete from public.reports where id='{self.report_id}'::uuid; end $$; commit;")
                sql(q, write=True)
                after = {k: sql(qq)[0]['c'] for k, qq in {
                    'reports': 'select count(*) c from public.reports', 'notifications': 'select count(*) c from public.notifications',
                    'outbox': 'select count(*) c from private.push_outbox', 'history': 'select count(*) c from public.report_history',
                    'devices': 'select count(*) c from public.devices'}.items()}
                led['after'] = after
                led['status'] = 'cleaned' if after == led['baseline'] and self.citizen_fp() == self.cfp and not self.devices(self.uid) else 'CLEANUP-MISMATCH'
        finally:
            json.dump(led, open(ledger, 'w'), indent=1)
            json.dump(self.matrix, open(os.path.join(self.a.work, 'push-matrix.json'), 'w'), indent=1)
            print('cleanup:', led['status'], led.get('after'))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--cleanup-only', action='store_true', help='remove the fixture recorded in an existing ledger in --work')
    p.add_argument('--credentials', required=True); p.add_argument('--work', required=True); p.add_argument('--serial', required=True)
    a = p.parse_args()
    if not SERIAL_RE.fullmatch(a.serial): raise SystemExit('Use an isolated emulator serial.')
    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO: raise SystemExit('Use a private --work directory outside the repository.')
    os.makedirs(work, exist_ok=True); a.work = work
    creds = json.load(open(a.credentials, encoding='utf-8-sig'))
    if a.cleanup_only:
        ledger = os.path.join(work, 'push-ledger.json'); led = json.load(open(ledger))
        a.report_id = str(uuid.UUID(led['report_id'])); a.run_id = 'cleanup'
        pr = Probe(a, creds); pr.marker = led['marker']; pr.cfp = led['citizen_device_fp']
        pr.finish(ledger, instrument=False); return
    a.report_id = str(uuid.uuid4()); a.run_id = f'{RUN}-{a.report_id[:6]}'
    Probe(a, creds).run()


if __name__ == '__main__':
    main()
