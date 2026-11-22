import { readFile, readdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';

/*
 * Shared replay harness: builds an empty PostgreSQL (PGlite) with Supabase-shaped stubs and replays ALL nine
 * supabase/migrations files in order. Stubs prove SQL logic and call shapes only, not real pg_cron/pg_net/Vault
 * or hosted Auth/Storage behavior. See backend/MIGRATION_REPLAY_VALIDATION.md.
 */
export const alice = '11111111-1111-4111-8111-111111111111';
export const bob = '22222222-2222-4222-8222-222222222222';
export const official = '33333333-3333-4333-8333-333333333333';
export const outsider = '44444444-4444-4444-8444-444444444444';

// MIGRATIONS_DIR lets mutation checks replay a deliberately broken copy; normal runs use the repository files.
export const migrationsDir = process.env.MIGRATIONS_DIR
  ? pathToFileURL(process.env.MIGRATIONS_DIR.replace(/[\\/]*$/, '/'))
  : new URL('../../../supabase/migrations/', import.meta.url);
// Windows checkouts may use CRLF; the database content must not depend on the checkout's line endings.
export const readMigration = async (name) => (await readFile(new URL(name, migrationsDir), 'utf8')).replace(/\r\n/g, '\n');

export const platformStubs = `
  create role anon; create role authenticated; create role service_role bypassrls;
  create schema extensions; create extension pgcrypto with schema extensions;
  grant usage on schema extensions to public;
  create schema auth; create table auth.users(id uuid primary key,raw_user_meta_data jsonb default '{}');
  create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
  grant usage on schema auth to public; grant execute on function auth.uid() to public;
  create schema storage;
  create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
  create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text,name text);
  alter table storage.objects enable row level security;
  grant usage on schema public,storage to authenticated,anon,service_role;
  grant select,insert,delete on storage.objects to authenticated;
  -- Supabase grants every new public object to the API roles by default; the migrations must revoke explicitly.
  alter default privileges in schema public grant all on tables to anon,authenticated,service_role;
  alter default privileges in schema public grant all on functions to anon,authenticated,service_role;
  alter default privileges in schema public grant all on sequences to anon,authenticated,service_role;
  create schema vault;
  create table vault.secrets(id uuid primary key default gen_random_uuid(),name text unique,secret text not null);
  create view vault.decrypted_secrets as select id,name,secret as decrypted_secret from vault.secrets;
  create function vault.create_secret(new_secret text,new_name text default null,new_description text default '',new_key_id uuid default null)
    returns uuid language plpgsql as $$declare i uuid; begin insert into vault.secrets(name,secret) values(new_name,new_secret) returning id into i; return i; end$$;
  create function vault.update_secret(secret_id uuid,new_secret text default null,new_name text default null,new_description text default null,new_key_id uuid default null)
    returns void language sql as $$update vault.secrets set secret=coalesce(new_secret,secret),name=coalesce(new_name,name) where id=secret_id$$;
  create schema cron;
  create table cron.job(jobid bigserial primary key,jobname text unique,schedule text,command text);
  create table cron.job_run_details(runid bigserial primary key,jobid bigint,end_time timestamptz);
  create function cron.schedule(job_name text,schedule text,command text) returns bigint language sql as
    $$insert into cron.job(jobname,schedule,command) values(job_name,schedule,command)
      on conflict(jobname) do update set schedule=excluded.schedule,command=excluded.command returning jobid$$;
  create schema net;
  create table net.http_request_queue(id bigserial primary key,method text,url text,headers jsonb,body jsonb,timeout_milliseconds integer);
  create table net._http_response(id bigint);
  create function net.http_post(url text,body jsonb default '{}'::jsonb,params jsonb default '{}'::jsonb,
    headers jsonb default '{"Content-Type":"application/json"}'::jsonb,timeout_milliseconds integer default 5000)
    returns bigint language sql as $$insert into net.http_request_queue(method,url,headers,body,timeout_milliseconds)
      values('POST',url,headers,body,timeout_milliseconds) returning id$$;
  insert into auth.users(id) values('${alice}'),('${bob}'),('${official}'),('${outsider}');
`;

export const platformExtensions = ['create extension if not exists pg_cron;', 'create extension if not exists pg_net with schema extensions;'];

export async function migrationFiles() {
  return (await readdir(migrationsDir)).filter(name => name.endsWith('.sql')).sort();
}

export async function createReplayDb() {
  const db = new PGlite({ extensions: { pgcrypto } });
  await db.exec(platformStubs);
  for (const name of await migrationFiles()) {
    let sql = await readMigration(name);
    for (const statement of platformExtensions) {
      if (sql.includes(statement)) sql = sql.replace(statement, '-- platform extension provided by the replay stub');
    }
    await db.exec(sql);
  }
  await db.query('insert into private.officials(user_id,barangay) values($1,$2),($3,$4)', [official, 'Alangilan', outsider, 'Another Barangay']);
  return db;
}

/** Run `run()` inside a transaction as an API role with a simulated JWT subject (empty = no session). */
export function roleRunner(db) {
  return async function as(role, uid, run) {
    await db.exec(`begin; set local role ${role};`);
    await db.query("select set_config('request.jwt.claim.sub',$1,true)", [uid || '']);
    try { const result = await run(); await db.exec('commit'); return result; }
    catch (error) { await db.exec('rollback'); throw error; }
  };
}
