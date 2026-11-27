begin;
-- CP-56 (groupmate feedback): citizens could edit only their name. Add an optional private contact number and an optional
-- home location (purok / street text). Both columns are nullable and additive; no existing row or function signature changes.
-- Privacy: the existing profiles_owner policy lets a user read only their own profile row, and nobody can write the table
-- directly (writes go through the SECURITY DEFINER RPC below), so contact numbers are never visible to other citizens.
alter table public.profiles
  add column contact_number text,
  add column home_location text,
  add constraint profiles_contact_number_check check (contact_number is null or contact_number ~ '^\+?[0-9]{7,15}$'),
  add constraint profiles_home_location_check check (home_location is null or char_length(btrim(home_location)) between 2 and 120);

-- Same signature as before; it now also returns the two new fields.
create or replace function public.my_profile() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('id',u.id,'display_name',coalesce(p.display_name,u.raw_user_meta_data->>'display_name','Citizen'),
    'official',o.user_id is not null,'barangay',o.barangay,
    'contact_number',p.contact_number,'home_location',p.home_location)
  from auth.users u left join public.profiles p on p.id=u.id
  left join private.officials o on o.user_id=u.id where u.id=auth.uid();
$$;

-- A blank value clears the field. The profile row already exists once the citizen has saved their name (save_profile).
create function public.save_profile_details(p_contact_number text, p_home_location text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Sign in first' using errcode='28000'; end if;
  update public.profiles
     set contact_number = nullif(regexp_replace(coalesce(p_contact_number,''), '[\s\-().]', '', 'g'), ''),
         home_location = nullif(btrim(regexp_replace(coalesce(p_home_location,''), '\s+', ' ', 'g')), ''),
         updated_at = now()
   where id = auth.uid();
  if not found then raise exception 'Save your name first' using errcode='P0002'; end if;
end $$;

revoke all on function public.save_profile_details(text,text) from public,anon;
grant execute on function public.save_profile_details(text,text) to authenticated;
commit;
