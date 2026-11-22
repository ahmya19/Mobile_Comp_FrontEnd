begin;
create or replace function public.claim_push_batch(p_report_id uuid default null) returns table(
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
    coalesce((select jsonb_agg(jsonb_build_object('token',d.token,'installation_id',d.installation_id,'user_id',n.user_id))
      from (select token,installation_id from public.devices where user_id=n.user_id order by updated_at desc limit 100) d),'[]'::jsonb)
  from claimed c join public.notifications n on n.id=c.notification_id;
$$;
commit;
