begin;
create table private.barangays (
  name text primary key,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180)
);
alter table private.push_outbox add column lease_until timestamptz;
alter table private.push_outbox add column lease_token uuid;

create function public.ingest_sms_report(p_gateway_id text,p_message_id text,p_payload_hash text,p_category text,
  p_description text,p_barangay text) returns uuid language plpgsql security definer set search_path='' as $$
declare receipt private.sms_receipts; area private.barangays; report_id uuid;
begin
  if p_gateway_id is null or p_gateway_id !~ '^[A-Za-z0-9_-]{1,64}$' or
    p_message_id is null or char_length(p_message_id) not between 1 and 128 or
    p_payload_hash is null or p_payload_hash !~ '^[0-9a-f]{64}$'
    then raise exception 'Invalid SMS envelope' using errcode='22023'; end if;
  perform pg_advisory_xact_lock(hashtextextended(p_gateway_id || ':' || p_message_id,0));
  select * into receipt from private.sms_receipts where gateway_id=p_gateway_id and message_id=p_message_id;
  if found then
    if receipt.payload_hash<>p_payload_hash then raise exception 'Message ID already has different content' using errcode='23505'; end if;
    return receipt.report_id;
  end if;
  select * into area from private.barangays where lower(name)=lower(btrim(p_barangay));
  if not found then raise exception 'Configure an approved barangay centroid before accepting SMS' using errcode='22023'; end if;
  insert into public.reports(id,owner_id,category,description,location,barangay,latitude,longitude,source)
    values(gen_random_uuid(),null,p_category,btrim(p_description),'Barangay center (SMS location)',area.name,area.latitude,area.longitude,'sms')
    returning id into report_id;
  insert into private.sms_receipts(gateway_id,message_id,payload_hash,report_id) values(p_gateway_id,p_message_id,p_payload_hash,report_id);
  return report_id;
end $$;

create function public.claim_push_batch(p_report_id uuid default null) returns table(
  notification_id uuid,lease_token uuid,report_id uuid,message text,devices jsonb)
language sql security definer set search_path='' as $$
  with selected as (
    select o.notification_id from private.push_outbox o join public.notifications n on n.id=o.notification_id
    where o.delivered_at is null and (o.lease_until is null or o.lease_until<now())
      and (p_report_id is null or n.report_id=p_report_id)
    order by n.created_at limit 20 for update of o skip locked
  ), claimed as (
    update private.push_outbox o set lease_until=now()+interval '5 minutes',lease_token=gen_random_uuid(),attempts=o.attempts+1
    from selected s where o.notification_id=s.notification_id returning o.notification_id,o.lease_token
  )
  select c.notification_id,c.lease_token,n.report_id,n.message,
    coalesce((select jsonb_agg(jsonb_build_object('token',d.token,'installation_id',d.installation_id))
      from (select token,installation_id from public.devices where user_id=n.user_id order by updated_at desc limit 100) d),'[]'::jsonb)
  from claimed c join public.notifications n on n.id=c.notification_id;
$$;
create function public.finish_push(p_notification_id uuid,p_lease_token uuid,p_error text default null) returns void
language sql security definer set search_path='' as $$
  update private.push_outbox set delivered_at=case when p_error is null then now() else null end,
    lease_until=null,last_error=left(p_error,512)
  where notification_id=p_notification_id and lease_token=p_lease_token;
$$;
create function public.remove_invalid_push_token(p_token text) returns void
language sql security definer set search_path='' as $$ delete from public.devices where token=p_token; $$;
revoke all on function public.ingest_sms_report(text,text,text,text,text,text),public.claim_push_batch(uuid),
  public.finish_push(uuid,uuid,text),public.remove_invalid_push_token(text) from public,anon,authenticated;
grant execute on function public.ingest_sms_report(text,text,text,text,text,text),public.claim_push_batch(uuid),
  public.finish_push(uuid,uuid,text),public.remove_invalid_push_token(text) to service_role;
commit;
