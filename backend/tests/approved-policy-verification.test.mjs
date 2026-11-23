import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { alice, official, createReplayDb, readMigration } from './support/replay.mjs';

const template = await readFile(new URL('../contracts/verify-approved-policy.sql', import.meta.url), 'utf8');
const query = template.replaceAll('__CITIZEN__', alice).replaceAll('__OFFICIAL__', official);

test('deployment verification exercises API roles and rolls back all reports, notices and SMS receipts', async () => {
  const db = await createReplayDb();
  try {
    const originalRole = (await db.query('select current_user role')).rows[0].role;
    const results = await db.exec(query);
    const checks = results.flatMap(r => r.rows || []).find(r => r.checks);
    assert.equal(checks.passed, 18);
    for (const table of ['reports', 'notifications', 'report_history', 'private.push_outbox', 'private.sms_receipts']) {
      assert.equal((await db.query(`select count(*)::int n from ${table}`)).rows[0].n, 0, table);
    }
    assert.equal((await db.query('select current_user role')).rows[0].role, originalRole);
  } finally { await db.close(); }
});

test('deployment verifier rejects the old priority-only behavior and rollback preserves unrelated data', async () => {
  const db = await createReplayDb();
  try {
    await db.query("insert into reports(id,owner_id,category,description,location,barangay) values(gen_random_uuid(),$1,'Other','Unrelated baseline report','Synthetic place','Alangilan')", [alice]);
    const before = (await db.query("select md5(string_agg(to_jsonb(r)::text,',' order by id)) d from reports r")).rows[0].d;
    const old = (await readMigration('202611210001_pinpoint.sql')).match(/create function public\.update_report_status[\s\S]*?end \$\$;/)[0];
    await db.exec(old.replace('create function', 'create or replace function'));
    await assert.rejects(db.exec(query), /Priority-only change created status events/);
    await db.exec('rollback');
    assert.equal((await db.query("select md5(string_agg(to_jsonb(r)::text,',' order by id)) d from reports r")).rows[0].d, before);
    assert.equal((await db.query('select count(*)::int n from notifications')).rows[0].n, 0);
  } finally { await db.close(); }
});
