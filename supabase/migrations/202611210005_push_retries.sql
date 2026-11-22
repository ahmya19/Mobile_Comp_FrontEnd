begin;
create function public.pending_push_reports() returns setof uuid
language sql stable security definer set search_path='' as $$
  select distinct n.report_id from private.push_outbox q
  join public.notifications n on n.id=q.notification_id
  join public.reports r on r.id=n.report_id
  join private.officials o on o.barangay=r.barangay and o.user_id=auth.uid()
  where q.delivered_at is null limit 20;
$$;
revoke all on function public.pending_push_reports() from public,anon;
grant execute on function public.pending_push_reports() to authenticated;
commit;
