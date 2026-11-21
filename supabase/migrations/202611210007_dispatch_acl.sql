begin;
-- pg_net's extension defaults must not let app roles inspect outbound headers.
revoke all on schema net from public,anon,authenticated;
revoke all on net.http_request_queue,net._http_response from public,anon,authenticated;
select vault.update_secret(id,encode(extensions.gen_random_bytes(32),'hex'))
  from vault.secrets where name='pinpoint_push_dispatcher';
commit;
