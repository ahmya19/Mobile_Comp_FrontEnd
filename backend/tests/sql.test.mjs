import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

let db;
const alice='11111111-1111-4111-8111-111111111111';
const bob='22222222-2222-4222-8222-222222222222';
const official='33333333-3333-4333-8333-333333333333';
const outsider='44444444-4444-4444-8444-444444444444';
before(async () => {
  db = new PGlite();
  await db.exec(`
    create role anon; create role authenticated; create role service_role bypassrls;
    create schema auth; create table auth.users(id uuid primary key,raw_user_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    grant usage on schema auth to public; grant execute on function auth.uid() to public;
    create schema storage;
    create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
    create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text,name text);
    alter table storage.objects enable row level security;
    grant usage on schema public,storage to authenticated,anon;
    grant select,insert,delete on storage.objects to authenticated;
    insert into auth.users(id) values('${alice}'),('${bob}'),('${official}'),('${outsider}');
  `);
  await db.exec(await readFile(new URL('../../supabase/migrations/202611210001_pinpoint.sql',import.meta.url),'utf8'));
  await db.exec(await readFile(new URL('../../supabase/migrations/202611210002_delivery.sql',import.meta.url),'utf8'));
  await db.exec(await readFile(new URL('../../supabase/migrations/202611210004_originals.sql',import.meta.url),'utf8'));
  await db.exec(await readFile(new URL('../../supabase/migrations/202611210005_push_retries.sql',import.meta.url),'utf8'));
  await db.exec(await readFile(new URL('../../supabase/migrations/202611210009_push_recipient.sql',import.meta.url),'utf8'));
  await db.exec(await readFile(new URL('../../supabase/migrations/202611230010_priority_only_status_policy.sql',import.meta.url),'utf8'));
  await db.exec(await readFile(new URL('../../supabase/migrations/202611270012_profile_contact_location.sql',import.meta.url),'utf8'));
  await db.query('insert into private.officials(user_id,barangay) values($1,$2),($3,$4)',[official,'Alangilan',outsider,'Another Barangay']);
});
after(async () => { await db?.close(); });

test('originals require a committed owner report and remain private to its owner and assigned official',async () => {
  const r=await submit();
  const name=`${alice}/${r.id}/original`;
  await assert.rejects(asUser(alice,()=>db.query('select public.attach_original($1)',[r.id])),{code:'22023'});
  await assert.rejects(asUser(bob,()=>db.query('insert into storage.objects(bucket_id,name) values($1,$2)',['report-originals',name])),{code:'42501'});
  await asUser(alice,()=>db.query('insert into storage.objects(bucket_id,name) values($1,$2)',['report-originals',name]));
  await asUser(alice,()=>db.query('select public.attach_original($1)',[r.id]));
  await assert.rejects(asUser(bob,()=>db.query('select public.attach_original($1)',[r.id])),{code:'42501'});
  assert.equal((await asUser(bob,()=>db.query('select * from storage.objects where name=$1',[name]))).rows.length,0);
  assert.equal((await asUser(official,()=>db.query('select * from storage.objects where name=$1',[name]))).rows.length,1);
  assert.equal((await asUser(outsider,()=>db.query('select * from storage.objects where name=$1',[name]))).rows.length,0);
  await assert.rejects(asUser(alice,()=>db.query('insert into storage.objects(bucket_id,name) values($1,$2)',['report-originals',`${alice}/${randomUUID()}/original`])),{code:'42501'});
});

async function asUser(uid,run,role='authenticated') {
  await db.exec(`begin; set local role ${role};`);
  await db.query("select set_config('request.jwt.claim.sub',$1,true)",[uid || '']);
  try { const result=await run(); await db.exec('commit'); return result; }
  catch(error) { await db.exec('rollback'); throw error; }
}
async function submit(uid=alice,id=randomUUID(),description='A large pothole near the school.') {
  return asUser(uid,async () => (await db.query('select * from public.submit_report($1,$2,$3,$4,$5)',
    [id,'Road Damage',description,'Main Road','Alangilan'])).rows[0]);
}

test('report retries return one immutable report; conflicting content is rejected',async () => {
  const id=randomUUID();
  const first=await submit(alice,id);
  const retry=await submit(alice,id);
  assert.equal(first.id,retry.id);
  assert.equal((await db.query('select count(*)::int n from public.reports where id=$1',[id])).rows[0].n,1);
  await assert.rejects(submit(alice,id,'A completely different report description.'),{code:'23505'});
  await assert.rejects(submit(bob,id),{code:'42501'});
});

test('anonymous users cannot read reports or invoke submission',async () => {
  await assert.rejects(asUser(null,()=>db.query('select * from public.reports'),'anon'),{code:'42501'});
  await assert.rejects(asUser(null,()=>db.query('select * from public.submit_report($1,$2,$3,$4,$5)',
    [randomUUID(),'Road Damage','Long enough description','Main road','Alangilan']),'anon'),{code:'42501'});
});

test('profiles are private and clients cannot assign official roles or directly mutate reports',async () => {
  await asUser(alice,()=>db.query('select public.save_profile($1)',['Alice Citizen']));
  assert.equal((await asUser(bob,()=>db.query('select * from public.profiles where id=$1',[alice]))).rows.length,0);
  await assert.rejects(asUser(alice,()=>db.query('insert into private.officials(user_id,barangay) values($1,$2)',[alice,'Alangilan'])),{code:'42501'});
  const r=await submit();
  await assert.rejects(asUser(alice,()=>db.query("update public.reports set status='Resolved' where id=$1",[r.id])),{code:'42501'});
  const profile=(await asUser(official,()=>db.query('select public.my_profile() result'))).rows[0].result;
  assert.equal(profile.official,true); assert.equal(profile.barangay,'Alangilan');
});

test('CP-56 profile contact number and location: private, normalized, validated and clearable',async () => {
  await asUser(alice,()=>db.query('select public.save_profile($1)',['Alice Citizen']));
  await asUser(alice,()=>db.query('select public.save_profile_details($1,$2)',['0917 123-4567','  Purok 3,   Alangilan ']));
  const mine=(await asUser(alice,()=>db.query('select public.my_profile() p'))).rows[0].p;
  assert.equal(mine.contact_number,'09171234567'); assert.equal(mine.home_location,'Purok 3, Alangilan');
  // Another citizen can neither read the row nor see the number through their own profile.
  assert.equal((await asUser(bob,()=>db.query('select * from public.profiles where id=$1',[alice]))).rows.length,0);
  assert.equal((await asUser(bob,()=>db.query('select public.my_profile() p'))).rows[0].p.contact_number ?? null,null);
  // Bad values are rejected by the database even if the app check were bypassed (23514 = check_violation).
  await assert.rejects(asUser(alice,()=>db.query('select public.save_profile_details($1,$2)',['not a number','Purok 3'])),{code:'23514'});
  await assert.rejects(asUser(alice,()=>db.query('select public.save_profile_details($1,$2)',['09171234567','x'])),{code:'23514'});
  // Anonymous callers and callers without a saved name are refused.
  await assert.rejects(db.query('select public.save_profile_details($1,$2)',['09171234567','Purok 3']),/permission denied|Sign in first/);
  // Blank clears both fields; the name is untouched.
  await asUser(alice,()=>db.query('select public.save_profile_details($1,$2)',['','']));
  const cleared=(await asUser(alice,()=>db.query('select public.my_profile() p'))).rows[0].p;
  assert.equal(cleared.contact_number,null); assert.equal(cleared.home_location,null); assert.equal(cleared.display_name,'Alice Citizen');
});

test('official authorization, legal transitions and atomic notification history',async () => {
  const r=await submit();
  await assert.rejects(asUser(alice,()=>db.query('select * from public.update_report_status($1,$2)',[r.id,'Under Review'])),{code:'42501'});
  await assert.rejects(asUser(outsider,()=>db.query('select * from public.update_report_status($1,$2)',[r.id,'Under Review'])),{code:'42501'});
  await asUser(official,()=>db.query('select * from public.update_report_status($1,$2)',[r.id,'Under Review']));
  await asUser(official,()=>db.query('select * from public.update_report_status($1,$2)',[r.id,'Under Review']));
  assert.equal((await db.query('select count(*)::int n from public.report_history where report_id=$1',[r.id])).rows[0].n,1);
  const notices=(await asUser(alice,()=>db.query('select * from public.notifications where report_id=$1',[r.id]))).rows;
  assert.equal(notices.length,1);
  assert.equal((await asUser(bob,()=>db.query('select * from public.notifications where report_id=$1',[r.id]))).rows.length,0);
  await asUser(alice,()=>db.query('select public.mark_notification_read($1)',[notices[0].id]));
  assert.equal((await db.query('select read from public.notifications where id=$1',[notices[0].id])).rows[0].read,true);
  await assert.rejects(asUser(official,()=>db.query('select * from public.update_report_status($1,$2)',[r.id,'Pending'])),{code:'22023'});
  assert.equal((await db.query('select count(*)::int n from private.push_outbox where notification_id=$1',[notices[0].id])).rows[0].n,1);
});

test('one vote per account and idempotent remove-vote are enforced by PostgreSQL',async () => {
  const r=await submit();
  for(let i=0;i<3;i++) await asUser(bob,()=>db.query('select * from public.vote_report($1,true)',[r.id]));
  assert.equal((await db.query('select vote_count from public.reports where id=$1',[r.id])).rows[0].vote_count,1);
  await asUser(alice,()=>db.query('select * from public.vote_report($1,true)',[r.id]));
  await asUser(official,()=>db.query('select * from public.vote_report($1,true)',[r.id]));
  assert.equal((await db.query('select priority from public.reports where id=$1',[r.id])).rows[0].priority,'Medium');
  for(let i=0;i<2;i++) await asUser(bob,()=>db.query('select * from public.vote_report($1,false)',[r.id]));
  assert.equal((await db.query('select vote_count from public.reports where id=$1',[r.id])).rows[0].vote_count,2);
});

test('photo ownership and uploaded-object existence are validated before report creation',async () => {
  const id=randomUUID();
  const sql='select * from public.submit_report($1,$2,$3,$4,$5,null,null,$6)';
  const args=[id,'Road Damage','A large pothole near school.','Main Road','Alangilan'];
  await assert.rejects(asUser(alice,()=>db.query(sql,[...args,`${bob}/${id}/photo.jpg`])),{code:'22023'});
  await assert.rejects(asUser(alice,()=>db.query(sql,[...args,`${alice}/${id}/photo.jpg`])),{code:'22023'});
  await asUser(alice,()=>db.query('insert into storage.objects(bucket_id,name) values($1,$2)',['report-images',`${alice}/${id}/photo.jpg`]));
  await asUser(alice,()=>db.query(sql,[...args,`${alice}/${id}/photo.jpg`]));
  const deleted=await asUser(alice,()=>db.query('delete from storage.objects where name=$1 returning id',[`${alice}/${id}/photo.jpg`]));
  assert.equal(deleted.rows.length,0);
});

test('invalid report content and unpaired coordinates fail constraints',async () => {
  await assert.rejects(submit(alice,randomUUID(),'short'),{code:'23514'});
  await assert.rejects(asUser(alice,()=>db.query('select * from public.submit_report($1,$2,$3,$4,$5,$6,$7)',
    [randomUUID(),'Road Damage','A long enough description.','Main road','Alangilan',13.78,null])),{code:'23514'});
});

test('community votes cannot lower an official high-priority assessment',async () => {
  const r=await submit();
  await asUser(official,()=>db.query('select * from public.update_report_status($1,$2,$3)',[r.id,'Under Review','High']));
  await asUser(bob,()=>db.query('select * from public.vote_report($1,true)',[r.id]));
  await asUser(bob,()=>db.query('select * from public.vote_report($1,false)',[r.id]));
  assert.equal((await db.query('select priority from public.reports where id=$1',[r.id])).rows[0].priority,'High');
});

test('urgency crosses 3 and 10 distinct votes in both directions and retains an official Medium floor',async () => {
  const r=await submit();
  const voters=Array.from({length:10},()=>randomUUID());
  for(const voter of voters) await db.query('insert into auth.users(id) values($1)',[voter]);
  async function check(count,priority) {
    const row=(await db.query('select vote_count,priority from public.reports where id=$1',[r.id])).rows[0];
    assert.deepEqual(row,{vote_count:count,priority});
  }
  for(let i=0;i<voters.length;i++) {
    await asUser(voters[i],()=>db.query('select public.vote_report($1,true)',[r.id]));
    await check(i+1,i+1>=10?'High':i+1>=3?'Medium':'Low');
  }
  for(let i=9;i>=0;i--) {
    await asUser(voters[i],()=>db.query('select public.vote_report($1,false)',[r.id]));
    await check(i,i>=10?'High':i>=3?'Medium':'Low');
  }
  await asUser(official,()=>db.query('select public.update_report_status($1,$2,$3)',[r.id,'Under Review','Medium']));
  await asUser(alice,()=>db.query('select public.vote_report($1,true)',[r.id]));
  await asUser(alice,()=>db.query('select public.vote_report($1,false)',[r.id]));
  await check(0,'Medium');
});

test('closed reports reject add and remove votes; invalid or missing targets leave counts intact',async () => {
  const r=await submit();
  await assert.rejects(asUser(alice,()=>db.query('select public.vote_report($1,null)',[r.id])),{code:'22023'});
  await assert.rejects(asUser(alice,()=>db.query('select public.vote_report($1,true)',[randomUUID()])),{code:'P0002'});
  for(const status of ['Resolved','Rejected']) {
    await asUser(official,()=>db.query('select public.update_report_status($1,$2)',[r.id,'Under Review']));
    await asUser(official,()=>db.query('select public.update_report_status($1,$2)',[r.id,status]));
    for(const choice of [true,false]) {
      await assert.rejects(asUser(alice,()=>db.query('select public.vote_report($1,$2)',[r.id,choice])),{code:'22023'});
    }
    assert.equal((await db.query('select vote_count from public.reports where id=$1',[r.id])).rows[0].vote_count,0);
  }
});

test('SMS ingest is server-only, uses approved barangay coordinates and deduplicates gateway IDs',async () => {
  await db.query('insert into private.barangays(name,latitude,longitude) values($1,$2,$3)',['Alangilan',13.78,121.07]);
  const args=['gateway-test','message-1','a'.repeat(64),'Road Damage','A large pothole near school.','Alangilan'];
  const sql='select public.ingest_sms_report($1,$2,$3,$4,$5,$6) id';
  await assert.rejects(asUser(alice,()=>db.query(sql,args)),{code:'42501'});
  const first=(await asUser(null,()=>db.query(sql,args),'service_role')).rows[0].id;
  const duplicate=(await asUser(null,()=>db.query(sql,args),'service_role')).rows[0].id;
  assert.equal(first,duplicate);
  const report=(await db.query('select * from public.reports where id=$1',[first])).rows[0];
  assert.equal(report.source,'sms');assert.equal(report.owner_id,null);assert.equal(report.latitude,13.78);
  await assert.rejects(asUser(null,()=>db.query(sql,[args[0],args[1],'b'.repeat(64),...args.slice(3)]),'service_role'),{code:'23505'});
});

test('push delivery outbox is private and a claimed batch cannot be claimed twice',async () => {
  const r=await submit();
  await asUser(alice,()=>db.query('select public.register_device($1,$2)',[randomUUID(),'fixture-token']));
  await asUser(official,()=>db.query('select * from public.update_report_status($1,$2)',[r.id,'Under Review']));
  await assert.rejects(asUser(alice,()=>db.query('select * from public.claim_push_batch($1)',[r.id])),{code:'42501'});
  assert.equal((await asUser(alice,()=>db.query('select * from public.pending_push_reports()'))).rows.length,0);
  assert.ok((await asUser(official,()=>db.query('select public.pending_push_reports() id'))).rows.some(row=>row.id===r.id));
  assert.equal((await asUser(outsider,()=>db.query('select * from public.pending_push_reports()'))).rows.length,0);
  const first=(await asUser(null,()=>db.query('select * from public.claim_push_batch($1)',[r.id]),'service_role')).rows;
  assert.equal(first.length,1);
  assert.equal(first[0].devices[0].user_id,alice);
  const second=(await asUser(null,()=>db.query('select * from public.claim_push_batch($1)',[r.id]),'service_role')).rows;
  assert.equal(second.length,0);
  await asUser(null,()=>db.query('select public.finish_push($1,$2,null)',[first[0].notification_id,first[0].lease_token]),'service_role');
  assert.ok((await db.query('select delivered_at from private.push_outbox where notification_id=$1',[first[0].notification_id])).rows[0].delivered_at);
});
