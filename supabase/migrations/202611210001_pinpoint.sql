-- Free-plan backend. No Firebase Functions, paid extension, or scheduled service.
begin;
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null check (char_length(btrim(display_name)) between 2 and 80),
  updated_at timestamptz not null default now()
);
create table private.officials (
  user_id uuid primary key references auth.users(id) on delete cascade,
  barangay text not null check (char_length(btrim(barangay)) between 2 and 100)
);
create table public.reports (
  id uuid primary key,
  owner_id uuid references auth.users(id) on delete set null,
  category text not null check (category in ('Road Damage','Streetlight','Flooding','Garbage','Water Interruption','Other')),
  description text not null check (char_length(btrim(description)) between 10 and 2000),
  location text not null check (char_length(btrim(location)) between 3 and 200),
  barangay text not null check (char_length(btrim(barangay)) between 2 and 100),
  latitude double precision check (latitude between -90 and 90),
  longitude double precision check (longitude between -180 and 180),
  photo_path text,
  status text not null default 'Pending' check (status in ('Pending','Under Review','In Progress','Resolved','Rejected')),
  priority text not null default 'Low' check (priority in ('Low','Medium','High')),
  official_priority text not null default 'Low' check (official_priority in ('Low','Medium','High')),
  vote_count integer not null default 0 check (vote_count >= 0),
  source text not null default 'app' check (source in ('app','sms')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((latitude is null) = (longitude is null)),
  check (latitude is null or (latitude > '-Infinity'::float8 and latitude < 'Infinity'::float8)),
  check (longitude is null or (longitude > '-Infinity'::float8 and longitude < 'Infinity'::float8))
);
create index reports_recent on public.reports(created_at desc, id desc);
create index reports_owner_recent on public.reports(owner_id, created_at desc, id desc);
create index reports_barangay_recent on public.reports(barangay, created_at desc, id desc);
create table public.report_history (
  id uuid primary key default gen_random_uuid(),
  report_id uuid not null references public.reports(id),
  previous_status text not null,
  status text not null,
  changed_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create table public.notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  report_id uuid not null references public.reports(id),
  message text not null,
  read boolean not null default false,
  created_at timestamptz not null default now()
);
create index notifications_owner_recent on public.notifications(user_id,created_at desc,id desc);
create table public.devices (
  user_id uuid not null references auth.users(id) on delete cascade,
  installation_id uuid not null,
  token text not null check (char_length(token) between 1 and 4096),
  updated_at timestamptz not null default now(),
  primary key (user_id, installation_id),
  unique (installation_id)
);
create table public.report_votes (
  report_id uuid not null references public.reports(id),
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(report_id,user_id)
);
create table private.push_outbox (
  notification_id uuid primary key references public.notifications(id) on delete cascade,
  delivered_at timestamptz,
  attempts integer not null default 0,
  last_error text
);
create table private.sms_receipts (
  gateway_id text not null,
  message_id text not null,
  report_id uuid not null references public.reports(id),
  payload_hash text not null,
  primary key(gateway_id,message_id)
);

alter table public.profiles enable row level security;
alter table public.reports enable row level security;
alter table public.report_history enable row level security;
alter table public.notifications enable row level security;
alter table public.devices enable row level security;
alter table public.report_votes enable row level security;
create policy profiles_owner on public.profiles for select to authenticated using (id = (select auth.uid()));
create policy reports_community on public.reports for select to authenticated using (true);
create policy history_community on public.report_history for select to authenticated using (true);
create policy notifications_owner on public.notifications for select to authenticated using (user_id = (select auth.uid()));
create policy devices_owner on public.devices for select to authenticated using (user_id = (select auth.uid()));
create policy votes_owner on public.report_votes for select to authenticated using (user_id = (select auth.uid()));
revoke all on public.profiles,public.reports,public.report_history,public.notifications,public.devices,public.report_votes from anon,authenticated;
grant select on public.profiles,public.reports,public.report_history,public.notifications,public.devices,public.report_votes to authenticated;

create function public.my_profile() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('id',u.id,'display_name',coalesce(p.display_name,u.raw_user_meta_data->>'display_name','Citizen'),
    'official',o.user_id is not null,'barangay',o.barangay)
  from auth.users u left join public.profiles p on p.id=u.id
  left join private.officials o on o.user_id=u.id where u.id=auth.uid();
$$;
create function public.save_profile(p_display_name text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
  insert into public.profiles(id,display_name) values(auth.uid(),btrim(p_display_name))
  on conflict(id) do update set display_name=excluded.display_name,updated_at=now();
end $$;

-- Stable UUID + immutable payload gives durable workers an idempotent submit.
create function public.submit_report(p_id uuid,p_category text,p_description text,p_location text,p_barangay text,
  p_latitude double precision default null,p_longitude double precision default null,p_photo_path text default null)
returns public.reports language plpgsql security definer set search_path = '' as $$
declare existing public.reports; result public.reports;
begin
  if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
  if p_photo_path is not null and p_photo_path <> auth.uid()::text || '/' || p_id::text || '/photo.jpg'
    then raise exception 'Invalid photo owner or report path' using errcode='22023'; end if;
  -- Serialize retries for the same UUID, including two requests racing to insert.
  perform pg_advisory_xact_lock(hashtextextended(p_id::text,0));
  select * into existing from public.reports where id=p_id;
  if found then
    if existing.owner_id is distinct from auth.uid() then raise exception 'Report belongs to another account' using errcode='42501'; end if;
    if (existing.category,existing.description,existing.location,existing.barangay,existing.latitude,existing.longitude,existing.photo_path)
      is distinct from (p_category,btrim(p_description),btrim(p_location),btrim(p_barangay),p_latitude,p_longitude,p_photo_path)
      then raise exception 'This report ID already has different content' using errcode='23505'; end if;
    return existing;
  end if;
  if p_photo_path is not null and not exists(select 1 from storage.objects where bucket_id='report-images' and name=p_photo_path)
    then raise exception 'Upload the photo before submitting the report' using errcode='22023'; end if;
  insert into public.reports(id,owner_id,category,description,location,barangay,latitude,longitude,photo_path)
    values(p_id,auth.uid(),p_category,btrim(p_description),btrim(p_location),btrim(p_barangay),p_latitude,p_longitude,p_photo_path)
    returning * into result;
  return result;
end $$;

create function public.update_report_status(p_report_id uuid,p_status text,p_priority text default null)
returns public.reports language plpgsql security definer set search_path = '' as $$
declare r public.reports; area text; notice uuid;
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
  if r.status<>p_status and not (
    (r.status='Pending' and p_status in ('Under Review','Rejected')) or
    (r.status='Under Review' and p_status in ('In Progress','Resolved','Rejected')) or
    (r.status='In Progress' and p_status in ('Under Review','Resolved')) or
    (r.status in ('Resolved','Rejected') and p_status='Under Review'))
    then raise exception 'Illegal status transition' using errcode='22023'; end if;
  insert into public.report_history(report_id,previous_status,status,changed_by) values(r.id,r.status,p_status,auth.uid());
  update public.reports set status=p_status,official_priority=coalesce(p_priority,official_priority),
    priority=case when coalesce(p_priority,official_priority)='High' or vote_count>=10 then 'High'
      when coalesce(p_priority,official_priority)='Medium' or vote_count>=3 then 'Medium' else 'Low' end,
    updated_at=now() where id=r.id returning * into r;
  if r.owner_id is not null then
    insert into public.notifications(user_id,report_id,message)
      values(r.owner_id,r.id,'Your ' || r.category || ' report is ' || lower(r.status) || '.') returning id into notice;
    insert into private.push_outbox(notification_id) values(notice);
  end if;
  return r;
end $$;

-- Prototype thresholds: 3 votes -> Medium; 10 votes -> High. Configurable in one place.
create function public.vote_report(p_report_id uuid,p_voted boolean) returns public.reports
language plpgsql security definer set search_path = '' as $$
declare r public.reports; total integer;
begin
  if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
  if p_voted is null then raise exception 'Choose vote or remove vote' using errcode='22023'; end if;
  select * into r from public.reports where id=p_report_id for update;
  if not found then raise exception 'Report not found' using errcode='P0002'; end if;
  if r.status in ('Resolved','Rejected') then raise exception 'Voting is closed for this report' using errcode='22023'; end if;
  if p_voted then insert into public.report_votes(report_id,user_id) values(r.id,auth.uid()) on conflict do nothing;
  else delete from public.report_votes where report_id=r.id and user_id=auth.uid(); end if;
  select count(*) into total from public.report_votes where report_id=r.id;
  update public.reports set vote_count=total,priority=case when total>=10 or official_priority='High' then 'High'
    when total>=3 or official_priority='Medium' then 'Medium' else 'Low' end,
    updated_at=now() where id=r.id returning * into r;
  return r;
end $$;

create function public.register_device(p_installation_id uuid,p_token text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
  insert into public.devices(user_id,installation_id,token) values(auth.uid(),p_installation_id,p_token)
  on conflict(installation_id) do update set user_id=excluded.user_id,token=excluded.token,updated_at=now();
end $$;
create function public.remove_device(p_installation_id uuid) returns void
language sql security definer set search_path = '' as $$
  delete from public.devices where user_id=auth.uid() and installation_id=p_installation_id;
$$;
create function public.mark_notification_read(p_id uuid) returns void
language sql security definer set search_path = '' as $$
  update public.notifications set read=true where id=p_id and user_id=auth.uid();
$$;

-- RPCs are exposed deliberately; private tables and mutation SQL are not.
revoke all on function public.my_profile(),public.save_profile(text),
  public.submit_report(uuid,text,text,text,text,double precision,double precision,text),
  public.update_report_status(uuid,text,text),public.vote_report(uuid,boolean),
  public.register_device(uuid,text),public.remove_device(uuid),public.mark_notification_read(uuid) from public,anon;
grant execute on function public.my_profile(),public.save_profile(text),
  public.submit_report(uuid,text,text,text,text,double precision,double precision,text),
  public.update_report_status(uuid,text,text),public.vote_report(uuid,boolean),
  public.register_device(uuid,text),public.remove_device(uuid),public.mark_notification_read(uuid) to authenticated;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
  values('report-images','report-images',false,256000,array['image/jpeg']);
create policy pinpoint_photo_read on storage.objects for select to authenticated using (
  bucket_id='report-images' and (
    split_part(name,'/',1)=(select auth.uid())::text or
    exists(select 1 from public.reports r where r.photo_path=name)
  )
);
create policy pinpoint_photo_upload on storage.objects for insert to authenticated with check (
  bucket_id='report-images' and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f-]{36}/photo[.]jpg$')
);
-- No update policy: existing evidence can never be overwritten. Abandoned uploads
-- can be deleted by their owner only while no report references them.
create policy pinpoint_photo_cleanup on storage.objects for delete to authenticated using (
  bucket_id='report-images' and split_part(name,'/',1)=(select auth.uid())::text and
  not exists(select 1 from public.reports r where r.photo_path=name)
);
commit;
