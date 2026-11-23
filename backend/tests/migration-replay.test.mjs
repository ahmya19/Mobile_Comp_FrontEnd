import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { alice, bob, official, platformExtensions, readMigration, migrationFiles, createReplayDb, roleRunner } from './support/replay.mjs';

/*
 * Replays ALL eleven supabase/migrations files, in order, from an empty database and checks the FINAL state
 * (several migrations are superseded later: 006 -> 008 dispatcher, 006 finish_push, 009 claim_push_batch).
 * The shared harness (tests/support/replay.mjs) documents what the platform stubs do and do not prove.
 * This is NOT a fresh hosted restore. See backend/MIGRATION_REPLAY_VALIDATION.md.
 */
let db;
let files;
let as;
before(async () => { files = await migrationFiles(); db = await createReplayDb(); as = roleRunner(db); });
after(async () => { await db?.close(); });
const q = (sql, params = []) => db.query(sql, params);
const one = async (sql, params = []) => (await db.query(sql, params)).rows[0];
async function reset() {
  await db.exec(`delete from private.push_outbox; delete from public.notifications; delete from public.report_history;
    delete from private.sms_receipts; delete from public.report_votes;
    delete from private.push_dispatch_tokens; delete from net.http_request_queue; delete from public.devices;
    delete from public.reports; delete from vault.secrets where name in ('pinpoint_project_url','pinpoint_publishable_key');`);
}
async function configureVault() {
  await db.exec(`insert into vault.secrets(name,secret) values('pinpoint_project_url','https://example.test'),('pinpoint_publishable_key','sb_publishable_test');`);
}
async function pendingNotification(owner = alice, status = 'Under Review') {
  const id = randomUUID();
  await as('authenticated', owner, () => q('select * from public.submit_report($1,$2,$3,$4,$5)', [id, 'Road Damage', 'A large pothole near the school.', 'Main Road', 'Alangilan']));
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2)', [id, status]));
  return { reportId: id, ...(await one('select n.id notification_id from public.notifications n where n.report_id=$1', [id])) };
}

test('inventory: exactly eleven migrations, ordered, each atomic, each contributing its objects', async () => {
  assert.deepEqual(files, [
    '202611210001_pinpoint.sql', '202611210002_delivery.sql', '202611210003_indexes.sql', '202611210004_originals.sql',
    '202611210005_push_retries.sql', '202611210006_push_dispatch.sql', '202611210007_dispatch_acl.sql',
    '202611210008_dispatch_nonce.sql', '202611210009_push_recipient.sql', '202611230010_priority_only_status_policy.sql', '202611230011_alangilan_jurisdiction.sql']);
  for (const name of files) {
    const sql = (await readMigration(name)).replace(/^(--[^\n]*\n)+/, '').trim();
    assert.ok(/^begin;/i.test(sql) && /commit;\s*$/i.test(sql), `${name} must be one transaction so a failure leaves no partial state`);
  }
  const six = await readMigration(files[5]);
  for (const statement of platformExtensions) assert.ok(six.includes(statement), `006 must still contain: ${statement}`);
  const exists = async (regclass) => (await one('select to_regclass($1) is not null as ok', [regclass])).ok;
  const fn = async (sig) => (await one('select to_regprocedure($1) is not null as ok', [sig])).ok;
  // 001
  for (const table of ['public.profiles', 'public.reports', 'public.report_history', 'public.notifications', 'public.devices', 'public.report_votes', 'private.officials', 'private.push_outbox', 'private.sms_receipts']) assert.ok(await exists(table), `001 ${table}`);
  for (const sig of ['public.submit_report(uuid,text,text,text,text,double precision,double precision,text)', 'public.update_report_status(uuid,text,text)', 'public.vote_report(uuid,boolean)', 'public.register_device(uuid,text)']) assert.ok(await fn(sig), `001 ${sig}`);
  assert.equal((await one("select count(*)::int n from storage.buckets where id='report-images'")).n, 1);
  // 002
  assert.ok(await exists('private.barangays')); assert.ok(await fn('public.ingest_sms_report(text,text,text,text,text,text)')); assert.ok(await fn('public.claim_push_batch(uuid)'));
  // 003
  for (const index of ['sms_receipts_report', 'notifications_report', 'report_history_report', 'report_history_actor', 'report_votes_user']) assert.ok(await exists(`public.${index}`) || await exists(`private.${index}`), `003 ${index}`);
  // 004
  assert.equal((await one("select count(*)::int n from storage.buckets where id='report-originals'")).n, 1);
  assert.ok(await fn('public.attach_original(uuid)')); assert.ok(await fn('public.can_read_original(text)'));
  // 005
  assert.ok(await fn('public.pending_push_reports()'));
  // 006
  assert.deepEqual((await q("select jobname from cron.job order by jobname")).rows.map(r => r.jobname), ['pinpoint-cron-log-retention', 'pinpoint-push-outbox']);
  // 007 + 008 (final state)
  assert.ok(await exists('private.push_dispatch_tokens'));
  assert.equal((await one("select count(*)::int n from vault.secrets where name='pinpoint_push_dispatcher'")).n, 0, '008 removes the reusable dispatcher secret');
  // 010 (approved BG-005 policy)
  assert.match((await one("select prosrc from pg_proc where oid='public.update_report_status(uuid,text,text)'::regprocedure")).prosrc, /status_changed/);
  // 009
  assert.match((await one("select prosrc from pg_proc where oid='public.claim_push_batch(uuid)'::regprocedure")).prosrc, /'user_id'/);
});

test('final catalog (tables, columns, constraints, RLS, indexes, functions, grants, policies, cron) matches the committed snapshot', async () => {
  const sql = await readFile(new URL('../contracts/catalog-fingerprint.sql', import.meta.url), 'utf8');
  const actual = JSON.parse((await one(sql)).fingerprint);
  const snapshotUrl = new URL('../contracts/final-catalog.json', import.meta.url);
  if (process.env.UPDATE_CATALOG === '1') await writeFile(snapshotUrl, JSON.stringify(actual, null, 1) + '\n');
  const expected = JSON.parse(await readFile(snapshotUrl, 'utf8'));
  assert.deepEqual(actual, expected, 'Catalog drifted from backend/contracts/final-catalog.json. Review the diff; if intended run: UPDATE_CATALOG=1 npm test');
});

test('grant matrix after replay: client RPCs, service-only RPCs, private objects and direct table writes', async () => {
  const exec = async (sig, role) => (await one('select has_function_privilege($1,$2::regprocedure,\'execute\') ok', [role, sig])).ok;
  const clientRpcs = ['public.my_profile()', 'public.save_profile(text)', 'public.submit_report(uuid,text,text,text,text,double precision,double precision,text)',
    'public.update_report_status(uuid,text,text)', 'public.vote_report(uuid,boolean)', 'public.register_device(uuid,text)', 'public.remove_device(uuid)',
    'public.mark_notification_read(uuid)', 'public.attach_original(uuid)', 'public.can_read_original(text)', 'public.pending_push_reports()'];
  const serviceOnly = ['public.ingest_sms_report(text,text,text,text,text,text)', 'public.claim_push_batch(uuid)', 'public.finish_push(uuid,uuid,text)',
    'public.remove_invalid_push_token(text)', 'public.authorize_push_dispatch(text)'];
  for (const sig of clientRpcs) { assert.equal(await exec(sig, 'authenticated'), true, sig); assert.equal(await exec(sig, 'anon'), false, `${sig} anon`); }
  for (const sig of serviceOnly) {
    assert.equal(await exec(sig, 'service_role'), true, `${sig} service`);
    assert.equal(await exec(sig, 'authenticated'), false, `${sig} authenticated`);
    assert.equal(await exec(sig, 'anon'), false, `${sig} anon`);
  }
  for (const role of ['anon', 'authenticated']) {
    assert.equal((await one('select has_schema_privilege($1,$2,\'usage\') ok', [role, 'private'])).ok, false, `${role} private schema`);
    for (const table of ['public.profiles', 'public.reports', 'public.report_history', 'public.notifications', 'public.devices', 'public.report_votes']) {
      for (const privilege of ['insert', 'update', 'delete', 'truncate']) assert.equal((await one('select has_table_privilege($1,$2,$3) ok', [role, table, privilege])).ok, false, `${role} ${privilege} ${table}`);
    }
  }
  assert.equal((await one("select has_table_privilege('anon','public.reports','select') ok")).ok, false);
  assert.equal((await one("select has_table_privilege('authenticated','public.reports','select') ok")).ok, true);
  // private.dispatch_pushes is for the scheduler (postgres), not for any API role.
  for (const role of ['anon', 'authenticated', 'service_role']) assert.equal(await exec('private.dispatch_pushes()', role), false, `dispatch_pushes ${role}`);
});

test('supersession: the live dispatcher is the nonce design, not the first Vault-token design', async () => {
  const auth = (await one("select prosrc from pg_proc where oid='public.authorize_push_dispatch(text)'::regprocedure")).prosrc;
  assert.ok(!/vault/i.test(auth), 'authorize_push_dispatch no longer reads a reusable Vault secret');
  assert.match(auth, /push_dispatch_tokens/);
  const dispatch = (await one("select prosrc from pg_proc where oid='private.dispatch_pushes()'::regprocedure")).prosrc;
  assert.match(dispatch, /push_dispatch_tokens/); assert.ok(!/pinpoint_push_dispatcher/.test(dispatch));
  assert.match((await one("select prosrc from pg_proc where oid='public.finish_push(uuid,uuid,text)'::regprocedure")).prosrc, /power\(2/);
  assert.equal((await one("select count(*)::int n from pg_attribute where attrelid='private.push_dispatch_tokens'::regclass and attnum>0 and not attisdropped")).n, 2, 'only a hash and an expiry are stored');
  assert.equal((await one("select relrowsecurity r from pg_class where oid='private.push_dispatch_tokens'::regclass")).r, true);
});

test('dispatcher: nothing pending or unconfigured sends nothing and creates no token', async () => {
  await reset();
  assert.equal((await one('select private.dispatch_pushes() r')).r, null);
  assert.equal((await one('select count(*)::int n from net.http_request_queue')).n, 0);
  await pendingNotification();
  assert.equal((await one('select private.dispatch_pushes() r')).r, null, 'pending work but no Vault configuration');
  assert.equal((await one('select count(*)::int n from net.http_request_queue')).n, 0);
  assert.equal((await one('select count(*)::int n from private.push_dispatch_tokens')).n, 0);
});

test('dispatcher: configured run posts one call with a one-use random token whose hash is stored for 60 seconds', async () => {
  await reset(); await configureVault(); await pendingNotification();
  const requestId = (await one('select private.dispatch_pushes() r')).r;
  assert.ok(requestId);
  const call = await one('select * from net.http_request_queue');
  assert.equal(call.url, 'https://example.test/functions/v1/deliver-push');
  assert.equal(call.headers.apikey, 'sb_publishable_test');
  assert.deepEqual(call.body, {}); assert.equal(call.timeout_milliseconds, 30000);
  const token = call.headers['x-pinpoint-dispatch'];
  assert.match(token, /^[0-9a-f]{64}$/);
  const stored = await one("select token_hash,extract(epoch from (expires_at-now())) as seconds from private.push_dispatch_tokens");
  assert.equal(Buffer.from(stored.token_hash).toString('hex'), (await one("select encode(extensions.digest($1,'sha256'),'hex') h", [token])).h);
  assert.ok(stored.seconds > 55 && stored.seconds <= 60, `expiry ${stored.seconds}`);
  assert.equal((await one('select count(*)::int n from private.push_dispatch_tokens where token_hash=convert_to($1,\'UTF8\')', [token])).n, 0, 'the plaintext token is not stored');
  // A second run mints a different token.
  await one('select private.dispatch_pushes()');
  const tokens = (await q('select headers from net.http_request_queue order by id')).rows.map(r => r.headers['x-pinpoint-dispatch']);
  assert.equal(new Set(tokens).size, 2);
});

test('dispatcher: token is accepted once, then replay, malformed, unknown, expired and null are rejected; only service_role may ask', async () => {
  await reset(); await configureVault(); await pendingNotification();
  await one('select private.dispatch_pushes()');
  const token = (await one('select headers from net.http_request_queue order by id desc limit 1')).headers['x-pinpoint-dispatch'];
  const authorize = (value) => as('service_role', null, async () => (await q('select public.authorize_push_dispatch($1) ok', [value])).rows[0].ok);
  assert.equal(await authorize(token), true, 'first use');
  assert.equal(await authorize(token), false, 'replay');
  assert.equal(await authorize('f'.repeat(64)), false, 'unknown');
  assert.equal(await authorize('not-a-token'), false, 'malformed');
  assert.equal(await authorize(token.toUpperCase()), false, 'wrong case');
  assert.equal(await authorize(null), false, 'null');
  const expired = 'a'.repeat(64);
  await db.query("insert into private.push_dispatch_tokens values(extensions.digest($1,'sha256'),now()-interval '1 second')", [expired]);
  assert.equal(await authorize(expired), false, 'expired');
  assert.equal((await one('select count(*)::int n from private.push_dispatch_tokens where expires_at<=now()')).n, 1, 'an expired row is left for the scheduler to clean');
  await one('select private.dispatch_pushes()');
  assert.equal((await one('select count(*)::int n from private.push_dispatch_tokens where expires_at<=now()')).n, 0, 'next dispatch deletes expired tokens');
  for (const role of ['authenticated', 'anon']) await assert.rejects(as(role, bob, () => q('select public.authorize_push_dispatch($1)', [token])), { code: '42501' });
  await assert.rejects(as('authenticated', bob, () => q('select private.dispatch_pushes()')), { code: '42501' });
});

test('claim/lease: batches of 20, leases hide claimed work, expired leases are reclaimed with a new token', async () => {
  await reset();
  const owner = alice;
  const report = randomUUID();
  await as('authenticated', owner, () => q('select * from public.submit_report($1,$2,$3,$4,$5)', [report, 'Road Damage', 'A large pothole near the school.', 'Main Road', 'Alangilan']));
  for (let i = 0; i < 25; i++) {
    const n = await one("insert into public.notifications(user_id,report_id,message,created_at) values($1,$2,$3,now()-($4||' seconds')::interval) returning id", [owner, report, `notice ${i}`, 100 - i]);
    await q('insert into private.push_outbox(notification_id) values($1)', [n.id]);
  }
  const claim = (reportId = null) => as('service_role', null, async () => (await q('select * from public.claim_push_batch($1)', [reportId])).rows);
  const first = await claim();
  assert.equal(first.length, 20);
  assert.equal(new Set(first.map(r => r.lease_token)).size, 20);
  assert.equal((await one('select count(*)::int n from private.push_outbox where attempts=1 and lease_until>now()+interval \'4 minutes\'')).n, 20);
  assert.equal((await claim()).length, 5, 'remaining five');
  assert.equal((await claim()).length, 0, 'everything is leased');
  await db.exec("update private.push_outbox set lease_until=now()-interval '1 second'");
  const again = await claim();
  assert.equal(again.length, 20);
  assert.equal((await one('select count(*)::int n from private.push_outbox where attempts=2')).n, 20);
  assert.ok(again.every(r => !first.some(f => f.notification_id === r.notification_id && f.lease_token === r.lease_token)), 'fresh lease tokens');
  // Report filter returns only that report's notifications.
  await reset();
  const a = await pendingNotification(alice); const b = await pendingNotification(bob);
  const filtered = await claim(b.reportId);
  assert.deepEqual(filtered.map(r => r.notification_id), [b.notification_id]);
  assert.notEqual(a.notification_id, b.notification_id);
});

test('finish: wrong lease is ignored, success is final, failures back off 10s*2^attempts capped at 30 minutes, errors truncated', async () => {
  await reset();
  const n = await pendingNotification();
  const claimed = (await as('service_role', null, () => q('select * from public.claim_push_batch()'))).rows[0];
  const finish = (token, error = null) => as('service_role', null, () => q('select public.finish_push($1,$2,$3)', [n.notification_id, token, error]));
  const row = () => one('select delivered_at,lease_until,last_error,attempts,extract(epoch from (lease_until-now())) as wait from private.push_outbox where notification_id=$1', [n.notification_id]);
  await finish(randomUUID());
  assert.equal((await row()).delivered_at, null); assert.ok((await row()).wait > 280, 'wrong token leaves the lease untouched');
  const cases = [[1, 20], [3, 80], [7, 1280], [8, 1800], [10, 1800], [20, 1800]];
  for (const [attempts, expected] of cases) {
    await db.query('update private.push_outbox set attempts=$2,lease_token=$3 where notification_id=$1', [n.notification_id, attempts, claimed.lease_token]);
    await finish(claimed.lease_token, 'x'.repeat(600));
    const r = await row();
    assert.equal(r.delivered_at, null);
    assert.ok(Math.abs(r.wait - expected) < 3, `attempts ${attempts}: wait ${r.wait} expected ${expected}`);
    assert.equal(r.last_error.length, 512);
  }
  await finish(claimed.lease_token);
  const done = await row();
  assert.ok(done.delivered_at); assert.equal(done.lease_until, null); assert.equal(done.last_error, null);
  await db.exec("update private.push_outbox set lease_until=now()-interval '1 hour'");
  assert.equal((await as('service_role', null, () => q('select * from public.claim_push_batch()'))).rows.length, 0, 'delivered work is never reclaimed');
});

test('recipient binding: claimed devices belong to the notification owner, newest first, capped at 100, with user_id attached', async () => {
  await reset();
  const n = await pendingNotification(alice);
  for (let i = 0; i < 105; i++) await q("insert into public.devices(user_id,installation_id,token,updated_at) values($1,$2,$3,now()-($4||' seconds')::interval)", [alice, randomUUID(), `alice-token-${i}`, 1000 - i]);
  await q("insert into public.devices(user_id,installation_id,token) values($1,$2,'bob-token')", [bob, randomUUID()]);
  const row = (await as('service_role', null, () => q('select * from public.claim_push_batch()'))).rows[0];
  assert.equal(row.notification_id, n.notification_id);
  assert.equal(row.devices.length, 100);
  assert.ok(row.devices.every(d => d.user_id === alice && d.token.startsWith('alice-token-')));
  assert.equal(row.devices[0].token, 'alice-token-104', 'most recently updated first');
  assert.ok(!row.devices.some(d => d.token === 'bob-token'));
  await as('service_role', null, () => q('select public.remove_invalid_push_token($1)', ['alice-token-104']));
  assert.equal((await one("select count(*)::int n from public.devices where token='alice-token-104'")).n, 0);
  assert.equal((await one("select count(*)::int n from public.devices where token='bob-token'")).n, 1, 'only the named token is removed');
});

test('scheduler registration and log-retention command touch only PinPoint jobs', async () => {
  const jobs = (await q('select jobname,schedule,command from cron.job order by jobname')).rows;
  assert.deepEqual(jobs.map(j => [j.jobname, j.schedule]), [['pinpoint-cron-log-retention', '15 3 * * *'], ['pinpoint-push-outbox', '10 seconds']]);
  assert.equal(jobs.find(j => j.jobname === 'pinpoint-push-outbox').command, 'select private.dispatch_pushes();');
  const retention = jobs.find(j => j.jobname === 'pinpoint-cron-log-retention');
  await db.exec('delete from cron.job_run_details');
  const other = (await one("insert into cron.job(jobname,schedule,command) values('someone-else','* * * * *','select 1') returning jobid")).jobid;
  const mine = jobs.find(j => j.jobname === 'pinpoint-push-outbox');
  const jobId = (await one("select jobid from cron.job where jobname='pinpoint-push-outbox'")).jobid;
  await db.query("insert into cron.job_run_details(jobid,end_time) values($1,now()-interval '8 days'),($1,now()-interval '1 day'),($2,now()-interval '30 days')", [jobId, other]);
  await db.exec(retention.command);
  const remaining = (await q('select jobid,end_time from cron.job_run_details')).rows;
  assert.equal(remaining.length, 2, 'only the PinPoint run older than seven days is deleted');
  assert.ok(remaining.some(r => String(r.jobid) === String(other)), 'another job\'s history is preserved');
  assert.ok(mine);
  await db.exec("delete from cron.job where jobname='someone-else'; delete from cron.job_run_details");
});

test('every foreign key column is the leading column of an index (no unindexed FK after all migrations)', async () => {
  const missing = (await q(`
    select c.conrelid::regclass::text as tbl, a.attname as col
    from pg_constraint c
    join pg_namespace n on n.oid=c.connamespace and n.nspname in ('public','private')
    join pg_attribute a on a.attrelid=c.conrelid and a.attnum=c.conkey[1]
    where c.contype='f' and not exists (select 1 from pg_index i where i.indrelid=c.conrelid and i.indkey[0]=c.conkey[1])`)).rows;
  assert.deepEqual(missing, []);
});

test('approved policy (BG-005): only an actual status change writes history, a notification and an outbox event', async () => {
  await reset();
  const mk = async () => { const n = await pendingNotification(alice, 'Under Review'); return n.reportId; };
  const events = async (id) => ({
    history: (await one('select count(*)::int n from public.report_history where report_id=$1', [id])).n,
    notices: (await one('select count(*)::int n from public.notifications where report_id=$1', [id])).n,
    outbox: (await one('select count(*)::int n from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id=$1', [id])).n });
  const id = await mk();
  assert.deepEqual(await events(id), { history: 1, notices: 1, outbox: 1 }, 'the real status change still creates exactly one of each');
  const stamp = (await one('select updated_at from public.reports where id=$1', [id])).updated_at;
  const setPriority = (p, status = 'Under Review') => as('authenticated', official, () => q('select * from public.update_report_status($1,$2,$3)', [id, status, p]));
  await setPriority('High');
  assert.deepEqual(await events(id), { history: 1, notices: 1, outbox: 1 }, 'a priority-only change adds no history, notice or outbox row');
  const row = await one('select priority,official_priority,status,updated_at from public.reports where id=$1', [id]);
  assert.equal(row.priority, 'High'); assert.equal(row.official_priority, 'High'); assert.equal(row.status, 'Under Review');
  assert.ok(new Date(row.updated_at) >= new Date(stamp), 'updated_at is refreshed');
  await setPriority('High');   // exact repeat
  assert.deepEqual(await events(id), { history: 1, notices: 1, outbox: 1 }, 'an exact repeat stays idempotent');
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2)', [id, 'Under Review']));
  assert.deepEqual(await events(id), { history: 1, notices: 1, outbox: 1 }, 'a null-priority repeat is idempotent too');
  // a real status change together with a priority still creates exactly one of each
  await setPriority('Medium', 'In Progress');
  assert.deepEqual(await events(id), { history: 2, notices: 2, outbox: 2 });
  assert.equal((await one('select priority from public.reports where id=$1', [id])).priority, 'Medium');
});

test('approved policy: effective priority keeps the vote floor and votes never lower an official assessment (3 = Medium, 10 = High)', async () => {
  await reset();
  const n = await pendingNotification(alice, 'Under Review'); const id = n.reportId;
  const prio = async () => (await one('select priority,official_priority,vote_count from public.reports where id=$1', [id]));
  const official_sets = (p) => as('authenticated', official, () => q('select * from public.update_report_status($1,$2,$3)', [id, 'Under Review', p]));
  await official_sets('High');
  assert.equal((await prio()).priority, 'High');
  await db.query('update public.reports set vote_count=3 where id=$1', [id]);   // 3 votes: the Medium floor
  await official_sets('Low');                                                     // the official lowers their own assessment
  assert.deepEqual([(await prio()).official_priority, (await prio()).priority], ['Low', 'Medium'], 'the effective priority falls only to the 3-vote Medium floor, never below it');
  await db.query('update public.reports set vote_count=10 where id=$1', [id]);  // 10 votes: the High floor
  await official_sets('Medium');
  assert.deepEqual([(await prio()).official_priority, (await prio()).priority], ['Medium', 'High'], 'the 10-vote High floor holds over an official Medium');
  await db.query('update public.reports set vote_count=2 where id=$1', [id]);   // votes removed below 3: only the official assessment remains
  await official_sets('Low');
  assert.equal((await prio()).priority, 'Low', 'below 3 votes the official Low is effective');
  assert.equal((await one('select count(*)::int n from public.report_history where report_id=$1', [id])).n, 1, 'none of these priority-only calls wrote history');
});

test('approved policy: ordinary roles still cannot use the priority path (citizen, anonymous, other barangay, invalid values)', async () => {
  await reset();
  const n = await pendingNotification(alice, 'Under Review'); const id = n.reportId;
  const before = (await one('select official_priority,updated_at from public.reports where id=$1', [id]));
  await assert.rejects(as('authenticated', alice, () => q('select * from public.update_report_status($1,$2,$3)', [id, 'Under Review', 'High'])), { code: '42501' });
  await assert.rejects(as('anon', null, () => q('select * from public.update_report_status($1,$2,$3)', [id, 'Under Review', 'High'])));
  await assert.rejects(as('authenticated', '44444444-4444-4444-8444-444444444444', () => q('select * from public.update_report_status($1,$2,$3)', [id, 'Under Review', 'High'])), { code: '42501' });
  await assert.rejects(as('authenticated', official, () => q('select * from public.update_report_status($1,$2,$3)', [id, 'Under Review', 'Critical'])), { code: '22023' });
  assert.deepEqual(await one('select official_priority,updated_at from public.reports where id=$1', [id]), before, 'rejected calls change nothing');
});

test('approved jurisdiction: only Alangilan is configured, SMS routes to its approximate centroid, anything else fails closed', async () => {
  await reset();
  const rows = (await q('select name,latitude,longitude from private.barangays order by name')).rows;
  assert.deepEqual(rows, [{ name: 'Alangilan', latitude: 13.7819, longitude: 121.0671 }], 'the simulation barangays are gone and nothing else is configured');
  const hash = (n) => n.repeat(64).slice(0, 64);
  const ingest = (msg, barangay, h) => as('service_role', null, () => q('select public.ingest_sms_report($1,$2,$3,$4,$5,$6) id', ['gateway-1', msg, h, 'Garbage', 'Overflowing bin', barangay]));
  const id = (await ingest('m1', ' alangilan ', hash('a'))).rows[0].id;
  assert.deepEqual(await one("select source,owner_id,barangay,location,latitude,longitude from public.reports where id=$1", [id]),
    { source: 'sms', owner_id: null, barangay: 'Alangilan', location: 'Barangay center (SMS location)', latitude: 13.7819, longitude: 121.0671 });
  assert.equal((await ingest('m1', 'Alangilan', hash('a'))).rows[0].id, id, 'a gateway retry returns the same report');
  await assert.rejects(ingest('m2', 'Test Barangay A', hash('b')), /approved barangay centroid/);
  await assert.rejects(ingest('m3', 'Poblacion', hash('c')), /approved barangay centroid/);
  assert.equal((await one("select count(*)::int n from public.reports where source='sms'")).n, 1, 'rejected messages create no report');
});

test('approved jurisdiction: the migration touches no report, history or receipt and public signup cannot create an official', async () => {
  await reset();
  const before = await one("select md5(coalesce(string_agg(to_jsonb(r)::text,',' order by id),'')) d from public.reports r");
  await db.exec(await readMigration('202611230011_alangilan_jurisdiction.sql'));   // idempotent re-application
  assert.deepEqual(await one("select md5(coalesce(string_agg(to_jsonb(r)::text,',' order by id),'')) d from public.reports r"), before);
  assert.equal((await one('select count(*)::int n from private.barangays')).n, 1);
  // roles come only from private.officials, which the API roles cannot write, and metadata cannot elevate
  await assert.rejects(as('authenticated', alice, () => q("insert into private.officials(user_id,barangay) values($1,'Alangilan')", [alice])));
  await assert.rejects(as('authenticated', alice, () => q("update auth.users set raw_user_meta_data='{\"official\":true}' where id=$1", [alice])));
  assert.equal((await as('authenticated', alice, () => q('select public.my_profile() p'))).rows[0].p.official, false);
});
