-- Free hosted scheduler. The dispatcher token is generated inside Vault and
-- never copied to a client, migration literal, terminal output, or Edge secret.
begin;
create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;
revoke all on schema net from public,anon,authenticated;
revoke all on net.http_request_queue,net._http_response from public,anon,authenticated;
select vault.create_secret(encode(extensions.gen_random_bytes(32),'hex'),'pinpoint_push_dispatcher')
  where not exists(select 1 from vault.secrets where name='pinpoint_push_dispatcher');

create function public.authorize_push_dispatch(p_token text) returns boolean
language sql stable security definer set search_path='' as $$
  select coalesce(p_token ~ '^[0-9a-f]{64}$' and exists(
    select 1 from vault.decrypted_secrets where name='pinpoint_push_dispatcher'
      and extensions.digest(decrypted_secret,'sha256')=extensions.digest(p_token,'sha256')
  ),false);
$$;
revoke all on function public.authorize_push_dispatch(text) from public,anon,authenticated;
grant execute on function public.authorize_push_dispatch(text) to service_role;

create function private.dispatch_pushes() returns bigint
language plpgsql security definer set search_path='' as $$
declare endpoint text; api_key text; token text; request_id bigint;
begin
  if not exists(select 1 from private.push_outbox where delivered_at is null and
    (lease_until is null or lease_until<now())) then return null; end if;
  select decrypted_secret into endpoint from vault.decrypted_secrets where name='pinpoint_project_url';
  select decrypted_secret into api_key from vault.decrypted_secrets where name='pinpoint_publishable_key';
  select decrypted_secret into token from vault.decrypted_secrets where name='pinpoint_push_dispatcher';
  if endpoint is null or api_key is null or token is null then return null; end if;
  select net.http_post(url:=endpoint || '/functions/v1/deliver-push',
    headers:=jsonb_build_object('Content-Type','application/json','apikey',api_key,'x-pinpoint-dispatch',token),
    body:='{}'::jsonb,timeout_milliseconds:=30000) into request_id;
  return request_id;
end $$;
revoke all on function private.dispatch_pushes() from public,anon,authenticated;

-- Failed provider calls release their lease into a bounded exponential backoff.
create or replace function public.finish_push(p_notification_id uuid,p_lease_token uuid,p_error text default null) returns void
language sql security definer set search_path='' as $$
  update private.push_outbox set delivered_at=case when p_error is null then now() else null end,
    lease_until=case when p_error is null then null else now()+least(interval '30 minutes',
      interval '10 seconds'*power(2,least(attempts,10))) end,last_error=left(p_error,512)
  where notification_id=p_notification_id and lease_token=p_lease_token;
$$;

select cron.schedule('pinpoint-push-outbox','10 seconds','select private.dispatch_pushes();');
-- Retain a week of only this project's routine scheduler logs, keeping the
-- prototype's limited database space available for its reports.
select cron.schedule('pinpoint-cron-log-retention','15 3 * * *',
  $job$delete from cron.job_run_details where jobid in (
    select jobid from cron.job where jobname in ('pinpoint-push-outbox','pinpoint-cron-log-retention')
  ) and end_time<now()-interval '7 days';$job$);
commit;
