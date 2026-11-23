#!/usr/bin/env python3
"""Host-coordinated queue/photo recovery across REAL Android process death, on one isolated emulator.

What is real: the app process is killed with SIGKILL (`run-as <pkg> kill -9 <pid>`, the same signal the low-memory killer
sends); the harness verifies the PID is gone, that the replacement process has a different PID, and (for the picker scenario)
that the SAME task id is retained. `ActivityScenario.recreate()` and `am force-stop` (which removes the task) are NOT used.
What is simulated: the backend. A fake Supabase endpoint runs on THIS host (so it outlives the app) and is reached through
`adb reverse`; faults are injected at exact request boundaries. No hosted project, account, push, or phone is involved, and
the app talks to it through a debug-only persisted loopback test backend with an isolated test session.

Durable evidence comes from the app's own files (queue SQLite + queued photo bytes, copied with run-as and inspected on the
host) and from app-private receipts written BEFORE each kill, not from callbacks. Private evidence goes to --work (outside Git).
Android's kill and WorkManager timing on an emulator is not a claim about OEM devices or any scheduling deadline.
"""
import argparse, hashlib, json, os, re, shutil, sqlite3, subprocess, sys, tempfile, threading, time, uuid
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PKG = 'com.example.pinpoint'
PORT = 18765
ADB = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk', 'platform-tools', 'adb.exe')
RUNNER = f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner'


# ------------------------------------------------------------------ fake backend (host side)
class Backend:
    """Just enough of Supabase for the app. Behaviors are consumed per request; events are the oracle's timeline."""

    def __init__(self):
        self.lock = threading.Lock(); self.events = []; self.tokens = {}; self.refresh = {}
        self.objects = {}; self.reports = {}; self.rpc_plan = []; self.upload_plan = []; self.offline = False
        self.release = threading.Event(); self.seq = 0

    @staticmethod
    def uid(email): return str(uuid.uuid5(uuid.NAMESPACE_DNS, email))

    def event(self, kind, **detail):
        with self.lock:
            self.seq += 1; self.events.append({'n': self.seq, 't': round(time.monotonic(), 3), 'kind': kind, **detail})

    def wait_event(self, kind, timeout=60, after=0):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            with self.lock:
                for e in self.events:
                    if e['kind'] == kind and e['n'] > after: return e
            time.sleep(0.2)
        return None

    def count(self, kind): return sum(1 for e in self.events if e['kind'] == kind)
    def mark(self): return self.seq


def make_handler(b):
    class H(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *a): pass

        def send(self, status, body=b'', ctype='application/json'):
            try:
                self.send_response(status); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body)))
                self.send_header('Connection', 'close'); self.end_headers(); self.wfile.write(body)
            except OSError: pass           # the client was killed or gave up: never crash the server

        def js(self, status, obj): self.send(status, json.dumps(obj).encode())

        def hold(self):
            """Block until released, a client disconnect surfaces as a failed write later, or 10 minutes pass."""
            b.release.wait(600)

        def body(self):
            n = int(self.headers.get('Content-Length') or 0); return self.rfile.read(n) if n else b''

        def handle_any(self, method):
            path = self.path.split('?')[0]; data = self.body()
            if b.offline: b.event('refused', path=path); self.close_connection = True; return   # connection closed with no answer
            if path == '/auth/v1/token':
                req = json.loads(data or b'{}')
                if 'grant_type=refresh_token' in self.path:
                    uid = b.refresh.get(req.get('refresh_token'))
                    if not uid: return self.js(400, {'error_code': 'refresh_token_not_found', 'msg': 'Invalid Refresh Token'})
                else:
                    uid = Backend.uid(req['email'])
                access, refresh = f'at-{uid}-{len(b.tokens)}', f'rt-{uid}-{len(b.tokens)}'
                b.tokens[access] = uid; b.refresh[refresh] = uid
                return self.js(200, {'access_token': access, 'refresh_token': refresh, 'expires_in': 3600,
                                     'user': {'id': uid, 'email': req.get('email', 'x'), 'user_metadata': {'display_name': 'Harness'}}})
            if path == '/auth/v1/logout': return self.send(204)
            uid = b.tokens.get((self.headers.get('Authorization') or '')[7:])
            if not uid: return self.js(401, {'code': 'PGRST301', 'message': 'JWT invalid'})
            if method == 'POST' and path.startswith('/storage/v1/object/report-images/'):
                name = path[len('/storage/v1/object/report-images/'):]; plan = b.upload_plan.pop(0) if b.upload_plan else 'ok'
                if name in b.objects:
                    b.event('upload_duplicate', name=name, sha=hashlib.sha256(data).hexdigest(), stored=hashlib.sha256(b.objects[name]).hexdigest())
                    return self.js(400, {'statusCode': '409', 'error': 'Duplicate', 'message': 'The resource already exists'})
                b.objects[name] = data; b.event('upload_stored', name=name, owner=uid, sha=hashlib.sha256(data).hexdigest(), bytes=len(data))
                if plan == 'hold-after-store': self.hold(); return
                return self.js(200, {'Key': 'report-images/' + name})
            if method == 'GET' and path.startswith('/storage/v1/object/authenticated/report-images/'):
                name = path[len('/storage/v1/object/authenticated/report-images/'):]
                b.event('object_read', name=name)
                return self.send(200, b.objects[name], 'image/jpeg') if name in b.objects else self.js(404, {'statusCode': '404', 'message': 'Object not found'})
            if method == 'POST' and path == '/rest/v1/rpc/submit_report':
                req = json.loads(data); rid = req['p_id']; plan = b.rpc_plan.pop(0) if b.rpc_plan else 'ok'
                b.event('rpc_received', id=rid, owner=uid, plan=plan)
                if plan == 'hold': self.hold(); return
                if plan == 'drop': self.close_connection = True; return
                with b.lock:
                    if rid in b.reports and b.reports[rid]['owner'] != uid: return self.js(403, {'code': '42501', 'message': 'Report belongs to another account'})
                    new = rid not in b.reports
                    if new: b.reports[rid] = {'owner': uid, 'photo': req.get('p_photo_path'), 'description': req.get('p_description')}
                b.event('rpc_committed', id=rid, owner=uid, new=new, photo_path=req.get('p_photo_path'))
                if plan == 'commit-then-hold': self.hold(); return
                return self.js(200, {'id': rid, 'owner_id': uid})
            if path == '/rest/v1/rpc/my_profile': return self.js(200, {'id': uid, 'display_name': 'Harness', 'official': False})
            if path in ('/rest/v1/rpc/pending_push_reports',): return self.js(200, [])
            if path.startswith('/rest/v1/rpc/'): return self.send(204)
            if method == 'GET' and path.startswith('/rest/v1/'): return self.js(200, [])
            return self.js(404, {'message': 'unhandled ' + method + ' ' + path})

        def do_GET(self): self.handle_any('GET')
        def do_POST(self): self.handle_any('POST')
        def do_PUT(self): self.handle_any('PUT')
        def do_DELETE(self): self.handle_any('DELETE')
        def do_HEAD(self): self.handle_any('HEAD')
    return H


def jpeg_dims(data):
    """(width, height) from a JPEG's SOF marker, or None."""
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF: i += 1; continue
        if data[i + 1] in (0xC0, 0xC1, 0xC2): return int.from_bytes(data[i + 7:i + 9], 'big'), int.from_bytes(data[i + 5:i + 7], 'big')
        i += 2 + int.from_bytes(data[i + 2:i + 4], 'big')
    return None


# ------------------------------------------------------------------ device control
def missing(data):
    """run-as/cat reports a missing file as text (merged into stdout by exec-out); real files are never this short and textual."""
    return len(data) < 400 and b'No such file or directory' in data or data.startswith(b'run-as:')


class Device:
    def __init__(self, serial, work, allow_physical=False):
        if not re.fullmatch(r'emulator-\d+', serial):
            # A physical device only with the explicit opt-in AND a plain adb serial (no spaces, separators or shell characters).
            if not (allow_physical and re.fullmatch(r'[A-Za-z0-9]{6,32}', serial)):
                raise SystemExit('Use an isolated emulator serial. A physical device needs --allow-physical-device and a plain serial, and only when its owner has released it.')
        self.s, self.work, self.physical = serial, work, not serial.startswith('emulator-')

    def adb(self, *args, binary=False, timeout=120):
        r = subprocess.run([ADB, '-s', self.s, *args], capture_output=True, timeout=timeout)
        return r.stdout if binary else r.stdout.decode('utf-8', 'replace')

    def sh(self, cmd): return self.adb('shell', cmd)
    def pid(self): return (self.sh(f'pidof {PKG}').split() or [None])[0]
    def runas(self, cmd): return self.adb('exec-out', f'run-as {PKG} {cmd}', binary=True)

    def receipt(self):
        raw = self.runas('cat shared_prefs/lifecycle_receipt.xml').decode('utf-8', 'replace')
        try: return {e.get('name'): (e.text or '') for e in ET.fromstring(raw[raw.index('<'):]).iter('string')}
        except Exception: return {}

    def clear_receipt(self): self.sh(f'run-as {PKG} rm -f shared_prefs/lifecycle_receipt.xml')

    def start_phase(self, phase, **extra):
        """Instrumentation blocks until it ends, and a SIGKILL ends it; run it in a thread and keep its output."""
        cmd = ['am', 'instrument', '-w', '-r', '-e', 'class', f'{PKG}.LifecycleDriverTest', '-e', 'phase', phase]
        for k, v in extra.items(): cmd += ['-e', k, str(v)]
        out = []
        t = threading.Thread(target=lambda: out.append(self.adb('shell', ' '.join(f"'{c}'" for c in cmd + [RUNNER]), timeout=400)), daemon=True)
        t.start(); return t, out

    def wait_receipt(self, key, value, timeout=90):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if self.receipt().get(key) == value: return True
            time.sleep(0.7)
        return False

    def kill9(self, pid):
        """Real SIGKILL. Returns (killed, seconds). `am kill` is not used: it refuses processes that are not cached."""
        self.sh(f'run-as {PKG} kill -9 {pid}')
        t0 = time.monotonic()
        while time.monotonic() - t0 < 15:
            if self.pid() != pid: return True, round(time.monotonic() - t0, 2)
            time.sleep(0.3)
        return False, 15.0

    def relaunch(self): self.sh(f'monkey -p {PKG} -c android.intent.category.LAUNCHER 1')

    def task_ids(self):
        out = self.sh('dumpsys activity activities')
        return sorted(set(re.findall(r'ActivityRecord\{[0-9a-f]+ u0 ' + re.escape(PKG) + r'/[^ ]+ t(\d+)', out)))

    def queue_rows(self, project):
        """Copy the SQLite files (db, wal, shm) with run-as and read them on the host: independent of the app's own code."""
        tmp = tempfile.mkdtemp(prefix='pp-queue-')
        try:
            for name in ('pinpoint_queue.db', 'pinpoint_queue.db-wal', 'pinpoint_queue.db-shm'):
                data = self.runas(f'cat databases/{name}')
                if data and not missing(data): open(os.path.join(tmp, name), 'wb').write(data)
            if not os.path.exists(os.path.join(tmp, 'pinpoint_queue.db')): return []
            con = sqlite3.connect(os.path.join(tmp, 'pinpoint_queue.db')); con.row_factory = sqlite3.Row
            rows = [dict(r) for r in con.execute('select * from queue where project=?', (project,))]; con.close(); return rows
        finally: shutil.rmtree(tmp, ignore_errors=True)

    def file_sha(self, path):
        data = self.runas(f'cat {path}')
        return (hashlib.sha256(data).hexdigest(), len(data)) if data and not missing(data) else (None, 0)

    # UI helpers (uiautomator dump through adb; used only for the picker scenario)
    def ui(self):
        self.sh('uiautomator dump /sdcard/pp-lc.xml'); raw = self.sh('cat /sdcard/pp-lc.xml')
        try: return [(n.get('text', ''), n.get('content-desc', ''), n.get('bounds', ''), n.get('checkable', 'false'), n.get('package', '')) for n in ET.fromstring(raw[raw.index('<'):]).iter('node')]
        except Exception: return []

    def has_text(self, needle, timeout=20):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if any(needle.lower() in (t + ' ' + d).lower() for t, d, *_ in self.ui()): return True
            time.sleep(1)
        return False

    def tap_node(self, predicate, timeout=15, scroll=False):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for t, d, b, checkable, pkg in self.ui():
                if predicate(t, d, checkable, pkg):
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b)); self.sh(f'input tap {(x1 + x2) // 2} {(y1 + y2) // 2}'); return True
            if scroll: self.swipe_up()
            time.sleep(1)
        return False

    def swipe_up(self):
        m = re.search(r'(\d+)x(\d+)', self.sh('wm size'))
        w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
        self.sh(f'input swipe {w // 2} {int(h * 0.62)} {w // 2} {int(h * 0.2)} 300')

    def hide_ime(self):
        if 'mInputShown=true' in self.sh('dumpsys input_method | grep mInputShown'): self.sh('input keyevent KEYCODE_BACK'); time.sleep(1)

    def type_into(self, label, text):
        # Hide any open IME first so lower fields (e.g. Barangay) are not covered by the keyboard when tapped.
        self.hide_ime()
        # Exact label first: substring matching hits section headers such as "4. Location" on the redesigned form.
        if not (self.tap_text(label, 6, exact=True) or self.tap_text(label, 10)): return False
        time.sleep(0.8); self.sh('input text ' + text.replace(' ', '%s')); time.sleep(0.8); return True

    def tap_text(self, needle, timeout=20, scroll=True, exact=False):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for t, d, b, *_ in self.ui():
                if ((t or d) == needle) if exact else (needle.lower() in (t + ' ' + d).lower()):
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', b)); self.sh(f'input tap {(x1 + x2) // 2} {(y1 + y2) // 2}'); return True
            if scroll: self.swipe_up()
            time.sleep(1)
        return False


# ------------------------------------------------------------------ scenarios
class Harness:
    def __init__(self, dev, backend, work):
        self.dev, self.b, self.work, self.matrix = dev, backend, work, []
        self.url = f'http://127.0.0.1:{PORT}'

    def rec(self, case, **kw):
        kw['case'] = case; self.matrix.append(kw); print(json.dumps(kw, default=str)); sys.stdout.flush()
        json.dump(self.matrix, open(os.path.join(self.work, 'lifecycle-matrix.json'), 'w'), indent=1, default=str)

    def fresh(self):
        self.b.events.clear(); self.b.objects.clear(); self.b.reports.clear(); self.b.rpc_plan.clear(); self.b.upload_plan.clear()
        self.b.offline = False; self.b.release.clear(); self.dev.clear_receipt()

    def phase_blocking(self, phase, **extra):
        t, out = self.dev.start_phase(phase, **extra); t.join(180); return out[0] if out else ''

    def setup(self, email, media=False):
        out = self.phase_blocking('setup', url=self.url, email=email, **({'media': 'yes'} if media else {}))
        return 'OK (1 test)' in out

    def kill_cycle(self):
        old = self.dev.pid()
        killed, secs = self.dev.kill9(old) if old else (False, 0)
        return old, killed, secs

    def rows(self): return self.dev.queue_rows(self.url)

    def wait_row(self, rid, state, timeout=90):
        end = time.monotonic() + timeout; last = None
        while time.monotonic() < end:
            r = [x for x in self.rows() if x['id'] == rid]; last = r[0]['state'] if r else None
            if last == state: return True, last
            time.sleep(2)
        return False, last

    def cleanup(self):
        self.b.offline = False; self.b.release.set()
        out = self.phase_blocking('cleanup')
        return 'OK (1 test)' in out

    # ---- S1: durable save boundary
    def s1_durable_save(self, email):
        case = 'S1 durable save then SIGKILL before any network'; self.fresh(); rid = str(uuid.uuid4()); uid = Backend.uid(email)
        assert self.setup(email), 'setup failed'
        self.b.offline = True
        t, out = self.dev.start_phase('enqueue', id=rid, tag='s1', width=1100)
        acked = self.dev.wait_receipt('acked', 'yes'); rc = self.dev.receipt()
        old, killed, secs = self.kill_cycle(); t.join(60)
        rows = [r for r in self.rows() if r['id'] == rid]; disk = rows[0] if rows else {}
        sha, size = self.dev.file_sha(disk.get('photo_file') or '/nonexistent') if disk else (None, 0)
        self.rec(case + ' (evidence on disk)', acked_before_kill=acked, killed=killed, old_pid=old, owner_matches=disk.get('owner_id') == uid,
                 row_state=disk.get('state'), photo_bytes_match_expected=sha == rc.get('expected_sha') and size == int(rc.get('expected_bytes', -1)),
                 server_requests_while_offline=self.b.count('rpc_received') + self.b.count('upload_stored'))
        self.b.offline = False; self.dev.relaunch()
        ok, state = self.wait_row(rid, 'Synced'); new = self.dev.pid()
        commits = [e for e in self.b.events if e['kind'] == 'rpc_committed' and e['id'] == rid and e['new']]
        obj = self.b.objects.get(f'{uid}/{rid}/photo.jpg')
        self.rec(case + ' (recovery)', new_pid=new, pid_replaced=bool(new) and new != old, synced=ok, final_state=state, reports_committed=len(commits),
                 object_sha_matches=bool(obj) and hashlib.sha256(obj).hexdigest() == rc.get('expected_sha'),
                 local_photo_removed_after_ack=self.dev.file_sha(disk.get('photo_file') or '/nonexistent')[0] is None)
        self.cleanup()

    # ---- S2: photo uploaded, RPC not acknowledged
    def s2_uploaded_not_acked(self, email):
        case = 'S2 photo uploaded, RPC held, SIGKILL, retry same UUID'; self.fresh(); rid = str(uuid.uuid4()); uid = Backend.uid(email)
        self.b.rpc_plan.append('hold'); assert self.setup(email)
        mark = self.b.mark(); t, out = self.dev.start_phase('enqueue', id=rid, tag='s2', width=1000)
        up = self.b.wait_event('upload_stored', 60, mark); rpc = self.b.wait_event('rpc_received', 60, mark); rc = self.dev.receipt()
        old, killed, secs = self.kill_cycle(); t.join(60)
        committed_before = self.b.count('rpc_committed')
        row = [r for r in self.rows() if r['id'] == rid]
        self.rec(case + ' (at kill)', upload_stored=bool(up), rpc_in_flight=bool(rpc), killed=killed, old_pid=old, committed_before_restart=committed_before,
                 row_state_on_disk=row[0]['state'] if row else None)
        self.b.release.set(); self.b.release.clear(); self.dev.relaunch()
        ok, state = self.wait_row(rid, 'Synced'); new = self.dev.pid()
        commits = [e for e in self.b.events if e['kind'] == 'rpc_committed' and e['id'] == rid and e['new']]
        dup = [e for e in self.b.events if e['kind'] == 'upload_duplicate']
        self.rec(case + ' (recovery)', new_pid=new, pid_replaced=bool(new) and new != old, synced=ok, final_state=state, reports_committed=len(commits),
                 objects_for_report=sum(1 for k in self.b.objects if f'/{rid}/' in k), existing_object_bytes_compared=bool(dup) and self.b.count('object_read') >= 1,
                 object_sha_matches=hashlib.sha256(self.b.objects.get(f'{uid}/{rid}/photo.jpg', b'')).hexdigest() == rc.get('expected_sha'))
        self.cleanup()

    def s2b_different_body(self, email):
        case = 'S2b existing object with DIFFERENT bytes at the report path'; self.fresh(); rid = str(uuid.uuid4()); uid = Backend.uid(email)
        seeded = b'\xff\xd8\xff' + b'SEEDED-DIFFERENT-BODY' * 5
        self.b.objects[f'{uid}/{rid}/photo.jpg'] = seeded; assert self.setup(email)
        mark = self.b.mark(); t, out = self.dev.start_phase('enqueue', id=rid, tag='s2b', width=950)
        dup = self.b.wait_event('upload_duplicate', 60, mark); old, killed, _ = self.kill_cycle(); t.join(60)
        self.dev.relaunch(); time.sleep(20)
        row = [r for r in self.rows() if r['id'] == rid]
        self.rec(case, duplicate_seen=bool(dup), killed=killed, final_state=row[0]['state'] if row else None, error=(row[0].get('error') or '')[:90] if row else None,
                 reports_committed=self.b.count('rpc_committed'), seeded_object_unchanged=self.b.objects[f'{uid}/{rid}/photo.jpg'] == seeded)
        self.cleanup()

    # ---- S3: committed on the server, acknowledgement lost
    def s3_committed_ack_lost(self, email):
        case = 'S3 server committed, acknowledgement lost, SIGKILL'; self.fresh(); rid = str(uuid.uuid4()); uid = Backend.uid(email)
        self.b.rpc_plan.append('commit-then-hold'); assert self.setup(email)
        mark = self.b.mark(); t, out = self.dev.start_phase('enqueue', id=rid, tag='s3', width=1050)
        com = self.b.wait_event('rpc_committed', 60, mark); rc = self.dev.receipt(); old, killed, _ = self.kill_cycle(); t.join(60)
        row = [r for r in self.rows() if r['id'] == rid]
        photo_still = self.dev.file_sha(row[0].get('photo_file') or '/nonexistent')[0] is not None if row else None
        self.rec(case + ' (at kill)', committed=bool(com), killed=killed, local_state=row[0]['state'] if row else None, local_photo_kept_until_ack=photo_still)
        self.b.release.set(); self.b.release.clear(); self.dev.relaunch()
        ok, state = self.wait_row(rid, 'Synced'); new = self.dev.pid()
        commits = [e for e in self.b.events if e['kind'] == 'rpc_committed' and e['id'] == rid and e['new']]
        self.rec(case + ' (recovery)', pid_replaced=bool(new) and new != old, synced=ok, final_state=state, new_commits_for_report=len(commits),
                 total_rpc_attempts=sum(1 for e in self.b.events if e['kind'] == 'rpc_received' and e['id'] == rid), objects_for_report=sum(1 for k in self.b.objects if f'/{rid}/' in k))
        self.cleanup()

    # ---- S4: account/project ownership across restart
    def s4_ownership(self, a_email, b_email):
        case = 'S4 account A queued work stays A across B sign-in and process restart'; self.fresh(); rid = str(uuid.uuid4())
        a, bb = Backend.uid(a_email), Backend.uid(b_email); assert self.setup(a_email); self.b.offline = True
        t, _ = self.dev.start_phase('enqueue', id=rid, tag='s4', width=1020); self.dev.wait_receipt('acked', 'yes'); self.kill_cycle(); t.join(60)
        self.b.offline = False
        t2, _ = self.dev.start_phase('switch', email=b_email); self.dev.wait_receipt('switched_to', bb); time.sleep(12)     # B active, worker may run late
        old, killed, _ = self.kill_cycle(); t2.join(60)
        self.dev.relaunch(); time.sleep(25)                                                                                 # restarted process, B still signed in
        row = [r for r in self.rows() if r['id'] == rid]
        self.rec(case + ' (B signed in, after restart)', killed=killed, row_owner_is_A=bool(row) and row[0]['owner_id'] == a, row_state=row[0]['state'] if row else None,
                 reports_committed_by_B=sum(1 for e in self.b.events if e['kind'] == 'rpc_committed' and e['owner'] == bb), total_commits=self.b.count('rpc_committed'),
                 uploads_by_B=sum(1 for e in self.b.events if e['kind'] == 'upload_stored' and e['owner'] == bb))
        self.dev.sh(f'am start -n {PKG}/.MainActivity >/dev/null'); old2 = self.dev.pid(); self.dev.kill9(old2)
        self.phase_blocking('switch', email=a_email, wait='no'); self.dev.relaunch()
        ok, state = self.wait_row(rid, 'Synced')
        commits = [e for e in self.b.events if e['kind'] == 'rpc_committed' and e['id'] == rid]
        self.rec(case + ' (A signs back in)', synced=ok, final_state=state, commit_owner_is_A=bool(commits) and commits[0]['owner'] == a, commits=len(commits))
        self.cleanup()

    # ---- S6: original / resumable-upload state across SIGKILL (durable state only; the TUS transport itself is Blocked)
    def s6_original_state(self, email):
        case = 'S6 original copy + saved resumable-upload session survive SIGKILL'; self.fresh(); rid = str(uuid.uuid4()); uid = Backend.uid(email)
        assert self.setup(email); self.b.offline = True
        session = f'https://{uuid.uuid4().hex[:8]}.storage.example.invalid/storage/v1/upload/resumable/{uuid.uuid4().hex}'
        t, _ = self.dev.start_phase('enqueue', id=rid, tag='s6', width=1300, original='yes', session=session)
        self.dev.wait_receipt('acked', 'yes'); old, killed, _ = self.kill_cycle(); t.join(60)
        before = [r for r in self.rows() if r['id'] == rid]; b0 = before[0] if before else {}
        osha, osize = self.dev.file_sha(b0.get('original_file') or '/nonexistent')
        self.dev.relaunch(); time.sleep(15); new = self.dev.pid()
        after = [r for r in self.rows() if r['id'] == rid]; a0 = after[0] if after else {}
        self.rec(case, killed=killed, pid_replaced=bool(new) and new != old, original_file_durable=osha is not None and osize > 0,
                 original_state_before=b0.get('original_state'), original_state_after_restart=a0.get('original_state'),
                 upload_session_preserved=bool(b0.get('upload_url')) and b0.get('upload_url') == a0.get('upload_url') == session,
                 report_state_after_restart=a0.get('state'), server_requests_while_offline=self.b.count('rpc_received') + self.b.count('upload_stored'),
                 blocked_not_tested='HEAD-driven PATCH offset, interrupted chunk and lost attach acknowledgement: WifiOriginals pins https port 443 on *.supabase.co/*.storage.supabase.co and binds sockets to a real unmetered Wi-Fi Network, so a local TUS fake cannot be used without weakening that check')
        self.cleanup()

    # ---- S7: killed between "Synced" and deleting the local compressed copy
    def s7_orphan_photo(self, email):
        case = 'S7 SIGKILL in the window after Synced, before the local photo copy is deleted'; self.fresh(); rid = str(uuid.uuid4())
        assert self.setup(email); self.b.offline = True
        t, _ = self.dev.start_phase('enqueue', id=rid, tag='s7', width=980, crashwindow='yes'); self.dev.wait_receipt('acked', 'yes')
        old, killed, _ = self.kill_cycle(); t.join(60)
        row = [r for r in self.rows() if r['id'] == rid]; r0 = row[0] if row else {}
        path = r0.get('photo_file') or '/nonexistent'; present_after_kill = self.dev.file_sha(path)[0] is not None
        self.dev.relaunch(); time.sleep(15)
        removed = self.dev.file_sha(path)[0] is None
        listing = self.dev.sh(f'run-as {PKG} ls files/queued_photos')
        self.rec(case, killed=killed, state_on_disk=r0.get('state'), copy_present_after_kill=present_after_kill, copy_removed_after_restart=removed,
                 orphan_file_names_left=[n for n in listing.split() if rid in n], server_requests=self.b.count('rpc_received') + self.b.count('upload_stored'))
        self.cleanup()

    # ---- S5: picker selection with the TASK retained across process death (real app, driven from the host)
    JPEG = __import__('base64').b64decode('/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=')

    def s5_picker_retained_task(self, email):
        case = 'S5 real picker selection, process SIGKILLed, task retained'; self.fresh(); d = self.dev
        assert self.setup(email, media=True)
        # A synthetic, task-owned image the REAL system picker will show first; the app is launched normally (NOT through
        # instrumentation, whose death would force-stop the package and remove the task).
        name = None
        d.sh('input keyevent KEYCODE_HOME'); d.sh(f'am start -n {PKG}/.MainActivity'); time.sleep(5)
        steps = {}
        steps['quick_report'] = d.tap_text('Quick Report', 25)
        steps['category'] = d.tap_text('Select a Category', 15) and d.tap_text('Road Damage', 10, scroll=False)
        steps['description'] = d.type_into('Description', 'Lifecycle S5 pothole'); steps['location'] = d.type_into('Location', 'Main Road')
        steps['barangay'] = d.type_into('Barangay', 'Alangilan')
        d.hide_ime(); steps['add_photo'] = d.tap_text('Add Photo', 20)
        picked = d.tap_node(lambda t, dd, c, pkg: dd.startswith('Photo taken'), 20)
        time.sleep(1.5)
        d.tap_node(lambda t, dd, c, pkg: pkg != PKG and re.fullmatch(r'(?i)add|done|select|ok|confirm.*', t or '') is not None, 3)
        steps['picked'] = picked; steps['selection_shown'] = d.has_text('Photo selected successfully', 15)
        d.tap_node(lambda t, dd, c, pkg: c == 'true', 5, scroll=True)             # untick "also upload original"
        old = d.pid(); tasks_before = d.task_ids()
        self.rec(case + ' (prepared)', steps=steps, pid=old, task_ids=tasks_before)
        if not steps['selection_shown'] or not old: self.cleanup(); return
        d.sh('input keyevent KEYCODE_HOME'); time.sleep(2)                          # backgrounded: instance state is saved
        killed, secs = d.kill9(old); time.sleep(2); tasks_after_kill = d.task_ids()
        self.rec(case + ' (kill)', old_pid=old, killed=killed, task_ids_before=tasks_before, task_ids_after_kill=tasks_after_kill,
                 task_retained=bool(set(tasks_before) & set(tasks_after_kill)))
        d.relaunch(); time.sleep(5); new = d.pid(); tasks_after = d.task_ids()
        form = d.has_text('Create a Report', 10); selection = d.has_text('Photo selected successfully', 8)
        self.rec(case + ' (restored)', new_pid=new, pid_replaced=bool(new) and new != old, same_task=bool(set(tasks_before) & set(tasks_after)),
                 form_restored=form, selection_state_restored=selection)
        result = {}
        if form or selection:
            mark = self.b.mark(); d.hide_ime(); tapped = d.tap_text('Submit Report', 25)
            time.sleep(2); shown = [t for t, dd, *_ in d.ui() if t][:40]
            up = self.b.wait_event('upload_stored', 40, mark); com = self.b.wait_event('rpc_committed', 40, mark)
            err = [t for t, dd, *_ in d.ui() if t and any(w in t.lower() for w in ('cannot', 'security', 'permission', 'error', 'exception', 'unable'))][:3]
            rows = self.rows()
            sizes = [len(v) for v in self.b.objects.values()]; dims = [jpeg_dims(v) for v in self.b.objects.values()]
            result = dict(submit_tapped=tapped, visible_text_after_tap=shown, uploaded_object_bytes=sizes, uploaded_object_dims=dims,
                          synthetic_image_chosen=bool(dims) and all(d == (1200, 800) for d in dims),
                          submit_uploaded_photo=bool(up), report_committed=bool(com), visible_error=err, queue_rows=len(rows),
                          queue_states=[r['state'] for r in rows], note='observation of platform grant lifetime and UI honesty; no draft persistence is specified')
        self.rec(case + ' (submit after restore)', **result)
        self.cleanup()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--serial', required=True); p.add_argument('--work', required=True)
    p.add_argument('--scenarios', default='s1,s2,s2b,s3,s4,s5,s6,s7')
    p.add_argument('--allow-physical-device', action='store_true', help='only when the device owner has released that phone for this run')
    a = p.parse_args()
    work = os.path.abspath(a.work)
    if os.path.commonpath([work, REPO]) == REPO: raise SystemExit('Use a private --work directory outside the repository.')
    os.makedirs(work, exist_ok=True)
    open(os.path.join(work, 'run.json'), 'x').write(json.dumps({'serial': a.serial, 'scenarios': a.scenarios, 'started': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}))
    dev = Device(a.serial, work, a.allow_physical_device); backend = Backend()
    server = ThreadingHTTPServer(('127.0.0.1', PORT), make_handler(backend)); server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    dev.adb('reverse', f'tcp:{PORT}', f'tcp:{PORT}')
    h = Harness(dev, backend, work); email_a, email_b = f'a-{uuid.uuid4().hex[:6]}@lifecycle.test', f'b-{uuid.uuid4().hex[:6]}@lifecycle.test'
    failures = []
    try:
        for name in a.scenarios.split(','):
            try:
                {'s1': lambda: h.s1_durable_save(email_a), 's2': lambda: h.s2_uploaded_not_acked(email_a), 's2b': lambda: h.s2b_different_body(email_a),
                 's3': lambda: h.s3_committed_ack_lost(email_a), 's4': lambda: h.s4_ownership(email_a, email_b), 's5': lambda: h.s5_picker_retained_task(email_a), 's6': lambda: h.s6_original_state(email_a), 's7': lambda: h.s7_orphan_photo(email_a)}[name]()
            except BaseException as error:
                failures.append((name, type(error).__name__ + ': ' + str(error)[:200])); h.rec(f'{name} HARNESS-ERROR', error=failures[-1][1])
                try: h.cleanup()
                except BaseException: pass
    finally:
        backend.release.set(); dev.adb('reverse', '--remove', f'tcp:{PORT}'); server.shutdown()
        leftover = dev.queue_rows(h.url)
        h.rec('final', queue_rows_left_for_harness_project=len(leftover), harness_errors=failures, emulator_serial=a.serial)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
