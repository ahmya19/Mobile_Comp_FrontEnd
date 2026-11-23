#!/usr/bin/env python3
"""Real FCM delivery to the user's PHYSICAL phone (released by the user for this run), with a hosted oracle and cleanup.

A labelled fixture report is created by the demo CITIZEN's ordinary session; the demo OFFICIAL's ordinary session changes
its status (a genuine reporter/official pair). The notification goes to the citizen's registered devices (the phone).
The phone is left as found: signed in, permission granted, no sign-out, no setting changes except screen sleep/wake
key events and one BACK press. Guards before every send: the phone's installation is among the citizen's device rows and
no row was added. NOTE: the dispatcher deletes a device row whose FCM token Google reports UNREGISTERED (normal server
hygiene); any such removal is recorded, not prevented.

Observations are not CP-25 latency acceptance. Secrets, tokens, FCM tokens and installation IDs are never printed.
"""
import argparse, json, os, re, sys, time, uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hosted_authz_probe import REPO, call, sign_in  # noqa: E402
from map_nav_hosted import PKG, props, sql  # noqa: E402
from real_fcm_probe import Probe, adb, now, MAX_WAIT  # noqa: E402


class Phone(Probe):
    def __init__(self, a, creds):
        super().__init__(a, creds)
        self.ctoken = None; self.cuid = self.uid
        self.marker = f'PUSHFCM-PHONE-{a.report_id[:6]} real FCM phone probe fixture'

    def csession(self):
        if not self.ctoken: self.ctoken = self.sessions.add('citizen', sign_in(self.creds['citizen']['email'], self.creds['citizen']['password'])[0])
        return self.ctoken

    def rows(self): return [r['i'] for r in self.devices(self.cuid)]

    def guard(self, label):
        rows = self.rows()
        if self.installation not in rows: raise SystemExit(f'{label}: phone installation not registered for the citizen')
        if not set(rows) <= set(self.base_rows):
            raise SystemExit(f'{label}: a new citizen device row appeared; aborting before send')
        if self.devices(self.ouid): raise SystemExit(f'{label}: unexpected official registration')
        if self.protected_fp() != self.cfp: raise SystemExit(f'{label}: protected (shared demo) device rows changed; aborting before send')

    def new_notice(self, before):
        status, rows = call('GET', f'/rest/v1/notifications?report_id=eq.{self.report_id}&select=id,message,created_at&order=created_at.desc', self.csession())
        fresh = [r for r in rows if r['id'] not in before]
        return fresh[0] if fresh else None

    def change(self, status, label):
        self.guard(label)
        _, rows = call('GET', f'/rest/v1/notifications?report_id=eq.{self.report_id}&select=id', self.csession())
        before = {r['id'] for r in rows}
        t0 = now()
        status_code, _ = call('POST', '/rest/v1/rpc/update_report_status', self.session(), {'p_report_id': self.report_id, 'p_status': status})
        if status_code >= 300: raise SystemExit(f'{label}: update_report_status HTTP {status_code}')
        notice = self.new_notice(before)
        if not notice: raise SystemExit(f'{label}: no notification produced')
        return notice, t0

    def run(self):
        cfg = props()
        if cfg.get('SUPABASE_URL') != 'https://jaysxjjdvaitgrbqjlhe.supabase.co': raise SystemExit('Unexpected project.')
        if adb(self.s, 'get-state').strip() != 'device': raise SystemExit('Phone not ready.')
        self.installation = self.installed_id()
        self.base_rows = self.rows()
        if self.installation not in self.base_rows: raise SystemExit('This phone is not registered for the citizen; open the app signed in first.')
        self.cfp = self.protected_fp()
        base = {k: sql(q)[0]['c'] for k, q in {
            'reports': 'select count(*) c from public.reports', 'notifications': 'select count(*) c from public.notifications',
            'outbox': 'select count(*) c from private.push_outbox', 'history': 'select count(*) c from public.report_history'}.items()}
        ledger = os.path.join(self.a.work, 'push-ledger.json')
        with open(ledger, 'x') as f:
            json.dump({'intent': 'one citizen-owned fixture; status changes by the official; phone left as found', 'report_id': self.report_id,
                       'marker': self.marker, 'baseline': base, 'citizen_device_rows': len(self.base_rows),
                       'citizen_device_fp': self.cfp, 'status': 'intent-recorded'}, f, indent=1)
        print('baseline', base, 'citizen rows:', len(self.base_rows), 'phone registered: True')
        try:
            adb(self.s, 'shell', 'input keyevent KEYCODE_WAKEUP')
            status_code, _ = call('POST', '/rest/v1/rpc/submit_report', self.csession(), {
                'p_id': self.report_id, 'p_category': 'Road Damage', 'p_description': self.marker,
                'p_location': 'PUSHFCM phone probe location', 'p_barangay': 'Alangilan'})
            if status_code >= 300: raise SystemExit(f'submit_report HTTP {status_code}')
            self.record('fixture', report_created_by_citizen_session=True)
            self.launch(); time.sleep(4)
            self.deliver_and_tap('P1 foreground', 'Under Review')
            self.home(); time.sleep(2)
            self.deliver_and_tap('P2 background (Home)', 'In Progress')

            def destroy():
                self.launch(); time.sleep(3); adb(self.s, 'shell', 'input keyevent KEYCODE_BACK'); time.sleep(2)
            self.deliver_and_tap('P3 activity destroyed', 'Under Review', setup=destroy)
            # P4: screen off. Delivery is observed through the notification service; no unlock or tap is attempted.
            self.home(); adb(self.s, 'shell', 'input keyevent KEYCODE_SLEEP'); time.sleep(3)
            notice, t0 = self.change('Resolved', 'P4 screen off')
            seen = self.wait_tray(notice['id'], MAX_WAIT); t_seen = now() - t0
            ob = self.wait_delivered(notice['id'], 60)
            adb(self.s, 'shell', 'input keyevent KEYCODE_WAKEUP')
            self.record('P4 screen off', status='Resolved', notification_in_tray=seen, tray_observed_seconds=round(t_seen, 1) if seen else None,
                        fcm_accepted_by_outbox=bool(ob and ob['d'] in (True, 't', 'true')), outbox_attempts=ob and ob['a'],
                        outbox_error=ob and ob['e'], tap_attempted=False)
            adb(self.s, 'shell', 'cmd statusbar collapse')
        finally:
            self.finish(ledger)

    def finish(self, ledger, instrument=False):
        self.home()
        led = json.load(open(ledger)); led['status'] = 'run-finished'
        rows_after = self.rows()
        led['citizen_rows_after'] = len(rows_after); led['phone_row_still_registered'] = self.installation in rows_after
        try:
            def pending():
                return int(sql(f"select count(*) c from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id='{self.report_id}'::uuid and o.delivered_at is null")[0]['c'])
            end = now() + 120
            while pending() and now() < end: time.sleep(5)
            if pending():
                led['status'] = 'CLEANUP-DEFERRED-outbox-not-delivered'; print('outbox pending; fixture preserved')
            else:
                rid = self.report_id
                sql(f"begin; do $$ begin if (select count(*) from public.reports where id='{rid}'::uuid and owner_id='{self.cuid}'::uuid and description='{self.marker}')<>1 "
                    f"then raise exception 'fixture identity mismatch'; end if; "
                    f"if exists(select 1 from public.notifications where report_id='{rid}'::uuid and user_id<>'{self.cuid}'::uuid) or exists(select 1 from public.report_votes where report_id='{rid}'::uuid) "
                    f"then raise exception 'unexpected fixture references'; end if; "
                    f"delete from public.notifications where report_id='{rid}'::uuid; delete from public.report_history where report_id='{rid}'::uuid; "
                    f"delete from public.reports where id='{rid}'::uuid; end $$; commit;", write=True)
                after = {k: sql(q)[0]['c'] for k, q in {
                    'reports': 'select count(*) c from public.reports', 'notifications': 'select count(*) c from public.notifications',
                    'outbox': 'select count(*) c from private.push_outbox', 'history': 'select count(*) c from public.report_history'}.items()}
                led['after'] = after
                led['status'] = 'cleaned' if after == led['baseline'] else 'CLEANUP-MISMATCH'
        finally:
            self.sessions.teardown()
            led['session_cleanup'] = self.sessions.summary()
            led['sessions'] = {r: {'outcome': o, 'detail': d} for r, (o, d) in self.sessions.outcomes.items()}
            led['fixture_cleanup'] = led['status']
            json.dump(led, open(ledger, 'w'), indent=1)
            json.dump(self.matrix, open(os.path.join(self.a.work, 'push-matrix.json'), 'w'), indent=1)
            print('fixture cleanup:', led['status'], led.get('after'), '| session cleanup:', led['session_cleanup'],
                  '| citizen rows now:', len(rows_after), 'phone row kept:', led['phone_row_still_registered'])
            self.exit_code = 0 if led['status'] == 'cleaned' and led['session_cleanup'] in ('confirmed', 'already-invalid') else 1


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--credentials', required=True); p.add_argument('--work', required=True); p.add_argument('--serial', required=True)
    a = p.parse_args()
    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO: raise SystemExit('Use a private --work directory outside the repository.')
    os.makedirs(work, exist_ok=True); a.work = work
    a.report_id = str(uuid.uuid4()); a.run_id = 'phone'
    probe = Phone(a, json.load(open(a.credentials, encoding='utf-8-sig')))
    try: probe.run()
    finally: pass
    return getattr(probe, 'exit_code', 1)


if __name__ == '__main__':
    sys.exit(main())
