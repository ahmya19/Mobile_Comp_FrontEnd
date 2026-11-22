import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { PGlite } from '@electric-sql/pglite';

const python=process.env.PINPOINT_PYTHON || (process.platform==='win32'?'python':'python3');
const backend=fileURLToPath(new URL('../',import.meta.url));
test('hosted map runner preserves ledgers, cleans partial setup and locally revokes its host session',()=>{
  const result=spawnSync(python,['-m','unittest','discover','-s','tests','-p','map_nav_hosted_test.py','-v'],{cwd:backend,encoding:'utf8'});
  assert.equal(result.status,0,result.stdout+result.stderr);
});

async function scenario(withHistory=false) {
  const db=new PGlite();
  await db.exec(`create schema private; create schema storage;
    create table public.reports(id uuid primary key,owner_id uuid,category text,description text,location text,barangay text,
      latitude float8,longitude float8,photo_path text,original_path text,status text default 'Pending',vote_count int default 0);
    create table public.report_history(report_id uuid);create table public.notifications(report_id uuid);
    create table public.report_votes(report_id uuid);create table private.sms_receipts(report_id uuid);create table storage.objects(name text);
    insert into public.reports(id,description) values('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','Original report');`);
  const fingerprint=(await db.query("select md5(coalesce(string_agg(to_jsonb(r)::text,',' order by id),'')) digest from reports r")).rows[0].digest;
  const data={schema:1,prefix:'MAPNAV-20261122-A01',citizen_uid:'11111111-1111-4111-8111-111111111111',
    baseline_report_ids:['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'],baseline_report_digest:fingerprint,fixtures:{}};
  for(const [i,name,category,lat,lon] of [[1,'road','Road Damage',13.6,121],[2,'garbage','Garbage',13.65,121.1]]) {
    data.fixtures[name]={id:`bbbbbbbb-bbbb-4bbb-8bbb-${String(i).padStart(12,'0')}`,category,description:`${data.prefix} ${name} marker fixture`,latitude:lat,longitude:lon};
  }
  // Only the first fixture exists: exercise interrupted preparation, not just the happy path.
  const fixture=data.fixtures.road;
  await db.query('insert into reports(id,owner_id,category,description,location,barangay,latitude,longitude) values($1,$2,$3,$4,$5,$6,$7,$8)',
    [fixture.id,data.citizen_uid,fixture.category,fixture.description,`${data.prefix} location`,'Alangilan',fixture.latitude,fixture.longitude]);
  if(withHistory) await db.query('insert into report_history values($1)',[fixture.id]);
  const code="import importlib.util,json,sys; s=importlib.util.spec_from_file_location('driver','scripts/map_nav_hosted.py'); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); print(m.cleanup_sql(json.load(sys.stdin)))";
  const result=spawnSync(python,['-c',code],{cwd:backend,input:JSON.stringify(data),encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);
  return {db,sql:result.stdout};
}
test('guarded PostgreSQL cleanup removes only the partially prepared fixture',async()=>{
  const {db,sql}=await scenario();
  try {
    await db.exec(sql);
    assert.deepEqual((await db.query('select description from reports')).rows,[{description:'Original report'}]);
  } finally {await db.close();}
});
test('guarded PostgreSQL cleanup refuses unexpected history without deleting either report',async()=>{
  const {db,sql}=await scenario(true);
  try {
    await assert.rejects(db.exec(sql),/Unexpected fixture identity\/reference/);
    await db.exec('rollback');
    assert.equal((await db.query('select count(*)::int n from reports')).rows[0].n,2);
    assert.equal((await db.query('select count(*)::int n from report_history')).rows[0].n,1);
  } finally {await db.close();}
});
