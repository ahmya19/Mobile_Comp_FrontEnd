-- Substitute __CITIZEN__ and __OFFICIAL__ with the private confirmed fixture UUIDs before execution.
-- Execute this complete file in ONE connection. Every fixture and event is rolled back.
-- Database-role checks with controlled JWT claims are not an HTTP/Auth sign-in test.
begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';
create temporary table deployment_checks(name text primary key) on commit drop;
do $verify$
declare
  citizen uuid := '__CITIZEN__'; official uuid := '__OFFICIAL__';
  report uuid := gen_random_uuid(); other_report uuid := gen_random_uuid(); sms uuid; replay uuid;
  saved jsonb; row_data public.reports;
begin
  if not exists(select 1 from auth.users where id=citizen)
     or exists(select 1 from private.officials where user_id=citizen)
     or not exists(select 1 from private.officials where user_id=official and barangay='Alangilan') then
    raise exception 'Fixture roles/Alangilan assignment mismatch';
  end if;
  insert into deployment_checks values('fixture-role prerequisites');
  if (select count(*) from private.barangays)<>1 or not exists(
    select 1 from private.barangays where name='Alangilan' and latitude=13.7819 and longitude=121.0671) then
    raise exception 'Jurisdiction differs from the approved Alangilan configuration';
  end if;
  insert into deployment_checks values('exact approved jurisdiction');
  insert into public.reports(id,owner_id,category,description,location,barangay,status,updated_at)
    values(report,citizen,'Garbage','ROLLBACK-ONLY deployment verification','Synthetic verification location','Alangilan','Under Review','2020-01-01Z'),
          (other_report,citizen,'Other','ROLLBACK-ONLY assignment verification','Synthetic verification location','Unassigned verification area','Under Review','2020-01-01Z');
  perform set_config('request.jwt.claim.sub',official::text,true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',official,'role','authenticated')::text,true);
  execute 'set local role authenticated';
  perform public.update_report_status(report,'Under Review','Medium');
  execute 'reset role';
  select * into row_data from public.reports where id=report;
  if row_data.status<>'Under Review' or row_data.priority<>'Medium' or row_data.official_priority<>'Medium'
     or row_data.updated_at<='2020-01-01Z'::timestamptz then raise exception 'Priority fields/updated_at incorrect'; end if;
  if exists(select 1 from public.report_history where report_id=report)
     or exists(select 1 from public.notifications where report_id=report)
     or exists(select 1 from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id=report)
     then raise exception 'Priority-only change created status events'; end if;
  insert into deployment_checks values('priority-only fields'),('priority-only creates zero status events');
  select to_jsonb(r) into saved from public.reports r where id=report;
  execute 'set local role authenticated';
  perform public.update_report_status(report,'Under Review','Medium');
  perform public.update_report_status(report,'Under Review');
  execute 'reset role';
  if (select to_jsonb(r) from public.reports r where id=report) is distinct from saved then
    raise exception 'Exact/null-priority repeats changed the report'; end if;
  insert into deployment_checks values('exact/null-priority repeats preserve the row');
  -- Privileged fixture setup models vote counts; this is not a hosted ten-user voting study.
  update public.reports set vote_count=3,official_priority='High',priority='High' where id=report;
  execute 'set local role authenticated';
  perform public.update_report_status(report,'Under Review','Low');
  execute 'reset role';
  if not exists(select 1 from public.reports where id=report and official_priority='Low' and priority='Medium') then
    raise exception 'Three-vote floor lost'; end if;
  insert into deployment_checks values('three-vote Medium floor');
  update public.reports set vote_count=10,priority='High' where id=report;
  execute 'set local role authenticated';
  perform public.update_report_status(report,'Under Review','Medium');
  execute 'reset role';
  if not exists(select 1 from public.reports where id=report and official_priority='Medium' and priority='High') then
    raise exception 'Ten-vote floor lost'; end if;
  insert into deployment_checks values('ten-vote High floor');
  update public.reports set vote_count=0 where id=report;
  execute 'set local role authenticated';
  perform public.update_report_status(report,'Under Review','High');
  execute 'reset role';
  if not exists(select 1 from public.reports where id=report and official_priority='High' and priority='High') then
    raise exception 'Official High floor lost'; end if;
  insert into deployment_checks values('official High floor without votes');
  perform set_config('request.jwt.claim.sub',citizen::text,true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',citizen,'role','authenticated')::text,true);
  begin
    execute 'set local role authenticated';
    perform public.update_report_status(report,'Under Review','Low');
    raise exception 'Citizen priority update unexpectedly allowed';
  exception when insufficient_privilege then execute 'reset role'; end;
  insert into deployment_checks values('citizen mutation denied');
  perform set_config('request.jwt.claim.sub','',true);
  perform set_config('request.jwt.claims','{}',true);
  begin
    execute 'set local role anon';
    perform public.update_report_status(report,'Under Review','Low');
    raise exception 'Anonymous priority update unexpectedly allowed';
  exception when insufficient_privilege then execute 'reset role'; end;
  insert into deployment_checks values('anonymous mutation denied');
  perform set_config('request.jwt.claim.sub',official::text,true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',official,'role','authenticated')::text,true);
  begin
    execute 'set local role authenticated';
    perform public.update_report_status(other_report,'Under Review','Low');
    raise exception 'Other-barangay priority update unexpectedly allowed';
  exception when insufficient_privilege then execute 'reset role'; end;
  insert into deployment_checks values('other-barangay mutation denied');
  begin
    execute 'set local role authenticated';
    perform public.update_report_status(report,'Under Review','Urgent');
    raise exception 'Invalid priority unexpectedly allowed';
  exception when invalid_parameter_value then execute 'reset role'; end;
  insert into deployment_checks values('invalid priority denied');
  begin
    execute 'set local role authenticated';
    perform public.update_report_status(report,'Pending');
    raise exception 'Illegal status transition unexpectedly allowed';
  exception when invalid_parameter_value then execute 'reset role'; end;
  insert into deployment_checks values('illegal transition denied');
  if exists(select 1 from public.report_history where report_id=report)
     or exists(select 1 from public.notifications where report_id=report) then
    raise exception 'Priority/negative cases created events'; end if;
  execute 'set local role authenticated';
  perform public.update_report_status(report,'In Progress','Low');
  execute 'reset role';
  if (select count(*) from public.report_history where report_id=report)<>1
     or (select count(*) from public.notifications where report_id=report and user_id=citizen)<>1
     or (select count(*) from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id=report)<>1 then
    raise exception 'Actual status transition did not create exactly one event of each type'; end if;
  insert into deployment_checks values('real transition creates one history/notice/outbox event');
  execute 'set local role authenticated';
  perform public.update_report_status(report,'In Progress','High');
  execute 'reset role';
  if (select count(*) from public.report_history where report_id=report)<>1
     or (select count(*) from public.notifications where report_id=report)<>1
     or (select count(*) from private.push_outbox o join public.notifications n on n.id=o.notification_id where n.report_id=report)<>1 then
    raise exception 'Priority-only change after transition added events'; end if;
  insert into deployment_checks values('priority after transition adds no events');
  execute 'set local role service_role';
  sms := public.ingest_sms_report('deploy-verification',report::text,repeat('a',64),'Garbage','Synthetic SMS verification',' alangilan ');
  replay := public.ingest_sms_report('deploy-verification',report::text,repeat('a',64),'Garbage','Synthetic SMS verification','Alangilan');
  execute 'reset role';
  if sms<>replay or not exists(select 1 from public.reports where id=sms and source='sms' and owner_id is null
      and barangay='Alangilan' and latitude=13.7819 and longitude=121.0671 and location='Barangay center (SMS location)') then
    raise exception 'Approved SMS routing/retry incorrect'; end if;
  insert into deployment_checks values('SMS Alangilan centroid/label'),('SMS retry returns same report');
  begin
    execute 'set local role service_role';
    perform public.ingest_sms_report('deploy-verification',other_report::text,repeat('b',64),'Garbage','Synthetic rejected area','Test Barangay A');
    raise exception 'Simulation barangay unexpectedly accepted';
  exception when invalid_parameter_value then execute 'reset role'; end;
  insert into deployment_checks values('unapproved SMS barangay denied');
end $verify$;
select count(*)::int passed,jsonb_agg(name order by name) checks from deployment_checks;
rollback;
