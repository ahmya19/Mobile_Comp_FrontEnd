begin;
alter table public.reports add column original_path text;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
  values('report-originals','report-originals',false,10485760,array['image/jpeg','image/png','image/webp']);
create policy pinpoint_original_upload on storage.objects for insert to authenticated with check (
  bucket_id='report-originals' and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f-]{36}/original$') and
  exists(select 1 from public.reports r where r.id::text=split_part(name,'/',2) and r.owner_id=(select auth.uid()))
);
-- A policy cannot directly access a private table as an unprivileged caller.
-- Keep the role lookup inside a narrowly scoped, read-only helper.
create function public.can_read_original(p_name text) returns boolean
language sql stable security definer set search_path='' as $$
  select exists(select 1 from public.reports r join private.officials o on o.barangay=r.barangay
    where o.user_id=auth.uid() and r.original_path=p_name);
$$;
create policy pinpoint_original_read on storage.objects for select to authenticated using (
  bucket_id='report-originals' and (split_part(name,'/',1)=(select auth.uid())::text or public.can_read_original(name))
);
create function public.attach_original(p_report_id uuid) returns void
language plpgsql security definer set search_path='' as $$
declare path text;
begin
  if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
  path=auth.uid()::text || '/' || p_report_id::text || '/original';
  perform 1 from public.reports where id=p_report_id and owner_id=auth.uid() for update;
  if not found then raise exception 'Report owner required' using errcode='42501'; end if;
  if not exists(select 1 from storage.objects where bucket_id='report-originals' and name=path)
    then raise exception 'Upload the original first' using errcode='22023'; end if;
  update public.reports set original_path=path,updated_at=now() where id=p_report_id;
end $$;
revoke all on function public.can_read_original(text),public.attach_original(uuid) from public,anon;
grant execute on function public.can_read_original(text),public.attach_original(uuid) to authenticated;
commit;
