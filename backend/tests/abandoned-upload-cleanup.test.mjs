import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { alice, bob, official, createReplayDb, roleRunner } from './support/replay.mjs';

const python = process.env.PINPOINT_PYTHON || (process.platform === 'win32' ? 'python' : 'python3');
const backend = fileURLToPath(new URL('../', import.meta.url));

test('abandoned-upload cleanup driver (mocked transport: dry-run, grace, concurrency, pagination, partial failure)', () => {
  const result = spawnSync(python, ['-m', 'unittest', 'discover', '-s', 'tests', '-p', 'abandoned_upload_cleanup_test.py', '-v'], { cwd: backend, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('maintained Alangilan demo dataset driver (mocked transport: idempotent seed, logout, guarded cleanup)', () => {
  const result = spawnSync(python, ['-m', 'unittest', 'discover', '-s', 'tests', '-p', 'demo_dataset_test.py', '-v'], { cwd: backend, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('isolated fresh Supabase Free restore harness (production-refusal, workdir isolation, quota gate, teardown)', () => {
  const result = spawnSync(python, ['-m', 'unittest', 'discover', '-s', 'tests', '-p', 'fresh_restore_test.py', '-v'], { cwd: backend, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

function pySql(moduleName, expr, payload = {}) {
  const code = `import importlib.util,json,sys
s=importlib.util.spec_from_file_location('m','scripts/${moduleName}.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
a=json.loads(sys.stdin.read());print(${expr})`;
  const res = spawnSync(python, ['-c', code], { cwd: backend, input: JSON.stringify(payload), encoding: 'utf8' });
  assert.equal(res.status, 0, res.stderr);
  return res.stdout.trim();
}

let db;
let as;
before(async () => {
  db = await createReplayDb();
  as = roleRunner(db);
  // Mirror Supabase Storage columns queried by cleanup_abandoned_uploads.py.
  await db.exec(`
    alter table storage.objects add column if not exists owner uuid;
    alter table storage.objects add column if not exists owner_id text;
    alter table storage.objects add column if not exists created_at timestamptz not null default now();
    alter table storage.objects add column if not exists updated_at timestamptz not null default now();
    create table if not exists storage.s3_multipart_uploads(
      id text primary key,
      bucket_id text not null,
      key text not null,
      owner_id text,
      created_at timestamptz not null default now()
    );
  `);
});
after(async () => { await db?.close(); });
beforeEach(async () => {
  await db.exec(`
    delete from private.push_outbox; delete from public.notifications; delete from public.report_history;
    delete from private.sms_receipts; delete from public.report_votes; delete from public.reports;
    delete from storage.s3_multipart_uploads; delete from storage.objects;
  `);
});

test('PGlite SQL: abandoned-upload inventory and concurrency re-check preserve referenced, recent, active-multipart, foreign and concurrently attached objects', async () => {
  const rRef = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000001';
  const rNew = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000002';
  const rActive = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000003';
  const rOldImg = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000004';
  const rOldOrig = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000005';
  const rForeign = 'bbbbbbbb-bbbb-4bbb-8bbb-000000000006';

  const insertObj = (bucket, name, owner, hoursAgo) => db.query(
    `insert into storage.objects(bucket_id,name,owner_id,created_at,updated_at)
     values($1,$2,$3,now()-($4||' hours')::interval,now()-($4||' hours')::interval)`,
    [bucket, name, owner, hoursAgo],
  );
  await insertObj('report-images', `${alice}/${rRef}/photo.jpg`, alice, 72);
  await insertObj('report-images', `${alice}/${rNew}/photo.jpg`, alice, 1);
  await insertObj('report-originals', `${alice}/${rActive}/original`, alice, 72);
  await insertObj('report-images', `${alice}/${rOldImg}/photo.jpg`, alice, 72);
  await insertObj('report-originals', `${alice}/${rOldOrig}/original`, alice, 72);
  await insertObj('report-images', `${bob}/${rForeign}/photo.jpg`, bob, 72);

  await db.query(`insert into public.reports(id,owner_id,category,description,location,barangay,photo_path)
    values($1,$2,'Road Damage','Committed referenced photo report','Main Road','Alangilan',$3)`, [rRef, alice, `${alice}/${rRef}/photo.jpg`]);
  await db.query(`insert into storage.s3_multipart_uploads(id,bucket_id,key,owner_id) values('mp-1','report-originals',$1,$2)`, [`${alice}/${rActive}/original`, alice]);

  const invSql = pySql('cleanup_abandoned_uploads', "m.inventory_page_sql(('report-images','report-originals'), 50)");
  const rows = (await db.query(invSql)).rows;
  assert.equal(rows.length, 6);
  const byName = Object.fromEntries(rows.map((r) => [r.name, r]));
  assert.equal(byName[`${alice}/${rRef}/photo.jpg`].is_referenced, true);
  assert.equal(byName[`${alice}/${rActive}/original`].has_active_multipart, true);
  assert.equal(byName[`${alice}/${rOldImg}/photo.jpg`].is_referenced, false);
  assert.ok(byName[`${alice}/${rOldImg}/photo.jpg`].age_seconds >= 86400);
  assert.ok(byName[`${alice}/${rNew}/photo.jpg`].age_seconds < 86400);

  // Simulate concurrent attachment of rOldImg right before final recheck:
  await db.query(`insert into public.reports(id,owner_id,category,description,location,barangay,photo_path)
    values($1,$2,'Flooding','Concurrently submitted right before cleanup','Canal St','Alangilan',$3)`, [rOldImg, alice, `${alice}/${rOldImg}/photo.jpg`]);
  const recheckSql = pySql('cleanup_abandoned_uploads', "m.final_recheck_sql('report-images', a['name'])", { name: `${alice}/${rOldImg}/photo.jpg` });
  const fresh = (await db.query(recheckSql)).rows[0];
  assert.equal(fresh.is_referenced, true, 'final recheck must detect concurrent attach and preserve the object');
});

test('PGlite SQL: demo dataset cleanup removes ONLY [DEMO] rows owned by demo citizen and refuses any tampered or baseline row', async () => {
  const baselineId = 'cccccccc-cccc-4ccc-8ccc-000000000001';
  await db.query(`insert into public.reports(id,owner_id,category,description,location,barangay)
    values($1,$2,'Road Damage','Real preserved baseline report','Main Rd','Alangilan')`, [baselineId, alice]);

  const catalog = JSON.parse(pySql('manage_demo_dataset', 'json.dumps(m.demo_catalog())'));
  for (const item of catalog) {
    await db.query(`insert into public.reports(id,owner_id,category,description,location,barangay)
      values($1,$2,$3,$4,$5,$6)`, [item.id, alice, item.category, item.description, item.location, item.barangay]);
    await as('authenticated', official, () => db.query('select * from public.update_report_status($1,$2)', [item.id, 'Under Review']));
  }
  assert.equal((await db.query('select count(*)::int n from public.reports')).rows[0].n, 7);

  // Tamper one demo report to belong to bob -> cleanup must refuse and delete NOTHING.
  await db.query('update public.reports set owner_id=$1 where id=$2', [bob, catalog[0].id]);
  const cleanSql = pySql('manage_demo_dataset', "m.demo_cleanup_sql(a['owner'], m.demo_catalog())", { owner: alice });
  await assert.rejects(db.exec(cleanSql), /Demo identity mismatch/);
  await db.exec('rollback').catch(() => {});
  assert.equal((await db.query('select count(*)::int n from public.reports')).rows[0].n, 7, 'refusal must delete nothing');

  // Restore demo owner -> guarded cleanup deletes exactly the 6 demos and preserves the baseline row.
  await db.query('update public.reports set owner_id=$1 where id=$2', [alice, catalog[0].id]);
  await db.exec(cleanSql);
  const remaining = (await db.query('select id::text id from public.reports')).rows.map((r) => r.id);
  assert.deepEqual(remaining, [baselineId]);
  assert.equal((await db.query('select count(*)::int n from public.notifications')).rows[0].n, 0);
  assert.equal((await db.query('select count(*)::int n from public.report_history')).rows[0].n, 0);
});
