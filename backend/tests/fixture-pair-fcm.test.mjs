import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { alice, bob, official, createReplayDb } from './support/replay.mjs';

const python=process.env.PINPOINT_PYTHON || (process.platform==='win32'?'python':'python3');
const backend=fileURLToPath(new URL('../',import.meta.url));
const rid='bbbbbbbb-bbbb-4bbb-8bbb-000000000001';
const installation='55555555-5555-4555-8555-555555555555';
test('fixture-pair FCM: role routing, isolation and failed-observation guards',()=>{
  const r=spawnSync(python,['-m','unittest','discover','-s','tests','-p','fcm_fixture_pair_test.py','-v'],{cwd:backend,encoding:'utf8'});
  assert.equal(r.status,0,r.stdout+r.stderr);
});

async function scenario({pending=false,badActor=false,badLabel=false}={}) {
  const db=await createReplayDb();
  await db.exec(`alter table auth.users add column raw_app_meta_data jsonb default '{}';`);
  await db.query('update auth.users set raw_app_meta_data=$3::jsonb where id in ($1,$2)',[alice,official,JSON.stringify({pinpoint_fixture:true})]);
  if(badLabel) await db.query("update auth.users set raw_app_meta_data='{}' where id=$1",[alice]);
  const marker='PUSHFCM-unit real FCM probe fixture';
  await db.query("insert into reports(id,owner_id,category,description,location,barangay,status) values($1,$2,'Road Damage',$3,'PUSHFCM probe location','Alangilan','Under Review')",[rid,alice,marker]);
  await db.query("insert into reports(id,owner_id,category,description,location,barangay) values(gen_random_uuid(),$1,'Other','Unrelated report','Synthetic place','Alangilan')",[bob]);
  await db.query("insert into report_history(report_id,previous_status,status,changed_by) values($1,'Pending','Under Review',$2)",[rid,badActor?bob:official]);
  const notice=(await db.query("insert into notifications(user_id,report_id,message) values($1,$2,'Fixture notification') returning id",[alice,rid])).rows[0].id;
  await db.query('insert into private.push_outbox(notification_id,delivered_at) values($1,$2)',[notice,pending?null:new Date()]);
  const code="import real_fcm_probe as m,types; a=types.SimpleNamespace(serial='emulator-5556',report_id='"+rid+"',run_id='unit'); c={'fixture':True,'citizen':{'uid':'"+alice+"'},'official':{'uid':'"+official+"'}}; p=m.Probe(a,c); print(p.cleanup_statement())";
  const r=spawnSync(python,['-c',"import sys; sys.path.insert(0,'scripts'); "+code],{cwd:backend,encoding:'utf8'});
  assert.equal(r.status,0,r.stderr);
  return {db,query:r.stdout};
}
test('FCM cleanup removes only the fixture after delivery and preserves the unrelated report',async()=>{
  const {db,query}=await scenario();
  try {
    await db.exec(query);
    assert.deepEqual((await db.query('select description from reports')).rows,[{description:'Unrelated report'}]);
    assert.equal((await db.query('select count(*)::int n from notifications')).rows[0].n,0);
  } finally {await db.close();}
});
for(const options of [{pending:true},{badActor:true},{badLabel:true}]) test(`FCM cleanup refuses ${options.pending?'pending delivery':options.badLabel?'changed fixture label':'unexpected history actor'} without deleting rows`,async()=>{
  const {db,query}=await scenario(options);
  try {
    await assert.rejects(db.exec(query),/unexpected fixture references|fixture labels changed/);
    await db.exec('rollback');
    assert.equal((await db.query('select count(*)::int n from reports')).rows[0].n,2);
    assert.equal((await db.query('select count(*)::int n from notifications')).rows[0].n,1);
  } finally {await db.close();}
});

test('FCM registration cleanup removes only the known fixture installation and refuses any other device',async()=>{
  const db=await createReplayDb();
  try {
    await db.exec("alter table auth.users add column raw_app_meta_data jsonb default '{}'");
    await db.query('update auth.users set raw_app_meta_data=$3::jsonb where id in ($1,$2)',[alice,official,JSON.stringify({pinpoint_fixture:true})]);
    const code="import sys; sys.path.insert(0,'scripts'); import real_fcm_probe as m,types; p=m.Probe(types.SimpleNamespace(serial='emulator-5556',report_id='"+rid+"',run_id='unit'),{'fixture':True,'citizen':{'uid':'"+alice+"'},'official':{'uid':'"+official+"'}}); print(p.device_cleanup_statement('"+installation+"'))";
    const r=spawnSync(python,['-c',code],{cwd:backend,encoding:'utf8'}); assert.equal(r.status,0,r.stderr);
    await db.query('insert into devices(user_id,installation_id,token) values($1,$2,$3)',[alice,installation,'test-token']);
    await db.query('insert into devices(user_id,installation_id,token) values($1,gen_random_uuid(),$2)',[bob,'unrelated-token']);
    await db.exec(r.stdout);
    assert.deepEqual((await db.query('select user_id from devices')).rows,[{user_id:bob}]);
    await db.query('insert into devices(user_id,installation_id,token) values($1,gen_random_uuid(),$2)',[alice,'unexpected-token']);
    await assert.rejects(db.exec(r.stdout),/unexpected fixture registration/);
    await db.exec('rollback');
    assert.equal((await db.query('select count(*)::int n from devices')).rows[0].n,2);
  } finally {await db.close();}
});
