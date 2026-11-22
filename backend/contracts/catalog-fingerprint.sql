-- Deterministic fingerprint of the PinPoint-owned database objects.
-- One portable, read-only query used in two places:
--   1. backend/tests/migration-replay.test.mjs runs it after replaying all nine migrations in PGlite
--      and compares it with backend/contracts/final-catalog.json.
--   2. backend/scripts/catalog_drift.py runs it read-only against the linked hosted project and compares
--      the same snapshot, to detect hand-applied or missing changes.
-- NOT NULL pg_constraint rows (contype 'n') are skipped: PostgreSQL 18 lists them, 17 does not, and the column list already records not null.
-- It never reads row data or secret values. Extension-owned objects and platform schemas are excluded.
select jsonb_build_object(
  'tables', (select coalesce(jsonb_agg(jsonb_build_object(
      'name', n.nspname || '.' || c.relname,
      'rls', c.relrowsecurity,
      'columns', (select jsonb_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
          || case when a.attnotnull then ' not null' else '' end
          || coalesce(' default ' || pg_get_expr(d.adbin, d.adrelid), '') order by a.attnum)
        from pg_attribute a left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
        where a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped),
      'constraints', (select coalesce(jsonb_agg(k.conname || ' ' || pg_get_constraintdef(k.oid) order by k.conname), '[]'::jsonb)
        from pg_constraint k where k.conrelid = c.oid and k.contype <> 'n'),
      'privileges', (select jsonb_object_agg(r, (select coalesce(jsonb_agg(p order by p), '[]'::jsonb)
          from unnest(array['select','insert','update','delete','truncate','references','trigger']) p
          where has_table_privilege(r, c.oid, p)))
        from unnest(array['anon','authenticated','service_role']) r)
    ) order by n.nspname, c.relname), '[]'::jsonb)
    from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where c.relkind = 'r' and n.nspname in ('public','private')),
  'indexes', (select coalesce(jsonb_agg(indexdef order by indexdef), '[]'::jsonb)
    from pg_indexes where schemaname in ('public','private')),
  'functions', (select coalesce(jsonb_agg(jsonb_build_object(
      'signature', n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
      'returns', pg_get_function_result(p.oid),
      'language', (select l.lanname from pg_language l where l.oid = p.prolang),
      'security_definer', p.prosecdef,
      'volatility', p.provolatile::text,
      'config', coalesce(to_jsonb(p.proconfig), '[]'::jsonb),
      'public_execute', coalesce((select bool_or(x.grantee = 0 and x.privilege_type = 'EXECUTE')
          from aclexplode(p.proacl) x), true),
      'execute', (select jsonb_object_agg(r, has_function_privilege(r, p.oid, 'execute'))
          from unnest(array['anon','authenticated','service_role']) r)
    ) order by n.nspname, p.proname, pg_get_function_identity_arguments(p.oid)), '[]'::jsonb)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname in ('public','private') and p.prokind = 'f'
      and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')),
  'policies', (select coalesce(jsonb_agg(jsonb_build_object(
      'table', schemaname || '.' || tablename, 'name', policyname, 'command', cmd,
      'roles', roles::text, 'using', qual, 'check', with_check) order by schemaname, tablename, policyname), '[]'::jsonb)
    from pg_policies where schemaname = 'public' or (schemaname = 'storage' and policyname like 'pinpoint%')),
  'cron_jobs', (select coalesce(jsonb_agg(jsonb_build_object('name', jobname, 'schedule', schedule, 'command', replace(command, chr(13), ''))
      order by jobname), '[]'::jsonb) from cron.job where jobname like 'pinpoint-%')
)::text as fingerprint
