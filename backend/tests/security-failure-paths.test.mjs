import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { alice, bob, official, outsider, createReplayDb, roleRunner } from './support/replay.mjs';

/*
 * Security and failure-path regressions on the FINAL schema (all nine migrations replayed).
 * Local PostgreSQL only: RLS, grants and function logic are real; Auth (JWT validity/expiry), PostgREST and the
 * Storage API are simulated, so hosted behavior is probed separately by backend/scripts/hosted_authz_probe.py.
 */
let db;
let as;
const q = (sql, params = []) => db.query(sql, params);
const one = async (sql, params = []) => (await db.query(sql, params)).rows[0];
const count = async (sql, params = []) => (await one(`select count(*)::int n from (${sql}) x`, params)).n;

before(async () => {
  db = await createReplayDb();
  as = roleRunner(db);
  // Supabase gives authenticated ALL on storage.objects and relies on RLS; model that so "no update policy" is
  // proven by policy and not by a missing grant.
  await db.exec('grant update on storage.objects to authenticated; grant select,insert,delete on storage.objects to anon;');
});
after(async () => { await db?.close(); });

async function reset() {
  await db.exec(`delete from private.push_outbox; delete from public.notifications; delete from public.report_history;
    delete from public.report_votes; delete from public.devices; delete from storage.objects; delete from public.reports;
    delete from public.profiles;`);
}
const submit = (uid, id = randomUUID(), photo = null, extra = {}) => as('authenticated', uid, async () =>
  (await q('select * from public.submit_report($1,$2,$3,$4,$5,null,null,$6)',
    [id, 'Road Damage', 'A large pothole near the school.', 'Main Road', extra.barangay || 'Alangilan', photo])).rows[0]);
const putObject = (uid, bucket, name, role = 'authenticated') => as(role, uid, () => q('insert into storage.objects(bucket_id,name) values($1,$2)', [bucket, name]));
const visible = (uid, name) => as('authenticated', uid, async () => (await q('select 1 from storage.objects where name=$1', [name])).rows.length);

test('no session: mutating RPCs refuse with 28000 and change nothing; passive helpers return nothing', async () => {
  await reset();
  const r = await submit(alice);
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2)', [r.id, 'Under Review']));
  const notice = await one('select id from public.notifications limit 1');
  await as('authenticated', alice, () => q('select public.register_device($1,$2)', [randomUUID(), 'token-a']));
  const before = { reports: await count('select * from public.reports'), devices: await count('select * from public.devices'),
    unread: await count('select * from public.notifications where not read'), votes: await count('select * from public.report_votes') };
  const none = (run) => as('authenticated', '', run);
  const refusals = [
    () => q('select public.save_profile($1)', ['No Session']),
    () => q('select * from public.submit_report($1,$2,$3,$4,$5)', [randomUUID(), 'Road Damage', 'A large pothole near the school.', 'Main Road', 'Alangilan']),
    () => q('select * from public.update_report_status($1,$2)', [r.id, 'Resolved']),
    () => q('select * from public.vote_report($1,true)', [r.id]),
    () => q('select public.register_device($1,$2)', [randomUUID(), 'token']),
    () => q('select public.attach_original($1)', [r.id]),
  ];
  for (const call of refusals) await assert.rejects(none(call), { code: '28000' });
  await none(() => q('select public.remove_device($1)', [randomUUID()]));
  await none(() => q('select public.mark_notification_read($1)', [notice.id]));
  assert.equal((await none(() => q('select public.my_profile() p'))).rows[0].p, null);
  assert.equal((await none(() => q('select * from public.pending_push_reports()'))).rows.length, 0);
  assert.equal((await none(() => q("select public.can_read_original('x/y/original') ok"))).rows[0].ok, false);
  assert.deepEqual({ reports: await count('select * from public.reports'), devices: await count('select * from public.devices'),
    unread: await count('select * from public.notifications where not read'), votes: await count('select * from public.report_votes') }, before);
  // anonymous API role cannot execute any client RPC at all
  for (const call of refusals) await assert.rejects(as('anon', null, call), { code: '42501' });
});

test('direct writes are impossible for API roles on every table, and private tables are unreachable', async () => {
  const publicTables = ['profiles', 'reports', 'report_history', 'notifications', 'devices', 'report_votes'];
  const writes = (table) => [`insert into public.${table} default values`, `update public.${table} set id=id`, `delete from public.${table}`, `truncate public.${table}`];
  for (const role of ['authenticated', 'anon']) {
    for (const table of publicTables) for (const sql of writes(table)) await assert.rejects(as(role, alice, () => q(sql.replace('set id=id', table === 'devices' || table === 'report_votes' ? 'set user_id=user_id' : 'set id=id'))), { code: '42501' }, `${role}: ${sql}`);
    for (const table of ['officials', 'push_outbox', 'sms_receipts', 'barangays', 'push_dispatch_tokens']) {
      await assert.rejects(as(role, alice, () => q(`select * from private.${table}`)), { code: '42501' }, `${role} reads private.${table}`);
      await assert.rejects(as(role, alice, () => q(`delete from private.${table}`)), { code: '42501' });
    }
  }
});

test('devices: input limits, owner-only reads, own-device removal only, and installation takeover is explicit', async () => {
  await reset();
  const phone = randomUUID();
  await assert.rejects(as('authenticated', alice, () => q('select public.register_device($1,$2)', [phone, ''])), { code: '23514' });
  await assert.rejects(as('authenticated', alice, () => q('select public.register_device($1,$2)', [phone, 'x'.repeat(4097)])), { code: '23514' });
  await as('authenticated', alice, () => q('select public.register_device($1,$2)', [phone, 'x'.repeat(4096)]));
  assert.equal(await as('authenticated', bob, async () => (await q('select * from public.devices')).rows.length), 0, 'devices are owner-only');
  await as('authenticated', alice, () => q('select public.register_device($1,$2)', [phone, 'refreshed']));
  assert.equal((await one('select token from public.devices where installation_id=$1', [phone])).token, 'refreshed', 'token refresh updates the same row');
  await as('authenticated', bob, () => q('select public.remove_device($1)', [phone]));
  assert.equal(await count('select * from public.devices where installation_id=$1', [phone]), 1, "another account cannot remove alice's device");
  // The same installation id may move between accounts on one phone (account switching): the row follows the active account.
  await as('authenticated', bob, () => q('select public.register_device($1,$2)', [phone, 'bob-token']));
  const row = await one('select user_id,token from public.devices where installation_id=$1', [phone]);
  assert.deepEqual([row.user_id, row.token], [bob, 'bob-token'], 'registration by the installation id reassigns the row (documented behavior)');
  assert.equal(await count('select * from public.devices'), 1, 'one row per installation');
  await as('authenticated', alice, () => q('select public.remove_device($1)', [phone]));
  assert.equal(await count('select * from public.devices'), 1, 'alice no longer owns it, so her logout cannot remove bob\'s registration');
  await as('authenticated', bob, () => q('select public.remove_device($1)', [phone]));
  assert.equal(await count('select * from public.devices'), 0);
});

test('notifications are recipient-bound: other accounts cannot read or mark them; officials see only their own', async () => {
  await reset();
  const r = await submit(alice);
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2)', [r.id, 'Under Review']));
  const notice = await one('select id from public.notifications where user_id=$1', [alice]);
  assert.equal(await as('authenticated', official, async () => (await q('select * from public.notifications')).rows.length), 0);
  assert.equal(await as('authenticated', bob, async () => (await q('select * from public.notifications')).rows.length), 0);
  await as('authenticated', bob, () => q('select public.mark_notification_read($1)', [notice.id]));
  await as('authenticated', official, () => q('select public.mark_notification_read($1)', [notice.id]));
  assert.equal((await one('select read from public.notifications where id=$1', [notice.id])).read, false);
  await as('authenticated', alice, () => q('select public.mark_notification_read($1)', [notice.id]));
  await as('authenticated', alice, () => q('select public.mark_notification_read($1)', [notice.id]));
  assert.equal((await one('select read from public.notifications where id=$1', [notice.id])).read, true, 'idempotent for the owner');
});

test('pending push work is visible only to officials of the report barangay, and only while undelivered', async () => {
  await reset();
  const r = await submit(alice);
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2)', [r.id, 'Under Review']));
  const pending = (uid) => as('authenticated', uid, async () => (await q('select * from public.pending_push_reports()')).rows.map(x => x.pending_push_reports));
  assert.deepEqual(await pending(official), [r.id]);
  assert.deepEqual(await pending(outsider), [], 'official of another barangay');
  assert.deepEqual(await pending(alice), [], 'citizen');
  await db.exec('update private.push_outbox set delivered_at=now()');
  assert.deepEqual(await pending(official), []);
});

test('retries after partial completion converge: lost acknowledgement, status change in between, repeated original attach', async () => {
  await reset();
  const id = randomUUID();
  const photo = `${alice}/${id}/photo.jpg`;
  await putObject(alice, 'report-images', photo);                         // photo uploaded, RPC not yet sent
  const first = await submit(alice, id, photo);
  const lostAck = await submit(alice, id, photo);                         // client never saw the first response
  assert.equal(first.id, lostAck.id);
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2)', [id, 'Under Review']));
  const late = await submit(alice, id, photo);                            // a very late worker retry
  assert.equal(late.status, 'Under Review', 'retry returns the current row instead of re-inserting');
  assert.equal(await count('select * from public.reports where id=$1', [id]), 1);
  assert.equal(await count('select * from public.notifications where report_id=$1', [id]), 1, 'a retry creates no second notice');
  assert.equal(await count('select * from public.report_history where report_id=$1', [id]), 1);
  const original = `${alice}/${id}/original`;
  await putObject(alice, 'report-originals', original);
  await as('authenticated', alice, () => q('select public.attach_original($1)', [id]));
  await as('authenticated', alice, () => q('select public.attach_original($1)', [id]));
  assert.equal((await one('select original_path from public.reports where id=$1', [id])).original_path, original);
  await assert.rejects(as('authenticated', bob, () => q('select public.attach_original($1)', [id])), { code: '42501' });
  await assert.rejects(submit(bob, id), { code: '42501' }, 'another account cannot retrieve or replay this report ID');
  // content drift on a retry is rejected rather than silently merged
  await assert.rejects(as('authenticated', alice, () => q('select * from public.submit_report($1,$2,$3,$4,$5,null,null,$6)',
    [id, 'Garbage', 'A large pothole near the school.', 'Main Road', 'Alangilan', photo])), { code: '23505' });
});

test('Storage policy matrix: path shape, ownership, no overwrite, delete rules, and who can see what', async () => {
  await reset();
  const id = randomUUID();
  const photo = `${alice}/${id}/photo.jpg`;
  // upload: only the owner's own folder with the exact photo shape, only the two known buckets
  await assert.rejects(putObject(alice, 'report-images', `${bob}/${id}/photo.jpg`), { code: '42501' }, "someone else's folder");
  await assert.rejects(putObject(alice, 'report-images', `${alice}/${id}/photo.png`), { code: '42501' }, 'wrong file name');
  await assert.rejects(putObject(alice, 'report-images', `${alice}/${id}/photo.jpg/extra`), { code: '42501' }, 'extra segment');
  await assert.rejects(putObject(alice, 'report-images', `${alice}/${id.toUpperCase()}/photo.jpg`), { code: '42501' }, 'upper-case id');
  await assert.rejects(putObject(alice, 'other-bucket', photo), { code: '42501' }, 'unknown bucket');
  await assert.rejects(putObject(null, 'report-images', photo, 'anon'), { code: '42501' }, 'anonymous');
  await assert.rejects(putObject(alice, 'report-originals', `${alice}/${id}/original`), { code: '42501' }, 'original before a report exists');
  await putObject(alice, 'report-images', photo);
  // before any report references it, the photo is private to its owner and can be deleted by the owner only
  assert.equal(await visible(bob, photo), 0); assert.equal(await visible(alice, photo), 1);
  assert.equal((await as('authenticated', bob, () => q('delete from storage.objects where name=$1 returning id', [photo]))).rows.length, 0, 'other account cannot delete');
  // no update policy: an existing object cannot be replaced or renamed even by its owner
  assert.equal((await as('authenticated', alice, () => q("update storage.objects set name=name||'x' where name=$1 returning id", [photo]))).rows.length, 0);
  await submit(alice, id, photo);
  assert.equal(await visible(bob, photo), 1, 'committed report photos are community-visible to signed-in users');
  assert.equal(await visible(outsider, photo), 1);
  assert.equal((await as('authenticated', alice, () => q('delete from storage.objects where name=$1 returning id', [photo]))).rows.length, 0, 'a referenced photo cannot be deleted');
  assert.equal(await count('select * from storage.objects where name=$1', [photo]), 1);
  // anonymous cannot see anything
  assert.equal((await as('anon', null, () => q('select * from storage.objects'))).rows.length, 0);
  // originals: owner and the barangay official, only once attached; nobody can delete them
  const original = `${alice}/${id}/original`;
  await putObject(alice, 'report-originals', original);
  assert.equal(await visible(official, original), 0, 'not visible to the official until attached');
  assert.equal(await visible(alice, original), 1);
  await as('authenticated', alice, () => q('select public.attach_original($1)', [id]));
  assert.equal(await visible(official, original), 1); assert.equal(await visible(outsider, original), 0); assert.equal(await visible(bob, original), 0);
  for (const uid of [alice, official]) assert.equal((await as('authenticated', uid, () => q('delete from storage.objects where name=$1 returning id', [original]))).rows.length, 0, 'no delete policy on originals');
  // an unreferenced orphan photo of another account cannot be removed by a stranger, but its owner can clean it up
  const orphan = `${bob}/${randomUUID()}/photo.jpg`;
  await putObject(bob, 'report-images', orphan);
  assert.equal((await as('authenticated', alice, () => q('delete from storage.objects where name=$1 returning id', [orphan]))).rows.length, 0);
  assert.equal((await as('authenticated', bob, () => q('delete from storage.objects where name=$1 returning id', [orphan]))).rows.length, 1);
});

test('photo path on submit is bound to the caller and report ID, and the object must already exist', async () => {
  await reset();
  const id = randomUUID();
  const args = (path) => [id, 'Road Damage', 'A large pothole near the school.', 'Main Road', 'Alangilan', path];
  const call = (uid, path) => as('authenticated', uid, () => q('select * from public.submit_report($1,$2,$3,$4,$5,null,null,$6)', args(path)));
  // Objects exist, so only the binding rule (not the existence rule) can reject these.
  const other = randomUUID();
  await db.query("insert into storage.objects(bucket_id,name) values('report-images',$1),('report-images',$2)", [`${alice}/${other}/photo.jpg`, `${bob}/${id}/photo.jpg`]);
  await assert.rejects(call(alice, `${alice}/${other}/photo.jpg`), { code: '22023' }, 'own uploaded photo bound to a different report id');
  await assert.rejects(call(alice, `${bob}/${id}/photo.jpg`), { code: '22023' }, "another user's uploaded photo cannot be attached");
  await db.query("delete from storage.objects where name in ($1,$2)", [`${alice}/${other}/photo.jpg`, `${bob}/${id}/photo.jpg`]);
  await assert.rejects(call(alice, `${alice}/${id}/photo.jpg`), { code: '22023' }, 'object not uploaded yet');
  assert.equal(await count('select * from public.reports'), 0, 'rejected submissions leave no row');
  await putObject(alice, 'report-images', `${alice}/${id}/photo.jpg`);
  await assert.rejects(call(bob, `${bob}/${id}/photo.jpg`), { code: '22023' }, 'bob has no uploaded object for this id');
  assert.equal((await call(alice, `${alice}/${id}/photo.jpg`)).rows[0].photo_path, `${alice}/${id}/photo.jpg`);
});

test('approved policy (BG-005): a priority-only change by an official updates priority only, with no history, notice or outbox row', async () => {
  await reset();
  const r = await submit(alice);
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2)', [r.id, 'Under Review']));
  const before = { history: await count('select * from public.report_history where report_id=$1', [r.id]), notices: await count('select * from public.notifications where report_id=$1', [r.id]) };
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2,$3)', [r.id, 'Under Review', 'High']));
  assert.equal(before.history, 1); assert.equal(before.notices, 1);
  assert.equal(await count('select * from public.report_history where report_id=$1', [r.id]), 1, 'no second history row');
  assert.equal(await count('select * from public.notifications where report_id=$1', [r.id]), 1, 'the owner is not notified again');
  assert.equal((await one('select priority from public.reports where id=$1', [r.id])).priority, 'High');
  await as('authenticated', official, () => q('select * from public.update_report_status($1,$2,$3)', [r.id, 'Under Review', 'High']));
  assert.equal(await count('select * from public.report_history where report_id=$1', [r.id]), 1);
});
