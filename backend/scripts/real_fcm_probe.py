#!/usr/bin/env python3
"""Real FCM delivery probe on ONE isolated emulator, with an independent hosted oracle and guarded cleanup.

Route under test (deployed, nothing added): ordinary report -> assigned-official status RPC -> history/notification/
push_outbox rows -> pg_cron dispatcher -> deliver-push Edge Function -> Google FCM -> emulator
PinPointMessagingService -> tray notification -> tap -> report details.

Recipient isolation: only admin-labelled permanent fixture identities are accepted. The fixture CITIZEN owns the
report and is signed in on the emulator; the separate Alangilan OFFICIAL performs status changes through REST.
Both must start with zero registrations. Every send rechecks labels/roles, report ownership, exactly the expected
recipient registration (or none in the signed-out control), zero actor registrations and every non-fixture device
row's fingerprint. Shared demo/phone accounts are never a recipient. Any drift aborts before sending.

Cases (each recorded with observed checkpoints; timestamps are observations, not CP-25 latency acceptance):
  foreground, background after activity destruction + rotation recreation, notification permission denied then
  granted, force-stop negative control, sign-out cleanup, sign-in device-row recovery.
Simulated-only cases (duplicate delivery, late payload after account switch) live in the local fault tests.

Secrets, tokens, FCM tokens and installation ids are never printed or stored. Use a fresh private --work directory.
"""
import argparse, hashlib, json, os, re, subprocess, sys, time, uuid
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hosted_safety as safety  # noqa: E402
from hosted_authz_probe import REPO, call, sign_in  # noqa: E402
from map_nav_hosted import ADB, PKG, props, shell_quote, sql  # noqa: E402

RUN = time.strftime('%Y%m%d', time.gmtime())
SERIAL_RE = re.compile(r'emulator-\d+')
MAX_WAIT = 150

TABLES = {'reports': 'public.reports', 'notifications': 'public.notifications', 'history': 'public.report_history',
          'outbox': 'private.push_outbox', 'votes': 'public.report_votes', 'devices': 'public.devices',
          'objects': 'storage.objects', 'buckets': 'storage.buckets', 'sms': 'private.sms_receipts',
          'profiles': 'public.profiles', 'officials': 'private.officials', 'barangays': 'private.barangays'}


def snapshot():
    return sql(' union all '.join(
        f"select '{name}' name,count(*)::int count,md5(coalesce(string_agg(to_jsonb(t)::text,',' order by to_jsonb(t)::text),'')) digest from {table} t"
        for name, table in TABLES.items()))


def adb(serial, *args, check=False):
    r = subprocess.run([ADB, '-s', serial, *args], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=150)
    if check and r.returncode: raise SystemExit(f'adb {args[0]} failed')
    return r.stdout


def now(): return time.monotonic()


class Probe:
    def __init__(self, a, creds):
        if creds.get('fixture') is not True: raise ValueError('Use dedicated fixture credentials, never shared demo accounts.')
        for role in ('citizen', 'official'):
            if not safety.valid_uuid(creds[role]['uid']): raise ValueError('Invalid fixture account identifier.')
        if creds['citizen']['uid'] == creds['official']['uid']: raise ValueError('Reporter and official must be distinct.')
        self.a, self.creds, self.s = a, creds, a.serial
        self.recipient = creds['citizen']; self.official = creds['official']
        self.uid = self.recipient['uid']; self.ouid = self.official['uid']
        self.matrix = []; self.report_id = a.report_id; self.marker = f'PUSHFCM-{a.run_id} real FCM probe fixture'
        if not safety.valid_uuid(self.report_id): raise ValueError('Invalid report identifier.')
        self.token = None; self.recipient_token = None; self.installation = None
        self.sessions = safety.SessionRegistry(call); self.exit_code = 1; self.test_ok = False; self.ledger = None

    # ---------- hosted oracle (privileged, read-only) ----------
    def devices(self, uid):
        return sql(f"select installation_id::text i from public.devices where user_id='{uid}'::uuid order by 1")

    def protected_fp(self):
        return sql(f"select md5(coalesce(string_agg(to_jsonb(d)::text,',' order by installation_id),'')) f,count(*) n from public.devices d where user_id not in ('{self.uid}'::uuid,'{self.ouid}'::uuid)")[0]

    def validate_pair(self):
        rows = sql(f"select u.id::text id,u.raw_app_meta_data->>'pinpoint_fixture'='true' fixture,u.email_confirmed_at is not null confirmed,"
                   f"(select barangay from private.officials o where o.user_id=u.id) area from auth.users u where id in ('{self.uid}'::uuid,'{self.ouid}'::uuid)")
        by_id = {r['id']: r for r in rows}
        if set(by_id) != {self.uid, self.ouid} or not all(r['fixture'] is True and r['confirmed'] is True for r in rows):
            raise SystemExit('Both accounts must be confirmed, admin-labelled project fixtures.')
        if by_id[self.uid]['area'] is not None or by_id[self.ouid]['area'] != 'Alangilan':
            raise SystemExit('Expected a fixture citizen and a distinct assigned Alangilan official.')

    def verify_report(self):
        rows = sql(f"select owner_id::text owner,description,barangay from public.reports where id='{self.report_id}'::uuid")
        if rows != [{'owner': self.uid, 'description': self.marker, 'barangay': 'Alangilan'}]:
            raise SystemExit('Report is not this run\'s citizen-owned fixture; aborting before send.')

    def guard(self, label, registered=True):
        """Abort before any send unless the recipient is exclusively this emulator."""
        self.validate_pair(); self.verify_report()
        rows = [r['i'] for r in self.devices(self.uid)]
        if not self.installation: raise SystemExit(f'{label}: emulator installation unknown')
        if rows != ([self.installation] if registered else []):
            raise SystemExit(f'{label}: unexpected recipient device registration; aborting before send')
        if self.devices(self.ouid): raise SystemExit(f'{label}: actor has a device registration; aborting before send')
        if self.protected_fp() != self.cfp: raise SystemExit(f'{label}: protected device rows changed; aborting before send')
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
        if phase == 'signin': cmd += ['-e', 'email', self.recipient['email'], '-e', 'password', self.recipient['password']]
        cmd += [f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner']
        out = adb(self.s, 'shell', ' '.join(shell_quote(c) for c in cmd))
        for role in ('citizen', 'official'):
            out = out.replace(self.creds[role]['password'], '[redacted]').replace(self.creds[role]['email'], '[fixture email]')
        if 'OK (1 test)' not in out: raise SystemExit(f'setup phase {phase} failed: ' + out[-800:])

    def wait_registration(self):
        """The registration worker may be pending when the instrumentation process ends; opening the app resumes it."""
        end = now() + 20
        while now() < end and not self.devices(self.uid): time.sleep(2)
        if not self.devices(self.uid):
            self.launch(); end = now() + 90
            while now() < end and not self.devices(self.uid): time.sleep(2)

    def readiness(self):
        cmd = ['am', 'instrument', '-w', '-r', '-e', 'class', 'com.example.pinpoint.PushReadinessTest',
               '-e', 'push_readiness', 'true', '-e', 'expected_user', self.uid,
               f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner']
        out = adb(self.s, 'shell', ' '.join(shell_quote(c) for c in cmd))
        if 'OK (1 test)' not in out: raise SystemExit('Push readiness diagnostic failed.')
        info = json.loads(adb(self.s, 'shell', f'run-as {PKG} cat files/push-readiness.json'))
        token_matches = sql(f"select md5(token) digest from public.devices where user_id='{self.uid}'::uuid") == [{'digest': info.pop('token_md5')}]
        self.expect('push readiness', token_matches and all(info[k] is True for k in (
            'expected_user_matches', 'configured_backend_matches', 'notifications_enabled', 'permission_granted'))
            and info['channel_importance'] != 0, registered_token_matches=token_matches, **info)

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
        # cmd lists ACTIVE keys only; dumpsys includes diagnostic/enqueued/historical sections too.
        out = adb(self.s, 'shell', 'cmd notification list', check=True)
        return re.findall(r'^\d+\|' + re.escape(PKG) + r'\|-?\d+\|([^|\r\n]+)\|', out, re.MULTILINE)

    def permission_denied(self):
        state = re.findall(r'^\s*android\.permission\.POST_NOTIFICATIONS:\s*granted=(true|false)',
                           adb(self.s, 'shell', f'dumpsys package {PKG}', check=True), re.MULTILINE)
        if len(state) != 1: raise AssertionError('Notification permission state is ambiguous.')
        return state[0] == 'false'

    # ---------- hosted actions as the ORDINARY official session ----------
    def session(self):
        if not self.token:
            token, uid = sign_in(self.official['email'], self.official['password'])
            self.token = self.sessions.add('official', token)
            if uid != self.ouid: raise SystemExit('Actor credentials do not match the fixture identity.')
        return self.token

    def reporter_session(self):
        if not self.recipient_token:
            token, uid = sign_in(self.recipient['email'], self.recipient['password'])
            self.recipient_token = self.sessions.add('citizen', token)
            if uid != self.uid: raise SystemExit('Reporter credentials do not match the fixture identity.')
        return self.recipient_token

    def rpc(self, name, body):
        if name not in ('submit_report', 'update_report_status'): raise ValueError('Unexpected probe RPC.')
        token = self.reporter_session() if name == 'submit_report' else self.session()
        status, data = call('POST', f'/rest/v1/rpc/{name}', token, body)
        if status >= 300: raise SystemExit(f'{name} -> HTTP {status}')
        return data

    def new_notice(self, before):
        status, rows = call('GET', f'/rest/v1/notifications?report_id=eq.{self.report_id}&select=id,message,created_at&order=created_at.desc', self.reporter_session())
        if status != 200: raise SystemExit('Recipient notification query failed.')
        ids = [r for r in rows if r['id'] not in before]
        return ids[0] if ids else None

    def change(self, status, label, registered=True):
        response, rows = call('GET', f'/rest/v1/notifications?report_id=eq.{self.report_id}&select=id', self.reporter_session())
        if response != 200: raise SystemExit('Recipient notification query failed.')
        before = {r['id'] for r in rows}
        self.session()  # authenticate the actor before the final isolation check
        self.guard(label, registered=registered)  # last operation before the mutation
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

    def expect(self, case, passed, **kw):
        self.record(case, passed=bool(passed), **kw)
        if not passed: raise AssertionError(f'Probe expectation failed: {case}')

    def home(self): adb(self.s, 'shell', 'input keyevent KEYCODE_HOME')
    def launch(self): adb(self.s, 'shell', f'am start -n {PKG}/.MainActivity')

    def deliver_and_tap(self, case, status, setup=None, expect_tray=True, rotate=False):
        self.instrument('clear-tray')
        if self.tray(): raise AssertionError('Owned tray could not be cleared before this case.')
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
            self.expect(case + ' isolated tray', self.tray() == [nid], expected_notice_is_only_owned_tray_entry=self.tray() == [nid])
            adb(self.s, 'shell', 'cmd statusbar expand-notifications')
            time.sleep(2)
            opened = self.tap_text(notice['message'])
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
        passed = seen == expect_tray and res['fcm_accepted_by_outbox']
        if expect_tray:
            passed = passed and bool(opened) and res.get('details_show_fixture', False)
            if rotate: passed = passed and res.get('details_after_rotation_recreate', False)
        self.expect(case, passed, status=status, **res)
        return nid, seen

    # ---------- run ----------
    def run(self):
        cfg = props()
        if cfg.get('SUPABASE_URL') != 'https://jaysxjjdvaitgrbqjlhe.supabase.co': raise SystemExit('Unexpected project.')
        if not cfg.get('SUPABASE_PUBLISHABLE_KEY', '').startswith('sb_publishable_'): raise SystemExit('Use the public publishable key.')
        self.validate_pair()
        if self.devices(self.uid) or self.devices(self.ouid):
            raise SystemExit('Fixture identities already have registrations; refusing to use another device.')
        if adb(self.s, 'get-state').strip() != 'device': raise SystemExit('Emulator not ready.')
        self.cfp = self.protected_fp()
        baseline = snapshot(); base = {r['name']: r['count'] for r in baseline}
        ledger = os.path.join(self.a.work, 'push-ledger.json')
        self.ledger = safety.RunLedger(ledger, intent='one citizen-owned fixture report; distinct official actor; one emulator registration',
                       report_id=self.report_id, recipient=self.uid, actor=self.ouid, marker=self.marker, baseline=base,
                       baseline_fingerprints=baseline, protected_device_fp=self.cfp, device_precheck='clean', status='intent-recorded')
        print('baseline', base, '| fixture registrations: 0; protected registrations:', self.cfp['n'])
        try:
            adb(self.s, 'shell', f'pm grant {PKG} android.permission.POST_NOTIFICATIONS')
            self.instrument('signin')
            self.wait_registration()
            self.installation = self.installed_id()
            if not self.installation or [r['i'] for r in self.devices(self.uid)] != [self.installation]:
                raise SystemExit('Registration did not appear exactly once for this emulator.')
            self.ledger.update(installation_hash=hashlib.sha256(self.installation.encode()).hexdigest())
            self.record('registration', recipient_rows=1, emulator_row_only=True, protected_rows_unchanged=self.protected_fp() == self.cfp)
            self.readiness()
            self.rpc('submit_report', {'p_id': self.report_id, 'p_category': 'Road Damage', 'p_description': self.marker,
                                       'p_location': 'PUSHFCM probe location', 'p_barangay': 'Alangilan'})
            self.verify_report()
            self.record('fixture', report_created=True)
            self.launch(); time.sleep(4)
            self.deliver_and_tap('1 foreground', 'Under Review')
            # 2: explicitly finish every activity and observe DESTROYED, then real delivery/tap and rotation.
            def destroy():
                self.instrument('destroy')
                self.record('activity destruction', observed_destroyed=True)
            self.deliver_and_tap('2 background after activity destroyed + recreation', 'In Progress', setup=destroy, rotate=True)
            # 3: permission denied, then granted
            def deny():
                adb(self.s, 'shell', f'pm revoke {PKG} android.permission.POST_NOTIFICATIONS'); time.sleep(3)
            nid, seen = self.deliver_and_tap('3a notification permission denied', 'Resolved', setup=deny, expect_tray=False)
            self.launch(); time.sleep(4)
            adb(self.s, 'shell', 'cmd statusbar collapse')
            # Answer a real prompt when Android shows one. Repeated denials may legitimately suppress that prompt.
            dialog_denied = self.tap_text("Don't allow", 6) or self.tap_text("Don\u2019t allow", 4)
            in_app = self.tap_text('Notifications', 10) and self.screen_has('is resolved', 15)
            os_denied = self.permission_denied()
            extra = {} if in_app else {'visible_text': [f'{pk}: {t or d}' for t, d, _, pk in self.ui() if (t or d) and 'systemui' not in pk][:25]}
            self.expect('3a in-app history while denied', os_denied and bool(in_app) and nid not in self.tray(), system_permission_dialog_denied=bool(dialog_denied), os_permission_denied=os_denied,
                        in_app_notification_visible=bool(in_app), tray_empty_while_denied=nid not in self.tray(), **extra)
            adb(self.s, 'shell', f'pm grant {PKG} android.permission.POST_NOTIFICATIONS'); time.sleep(3)
            self.home()
            self.deliver_and_tap('3b permission granted', 'Under Review')
            # 4: force-stop negative control
            def stop(): adb(self.s, 'shell', f'am force-stop {PKG}'); time.sleep(2)
            nid, seen = self.deliver_and_tap('4 force-stopped negative control', 'In Progress', setup=stop, expect_tray=False)
            stopped = 'stopped=true' in adb(self.s, 'shell', f'dumpsys package {PKG}')
            self.expect('4 observation', stopped and not seen, package_stopped=stopped, note='Bounded negative control; no delivery is promised while force-stopped')
            # 5: sign-out cleanup
            self.launch(); time.sleep(3)
            before_tray = self.tray()
            self.instrument('signout')
            time.sleep(3)
            self.expect('5a sign-out cleanup', not self.tray() and not self.devices(self.uid) and self.protected_fp() == self.cfp,
                        tray_before=len(before_tray), tray_after=len(self.tray()),
                        recipient_device_rows_after=len(self.devices(self.uid)), protected_rows_unchanged=self.protected_fp() == self.cfp)
            ok_signed_out = not self.devices(self.uid)
            if ok_signed_out:
                notice, _ = self.change('Under Review', '5b signed out', registered=False)
                ob = self.wait_delivered(notice['id'], 60)
                self.expect('5b status change while signed out (no devices)', notice['id'] not in self.tray() and not self.devices(self.uid)
                            and bool(ob and ob['d'] in (True, 't', 'true')), tray_has_notice=notice['id'] in self.tray(),
                            outbox_marked_delivered=bool(ob and ob['d'] in (True, 't', 'true')), device_rows=len(self.devices(self.uid)))
            # 6: sign-in recovery
            self.instrument('signin')
            self.wait_registration()
            recovered = self.installed_id()
            self.expect('6a device-row recovery', recovered == self.installation and [r['i'] for r in self.devices(self.uid)] == [self.installation],
                        recipient_rows=len(self.devices(self.uid)), same_installation=recovered == self.installation)
            self.home()
            self.deliver_and_tap('6b delivery after recovery', 'Resolved')
            self.test_ok = True
        except BaseException as error:
            self.ledger.error('probe', error)
        finally:
            self.finish(ledger)

    def finish(self, ledger, instrument=True):
        """Fixture cleanup and session cleanup are separate outcomes. Every step is attempted; none hides another."""
        self.ledger = safety.RunLedger.load(ledger); led = self.ledger.data
        if led.get('recipient') != self.uid or led.get('actor') != self.ouid or led.get('device_precheck') != 'clean':
            raise SystemExit('Ledger does not prove ownership of this fixture pair.')
        led['status'] = 'run-finished'; self.cfp = led['protected_device_fp']
        def app_signout():
            if instrument:
                try: self.instrument('signout'); led['app_signout'] = 'ui-signout-ok'
                except SystemExit as error: led['app_signout'] = 'UNRESOLVED-' + str(error)[:120]; raise
            else: led['app_signout'] = 'not-attempted'
        def restore_settings():
            adb(self.s, 'shell', 'settings put system user_rotation 0; settings put system accelerometer_rotation 1')
            adb(self.s, 'shell', f'pm grant {PKG} android.permission.POST_NOTIFICATIONS')
        def fixture():
            q = lambda: sql(f"select count(*) c from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id='{self.report_id}'::uuid and o.delivered_at is null")[0]['c']
            pending = q(); end = now() + 120
            while int(pending) and now() < end: time.sleep(5); pending = q()
            if int(pending): led['fixture_cleanup'] = 'DEFERRED-outbox-not-delivered'; print('outbox still pending; fixture preserved'); return
            if int(sql(f"select count(*) c from public.reports where id='{self.report_id}'::uuid")[0]['c']):
                sql(self.cleanup_statement(), write=True)
            led['fixture_cleanup'] = 'report-removed'
        def registrations():
            self.validate_pair()
            if not self.devices(self.uid) and not self.devices(self.ouid):
                led['fixture_devices'] = 'removed'; return
            installation = self.installation or self.installed_id()
            if not installation or hashlib.sha256(installation.encode()).hexdigest() != led.get('installation_hash'):
                raise RuntimeError('Cannot prove ownership of a remaining registration.')
            sql(self.device_cleanup_statement(installation), write=True)
            led['fixture_devices'] = 'removed' if not self.devices(self.uid) and not self.devices(self.ouid) else 'FAILED-registrations-remain'
        def preservation():
            after = snapshot(); led['after'] = {r['name']: r['count'] for r in after}; led['after_fingerprints'] = after
            if after != led['baseline_fingerprints'] or self.protected_fp() != self.cfp:
                raise RuntimeError('Baseline fingerprints changed; inspect the private ledger.')
            if led.get('fixture_cleanup') == 'report-removed' and led.get('fixture_devices') == 'removed':
                led['fixture_cleanup'] = 'cleaned'
        def sessions():
            self.sessions.teardown()
            if self.sessions.outcomes or instrument:
                led['session_cleanup'] = self.sessions.summary()
                led['sessions'] = {r: {'outcome': o, 'detail': d} for r, (o, d) in self.sessions.outcomes.items()}
        led.setdefault('fixture_cleanup', 'not-started'); led.setdefault('session_cleanup', 'not-started')
        results = safety.run_all([('app-signout', app_signout), ('restore-emulator-settings', restore_settings), ('fixture-cleanup', fixture),
                                  ('fixture-registration-cleanup', registrations), ('preservation', preservation), ('rest-session-teardown', sessions)])
        led['cleanup_errors'] = [{'step': n, 'error': type(e).__name__ + ': ' + str(e)[:160]} for n, e in results if e is not None]
        if led['fixture_cleanup'] == 'not-started' and any(n == 'fixture-cleanup' for n, e in results if e): led['fixture_cleanup'] = 'FAILED-needs-review'
        for name in ('session_cleanup',):
            if name not in led or led[name] == 'not-started': led[name] = self.sessions.summary()
        led['test_ok'] = self.test_ok if instrument else led.get('test_ok', False)
        self.ledger.update(**led)
        if instrument:
            with open(os.path.join(self.a.work, 'push-matrix.json'), 'w', encoding='utf-8') as stream:
                json.dump(self.matrix, stream, indent=1)
        print('fixture cleanup:', led['fixture_cleanup'], led.get('after'), '| session cleanup:', led['session_cleanup'], '| app sign-out:', led.get('app_signout'))
        app_ok = led.get('app_signout') == 'ui-signout-ok' if instrument else True
        self.exit_code = safety.final_exit(led['fixture_cleanup'] == 'cleaned', led['session_cleanup'],
                                           (self.test_ok if instrument else True) and app_ok and not led['cleanup_errors'])

    def cleanup_statement(self):
        if not re.fullmatch(r'PUSHFCM-[A-Za-z0-9-]+ real FCM probe fixture', self.marker): raise ValueError('Unexpected run marker.')
        return (f"begin; do $$ begin perform 1 from public.reports where id='{self.report_id}'::uuid for update; "
                f"if (select count(*) from auth.users where id in ('{self.uid}'::uuid,'{self.ouid}'::uuid) and raw_app_meta_data->>'pinpoint_fixture'='true')<>2 "
                f"then raise exception 'fixture labels changed'; end if; "
                f"if (select count(*) from public.reports where id='{self.report_id}'::uuid and owner_id='{self.uid}'::uuid "
                f"and description='{self.marker}' and barangay='Alangilan' and category='Road Damage' and source='app' and vote_count=0 "
                f"and photo_path is null and original_path is null)<>1 then raise exception 'fixture identity mismatch'; end if; "
                f"if exists(select 1 from public.notifications where report_id='{self.report_id}'::uuid and user_id<>'{self.uid}'::uuid) "
                f"or exists(select 1 from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id='{self.report_id}'::uuid and o.delivered_at is null) "
                f"or exists(select 1 from public.report_votes where report_id='{self.report_id}'::uuid) "
                f"or exists(select 1 from public.report_history where report_id='{self.report_id}'::uuid and changed_by is distinct from '{self.ouid}'::uuid) "
                f"or exists(select 1 from private.sms_receipts where report_id='{self.report_id}'::uuid) "
                f"or exists(select 1 from storage.objects where split_part(name,'/',2)='{self.report_id}') "
                f"then raise exception 'unexpected fixture references; cleanup refused'; end if; "
                f"delete from public.notifications where report_id='{self.report_id}'::uuid; "
                f"delete from public.report_history where report_id='{self.report_id}'::uuid; "
                f"delete from public.reports where id='{self.report_id}'::uuid; end $$; commit;")

    def device_cleanup_statement(self, installation):
        if not safety.valid_uuid(installation): raise ValueError('Invalid owned emulator installation.')
        return (f"begin; do $$ begin if (select count(*) from auth.users where id in ('{self.uid}'::uuid,'{self.ouid}'::uuid) "
                f"and raw_app_meta_data->>'pinpoint_fixture'='true')<>2 then raise exception 'fixture labels changed'; end if; "
                f"if exists(select 1 from public.devices where user_id='{self.ouid}'::uuid or (user_id='{self.uid}'::uuid "
                f"and installation_id<>'{installation}'::uuid)) then raise exception 'unexpected fixture registration'; end if; "
                f"delete from public.devices where user_id='{self.uid}'::uuid and installation_id='{installation}'::uuid; end $$; commit;")


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--cleanup-only', action='store_true', help='remove the fixture recorded in an existing ledger in --work')
    p.add_argument('--credentials', required=True); p.add_argument('--work', required=True); p.add_argument('--serial', required=True)
    a = p.parse_args()
    if not SERIAL_RE.fullmatch(a.serial): raise SystemExit('Use an isolated emulator serial.')
    work = os.path.realpath(a.work)
    for private in (work, os.path.realpath(a.credentials)):
        if os.path.commonpath([os.path.normcase(private), os.path.normcase(os.path.realpath(REPO))]) == os.path.normcase(os.path.realpath(REPO)):
            raise SystemExit('Keep credentials and work/evidence outside the repository.')
    os.makedirs(work, exist_ok=True); a.work = work
    if not a.cleanup_only and any(os.path.exists(os.path.join(work, name)) for name in ('push-ledger.json', 'push-matrix.json')):
        raise SystemExit('Preserve the existing run; use a fresh private work directory or explicit cleanup-only.')
    with open(a.credentials, encoding='utf-8-sig') as stream:
        creds = json.load(stream)
    if a.cleanup_only:
        ledger = os.path.join(work, 'push-ledger.json'); led = json.load(open(ledger))
        a.report_id = str(uuid.UUID(led['report_id'])); a.run_id = 'cleanup'
        pr = Probe(a, creds); pr.marker = led['marker']; pr.cfp = led['protected_device_fp']
        pr.finish(ledger, instrument=False); return pr.exit_code
    a.report_id = str(uuid.uuid4()); a.run_id = f'{RUN}-{a.report_id[:6]}'
    probe = Probe(a, creds)
    try: probe.run()
    finally: pass
    return getattr(probe, 'exit_code', 1)


if __name__ == '__main__':
    sys.exit(main())
