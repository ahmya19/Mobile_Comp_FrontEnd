"""Small shared safety helpers for the hosted-test drivers (stdlib only; no tokens or passwords are ever stored).

Why this exists: the drivers used to (a) infer ownership of rows from "exactly one new report", (b) swallow logout
failures, (c) skip cleanup when a verification step raised and (d) call a run "cleaned" from row counts alone. These
helpers keep fixture cleanup and session cleanup as two separate, honestly labelled outcomes.

Session outcomes (documented distinction):
  confirmed        the Auth server answered 200/204 to POST /auth/v1/logout?scope=local for that token.
  already-invalid  401/403: the server no longer accepts that token (expired or revoked elsewhere), so there is nothing this
                   call could revoke. It is NOT proof that this run's refresh token was revoked.
  unresolved       network error, timeout, 5xx or any other answer: the session may still exist. Never reported as cleaned.
"""
import json, os, re, tempfile, time, traceback, uuid

CONFIRMED, INVALID, UNRESOLVED = 'confirmed', 'already-invalid', 'unresolved'


def classify_logout(status):
    if status in (200, 204): return CONFIRMED
    if status in (401, 403): return INVALID
    return UNRESOLVED


def logout_local(call, token):
    """Local-scope logout (never revokes the user's other sessions). Returns (outcome, detail); never raises."""
    try:
        status, _ = call('POST', '/auth/v1/logout?scope=local', token=token)
    except BaseException as error:  # network down, DNS, timeout, interrupted
        if isinstance(error, (KeyboardInterrupt, SystemExit)): raise
        return UNRESOLVED, type(error).__name__
    return classify_logout(status), f'HTTP {status}'


class SessionRegistry:
    """Owned sessions. teardown() attempts every one independently, so one failure cannot skip another."""

    def __init__(self, call):
        self._call, self._sessions, self.outcomes = call, [], {}

    def add(self, role, token):
        self._sessions.append((role, token)); return token

    def teardown(self):
        for role, token in self._sessions:
            if role not in self.outcomes: self.outcomes[role] = logout_local(self._call, token)
        return dict(self.outcomes)

    def summary(self):
        """confirmed only if every owned session was confirmed; any unresolved wins; otherwise already-invalid."""
        values = [o for o, _ in self.outcomes.values()]
        if not self._sessions: return 'none-created'
        if len(values) < len(self._sessions) or UNRESOLVED in values: return UNRESOLVED
        return CONFIRMED if all(v == CONFIRMED for v in values) else INVALID


class RunLedger:
    """Exclusive, atomic, fsynced JSON ledger outside Git. Identifiers and outcomes only."""

    def __init__(self, path, **fields):
        self.path = path
        self.data = {'schema': 2, 'run_id': uuid.uuid4().hex, 'started': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                     'fixture_cleanup': 'not-started', 'session_cleanup': 'not-started', 'errors': []}
        self.data.update(fields)
        with open(path, 'x', encoding='utf-8') as stream:  # refuses an existing ledger/archive
            json.dump(self.data, stream, indent=1); stream.flush(); os.fsync(stream.fileno())

    def update(self, **fields):
        self.data.update(fields); self._write()

    def error(self, where, exc):
        self.data['errors'].append({'where': where, 'type': type(exc).__name__, 'message': str(exc)[:300]}); self._write()

    def _write(self):
        directory = os.path.dirname(self.path)
        fd, tmp = tempfile.mkstemp(dir=directory, prefix='.ledger-', suffix='.tmp')
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(self.data, stream, indent=1); stream.flush(); os.fsync(stream.fileno())
            os.replace(tmp, self.path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)

    @classmethod
    def load(cls, path):
        led = cls.__new__(cls); led.path = path; led.data = json.load(open(path, encoding='utf-8')); return led


def run_all(steps, ledger=None):
    """Run every cleanup step even if an earlier one raises. Returns [(name, None|exception)]; never raises.
    The caller keeps the ORIGINAL test failure; these results are recorded beside it, not instead of it."""
    results = []
    for name, step in steps:
        try:
            step(); results.append((name, None))
        except (Exception, SystemExit) as error:  # drivers raise SystemExit for SQL/CLI errors; Ctrl-C still propagates
            results.append((name, error))
            if ledger is not None:
                try: ledger.error(name, error)
                except Exception: pass
    return results


def valid_uuid(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', value) is not None


def final_exit(fixture_ok, session_summary, test_ok):
    """Exit 0 only when the test passed, fixtures are verifiably cleaned AND sessions are not unresolved."""
    return 0 if (test_ok and fixture_ok and session_summary in (CONFIRMED, INVALID, 'none-created')) else 1
