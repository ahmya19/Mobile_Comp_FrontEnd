begin;
-- Approved product policy (November 23, 2026): only an ACTUAL status change writes a status-history row, a citizen
-- status notification and a push-outbox event. A priority-only change by the assigned official updates the official
-- priority, the effective priority (vote floor preserved: 3 votes Medium, 10 votes High, votes never lower an official
-- assessment) and updated_at, and nothing else. An exact repeat stays idempotent. Same signature, grants and security
-- model as the original function (create or replace keeps its ACL).
create or replace function public.update_report_status(p_report_id uuid,p_status text,p_priority text default null)
returns public.reports language plpgsql security definer set search_path = '' as $$
declare r public.reports; area text; notice uuid; status_changed boolean;
begin
  if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
  select barangay into area from private.officials where user_id=auth.uid();
  if not found then raise exception 'Only an assigned official can update reports' using errcode='42501'; end if;
  select * into r from public.reports where id=p_report_id for update;
  if not found then raise exception 'Report not found' using errcode='P0002'; end if;
  if r.barangay <> area then raise exception 'This report belongs to another barangay' using errcode='42501'; end if;
  if p_status is null or p_status not in ('Pending','Under Review','In Progress','Resolved','Rejected')
    or (p_priority is not null and p_priority not in ('Low','Medium','High'))
    then raise exception 'Invalid status or priority' using errcode='22023'; end if;
  if r.status=p_status and (p_priority is null or r.official_priority=p_priority) then return r; end if;
  status_changed := r.status<>p_status;
  if status_changed and not (
    (r.status='Pending' and p_status in ('Under Review','Rejected')) or
    (r.status='Under Review' and p_status in ('In Progress','Resolved','Rejected')) or
    (r.status='In Progress' and p_status in ('Under Review','Resolved')) or
    (r.status in ('Resolved','Rejected') and p_status='Under Review'))
    then raise exception 'Illegal status transition' using errcode='22023'; end if;
  if status_changed then
    insert into public.report_history(report_id,previous_status,status,changed_by) values(r.id,r.status,p_status,auth.uid());
  end if;
  update public.reports set status=p_status,official_priority=coalesce(p_priority,official_priority),
    priority=case when coalesce(p_priority,official_priority)='High' or vote_count>=10 then 'High'
      when coalesce(p_priority,official_priority)='Medium' or vote_count>=3 then 'Medium' else 'Low' end,
    updated_at=now() where id=r.id returning * into r;
  if status_changed and r.owner_id is not null then
    insert into public.notifications(user_id,report_id,message)
      values(r.owner_id,r.id,'Your ' || r.category || ' report is ' || lower(r.status) || '.') returning id into notice;
    insert into private.push_outbox(notification_id) values(notice);
  end if;
  return r;
end $$;
commit;
