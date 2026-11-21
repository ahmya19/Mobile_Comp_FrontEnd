begin;
-- Hosted pg_net tables are owned by supabase_admin; their grants may not be
-- narrowed by a project's postgres role. Never put a reusable secret in them.
create table private.push_dispatch_tokens (
  token_hash bytea primary key,
  expires_at timestamptz not null
);
alter table private.push_dispatch_tokens enable row level security;
revoke all on private.push_dispatch_tokens from public,anon,authenticated;

create or replace function public.authorize_push_dispatch(p_token text) returns boolean
language plpgsql security definer set search_path='' as $$
begin
  if p_token is null or p_token !~ '^[0-9a-f]{64}$' then return false; end if;
  delete from private.push_dispatch_tokens where token_hash=extensions.digest(p_token,'sha256') and expires_at>now();
  return found;
end $$;

create or replace function private.dispatch_pushes() returns bigint
language plpgsql security definer set search_path='' as $$
declare endpoint text; api_key text; token text; request_id bigint;
begin
  delete from private.push_dispatch_tokens where expires_at<=now();
  if not exists(select 1 from private.push_outbox where delivered_at is null and
    (lease_until is null or lease_until<now())) then return null; end if;
  select decrypted_secret into endpoint from vault.decrypted_secrets where name='pinpoint_project_url';
  select decrypted_secret into api_key from vault.decrypted_secrets where name='pinpoint_publishable_key';
  if endpoint is null or api_key is null then return null; end if;
  token=encode(extensions.gen_random_bytes(32),'hex');
  insert into private.push_dispatch_tokens(token_hash,expires_at)
    values(extensions.digest(token,'sha256'),now()+interval '60 seconds');
  select net.http_post(url:=endpoint || '/functions/v1/deliver-push',
    headers:=jsonb_build_object('Content-Type','application/json','apikey',api_key,'x-pinpoint-dispatch',token),
    body:='{}'::jsonb,timeout_milliseconds:=30000) into request_id;
  return request_id;
end $$;
-- The reusable dispatcher credential from the initial scheduler version is no
-- longer used. Remove only this migration's generated secret, not FCM secrets.
delete from vault.secrets where name='pinpoint_push_dispatcher';
commit;
