import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { alice, bob, official, createReplayDb, roleRunner } from './support/replay.mjs';

/*
 * Failure-path coverage for the hosted-test drivers (BG tooling defects, not production RLS findings).
 * (1) The mocked-transport Python suite: ledgers, identity proof, logout outcomes, guards, forbidden uploads.
 * (2) The workflow driver's guarded cleanup transaction, executed as real SQL against the nine replayed migrations:
 *     it must refuse (deleting NOTHING) on any identity or reference mismatch. The "control" test shows the previous
 *     unguarded statement sequence deleting an unrelated report's rows, so these tests fail without the safeguard.
 */
const python = process.env.PINPOINT_PYTHON || (process.platform === 'win32' ? 'python' : 'python3');
const backend = fileURLToPath(new URL('../', import.meta.url));

test('mocked-transport driver suite (workflow, authz forbidden uploads, logout outcomes, FCM guard)', () => {
  const result = spawnSync(python, ['-m', 'unittest', 'discover', '-s', 'tests', '-p', 'hosted_tooling_test.py', '-v'], { cwd: backend, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('fixture-identity provisioner (mocked Auth admin, CLI and SQL): ledger first, no secret output, rollback of only its own users', () => {
  const result = spawnSync(python, ['-m', 'unittest', 'discover', '-s', 'tests', '-p', 'provision_identities_test.py', '-v'], { cwd: backend, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('process-death harness host side: emulator-only serial guard, fake server fault semantics, missing-file detection', () => {
  const result = spawnSync(python, ['-m', 'unittest', 'discover', '-s', 'tests', '-p', 'process_death_harness_test.py', '-v'], { cwd: backend, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

const REPORT = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000001';
const UNRELATED = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000002';
const description = (n) => `CP16 eeeeeeee-eeee-4eee-8eee-${String(n).padStart(12, '0')}: water service issue.`;
let db; let as;
before(async () => { db = await createReplayDb(); as = roleRunner(db); });
after(async () => { await db?.close(); });
beforeEach(async () => {
  await db.exec(`delete from private.push_outbox; delete from public.notifications; delete from public.report_history;
    delete from public.report_votes; delete from public.reports;`);
});

async function fixture(id, owner, desc, photo = `${owner}/${id}/photo.jpg`) {
  await db.query(`insert into public.reports(id,owner_id,category,description,location,barangay,photo_path) values($1,$2,'Water Interruption',$3,'Test location','Alangilan',$4)`, [id, owner, desc, photo]);
  await as('authenticated', official, () => db.query('select * from public.update_report_status($1,$2)', [id, 'Under Review']));
  const notes = (await db.query('select id::text id from public.notifications where report_id=$1', [id])).rows.map((r) => r.id);
  const history = (await db.query('select count(*)::int n from public.report_history where report_id=$1', [id])).rows[0].n;
  return { notes, history };
}
function cleanupSql(scope) {
  const code = `import importlib.util,json,sys
s=importlib.util.spec_from_file_location('w','scripts/workflow_hosted_regression.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
a=json.loads(sys.stdin.read());print(m.cleanup_sql(a['report'],a['owner'],a['description'],a['photo'],a['notes'],a['history']))`;
  const result = spawnSync(python, ['-c', code], { cwd: backend, input: JSON.stringify(scope), encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}
const counts = async () => (await db.query(`select (select count(*)::int from public.reports) r,(select count(*)::int from public.notifications) n,
  (select count(*)::int from public.report_history) h`)).rows[0];
async function refused(sql, pattern) {
  await assert.rejects(db.exec(sql), pattern);
  await db.exec('rollback').catch(() => {});
}

test('the one-line cleanup transaction removes exactly the proven fixture and leaves an unrelated report with its history and notices', async () => {
  const mine = await fixture(REPORT, alice, description(1));
  await fixture(UNRELATED, bob, 'Unrelated report from another account');
  const sql = cleanupSql({ report: REPORT, owner: alice, description: description(1), photo: `${alice}/${REPORT}/photo.jpg`, notes: mine.notes, history: mine.history });
  assert.ok(sql.startsWith('begin;') && sql.endsWith('commit;') && !sql.includes('\n'));
  await db.exec(sql);
  assert.deepEqual(await counts(), { r: 1, n: 1, h: 1 });
  assert.equal((await db.query('select count(*)::int n from public.reports where id=$1', [UNRELATED])).rows[0].n, 1);
});

test('an unrelated sole report (wrong run marker) is refused and nothing is deleted', async () => {
  const other = await fixture(UNRELATED, alice, 'Someone else wrote this');
  const before = await counts();
  await refused(cleanupSql({ report: UNRELATED, owner: alice, description: description(2), photo: `${alice}/${UNRELATED}/photo.jpg`, notes: other.notes, history: other.history }), /Fixture identity mismatch/);
  assert.deepEqual(await counts(), before);
});

test('a report owned by another account is refused even if its marker matches', async () => {
  const mine = await fixture(REPORT, bob, description(3));
  const before = await counts();
  await refused(cleanupSql({ report: REPORT, owner: alice, description: description(3), photo: `${alice}/${REPORT}/photo.jpg`, notes: mine.notes, history: mine.history }), /Fixture identity mismatch/);
  assert.deepEqual(await counts(), before);
});

test('a photo path that is not bound to this owner and report is refused', async () => {
  const mine = await fixture(REPORT, alice, description(4), `${alice}/${UNRELATED}/photo.jpg`);
  const before = await counts();
  await refused(cleanupSql({ report: REPORT, owner: alice, description: description(4), photo: `${alice}/${REPORT}/photo.jpg`, notes: mine.notes, history: mine.history }), /Fixture identity mismatch/);
  assert.deepEqual(await counts(), before);
});

test('references that appeared after the scope was recorded (notice, history, vote) are refused and nothing is deleted', async () => {
  const mine = await fixture(REPORT, alice, description(5));
  const scope = { report: REPORT, owner: alice, description: description(5), photo: `${alice}/${REPORT}/photo.jpg`, notes: mine.notes, history: mine.history };
  const before = await counts();
  // a late status change by someone else adds a notification AND a history row
  await as('authenticated', official, () => db.query('select * from public.update_report_status($1,$2)', [REPORT, 'In Progress']));
  await refused(cleanupSql(scope), /Unexpected notification references/);
  assert.equal((await counts()).r, before.r);
  // scope refreshed for notices only: history still differs
  const notes = (await db.query('select id::text id from public.notifications where report_id=$1', [REPORT])).rows.map((r) => r.id);
  await refused(cleanupSql({ ...scope, notes }), /Unexpected history references/);
  // matching scope but a vote now exists
  const history = (await db.query('select count(*)::int n from public.report_history where report_id=$1', [REPORT])).rows[0].n;
  await db.query('insert into public.report_votes(report_id,user_id) values($1,$2)', [REPORT, bob]);
  await refused(cleanupSql({ ...scope, notes, history }), /Unexpected vote or SMS references|Fixture identity mismatch/);
  assert.equal((await db.query('select count(*)::int n from public.reports where id=$1', [REPORT])).rows[0].n, 1);
  assert.ok((await counts()).n >= 2 && (await counts()).h >= 2, 'no related row was deleted by a refused transaction');
});

test('CONTROL: the previous unguarded sequence deletes an unrelated report\'s notices and history (why the identity gate and guarded transaction exist)', async () => {
  const other = await fixture(UNRELATED, alice, 'Unrelated report that merely appeared as the sole new report');
  const ids = other.notes.map((n) => `'${n}'`).join(',');
  // The old driver derived scope from "exactly one new report" and ran these three separate statements regardless of the checks.
  await db.exec(`delete from public.notifications where id in (${ids}) and report_id='${UNRELATED}';
    delete from public.report_history where report_id='${UNRELATED}';
    delete from public.reports where id='${UNRELATED}' and owner_id='${alice}' and description like 'CP16 %';`);
  const after = await counts();
  assert.equal(after.r, 1, 'the old final delete was guarded and left the report');
  assert.deepEqual([after.n, after.h], [0, 0], 'but its notices and history were already destroyed: the hazard the new transaction prevents');
});
