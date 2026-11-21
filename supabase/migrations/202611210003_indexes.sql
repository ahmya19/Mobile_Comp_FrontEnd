begin;
create index sms_receipts_report on private.sms_receipts(report_id);
create index notifications_report on public.notifications(report_id);
create index report_history_report on public.report_history(report_id,created_at);
create index report_history_actor on public.report_history(changed_by);
create index report_votes_user on public.report_votes(user_id);
commit;
