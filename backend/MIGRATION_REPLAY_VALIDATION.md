# Migration replay and fresh-project reproducibility, November 23, 2026

Checkpoint CP-31. Scope: can the nine files in `supabase/migrations` rebuild the backend, and does the rebuilt FINAL state match what is deployed? **This is not a fresh hosted restore.** Nothing was created on any Supabase project; docker is not installed here, so no local Supabase stack could run.

## Result

| Evidence | Result | What it proves | What it does not prove |
| --- | --- | --- | --- |
| `backend/tests/migration-replay.test.mjs` (12 tests, runs in CI) | All nine files replay in order from an empty PostgreSQL; final state checked | The SQL is valid and ordered; superseding migrations leave the intended final functions, grants, RLS, indexes and cron registrations; dispatcher logic behaves as designed against stubs | Real pg_cron scheduling, real pg_net HTTP, real Vault encryption, hosted Auth/Storage APIs |
| Mutation checks (10 deliberate migration breaks) | All 10 caught by the intended tests | The tests are not vacuous | Unlisted mutations |
| `backend/scripts/catalog_drift.py` (read-only, hosted) | **Identical**: 11 tables, 21 indexes, 11 policies, 2 cron jobs, 17 functions, 0 differences | The deployed PinPoint objects, RLS flags, defaults, constraints and per-role grants equal the replayed final state | That a clean project would restore identically (nothing was created); data; Vault values |
| Hosted read-only platform facts | PostgreSQL 17.12; pg_cron 1.6.4, pg_net 0.20.4, pgcrypto 1.3, supabase_vault 0.3.2; Vault holds only `pinpoint_project_url` and `pinpoint_publishable_key`; scheduler job active, 359 runs in the previous hour, 0 non-successful | The live platform matches the assumptions behind the stubs; the old reusable dispatcher secret is gone | Delivery to FCM (separate CP-15 evidence) |

Replay engine: PGlite 0.5.17, **PostgreSQL 18.7** (WASM). Hosted: **17.12**. Behavior was identical for every migration statement, but the engine gap is real: the fingerprint ignores `NOT NULL` rows in `pg_constraint` because only 18 lists them.

## Migration coverage matrix

Before this checkpoint `backend/tests/sql.test.mjs` replayed 5 of 9 files (001, 002, 004, 005, 009). 003, 006, 007 and 008 were never replayed. Now all nine are, from a clean database, by the shared harness `backend/tests/support/replay.mjs`.

| File | Contribution | Superseded or amended later | Platform dependency | Coverage now |
| --- | --- | --- | --- | --- |
| 001 pinpoint | Schemas, 9 tables, constraints, RLS, community/owner policies, 8 client RPCs with grants, report and original photo buckets and storage policies | `finish_push` (006), none other | `auth.users`, `auth.uid()`, `storage.buckets/objects` | Replay + existing `sql.test.mjs` + `security-failure-paths.test.mjs` |
| 002 delivery | `private.barangays`, SMS ingest, `claim_push_batch`, `finish_push`, `remove_invalid_push_token`, service-only grants | `claim_push_batch` (009), `finish_push` (006) | `service_role` | Replay + SMS and lease tests |
| 003 indexes | 5 foreign-key indexes | none | none | Inventory + "every FK column is indexed" test |
| 004 originals | `original_path`, original bucket, upload/read policies, `can_read_original`, `attach_original` | none | storage policies | Replay + originals tests |
| 005 push retries | `pending_push_reports` | none | none | Replay + barangay-scoping test |
| 006 push dispatch | pg_cron and pg_net extensions, Vault secret, first dispatcher, backoff `finish_push`, 2 cron jobs | Dispatcher replaced by 008; secret removed by 008; `finish_push` backoff is final | **pg_cron, pg_net, Vault, pgcrypto in `extensions`** | Replay against stubs; backoff and cron logic tested; extension behavior platform-only |
| 007 dispatch ACL | Revoke on `net` objects, rotate the (later removed) secret | secret removed by 008; revokes are best-effort on hosted pg_net | pg_net tables, Vault | Replay; effect on hosted is platform-only (revokes may warn) |
| 008 dispatch nonce | `private.push_dispatch_tokens`, one-use `authorize_push_dispatch`, nonce `dispatch_pushes`, removes reusable secret | none (final) | Vault (project URL and key), pg_net | Nonce lifetime, one-use, replay, expiry, cleanup, ACL tested |
| 009 push recipient | `claim_push_batch` returns `user_id` per device | none (final) | none | Recipient binding, 100-device cap, ordering tested |

Final-state notes: `public.authorize_push_dispatch` no longer reads Vault; `private.dispatch_pushes` reads only the project URL and publishable key from Vault and mints a 32-byte token per run; `private.push_dispatch_tokens` stores only a SHA-256 hash and an expiry; the live dispatcher secret `pinpoint_push_dispatcher` does not exist after 008. Those facts are asserted.

## Dispatcher, lease and ACL behavior verified (stubs)

Nonce accepted once; replay, unknown, malformed, upper-case, null and expired tokens rejected; only `service_role` may call `authorize_push_dispatch`; no API role may call `private.dispatch_pushes`; an expired token row survives until the next dispatch deletes it. Nothing pending or no Vault configuration means no call and no token. A configured run makes one call with the expected URL, publishable-key header, 30 s timeout, empty body and a token whose hash (not plaintext) is stored for 60 s. Claims return batches of 20, hide leased work, reclaim after lease expiry with a new token and incremented attempts, ignore delivered rows and honour the report filter. `finish_push` ignores a wrong lease token and backs off 10 s x 2^attempts capped at 30 minutes (20 s, 80 s, 1280 s, then 1800 s); errors truncate to 512 characters. Claimed devices belong to the notification owner, newest first, capped at 100, with `user_id` attached. Two cron jobs exist with the expected names and schedules; the retention command removes only PinPoint job history older than seven days.

Not provable without the real platform: that `pg_cron` runs `10 seconds` jobs (hosted shows 359 runs/hour), that `pg_net` delivers the request, that two concurrent claimers cannot overlap (`FOR UPDATE SKIP LOCKED` needs two sessions; PGlite has one; the older SQL test covers the sequential case), Vault encryption, and FCM.

## Reproduce

~~~powershell
cd backend
npm ci
npm test                       # 53 tests including the nine-migration replay and security paths
python scripts/catalog_drift.py   # read-only; needs a logged-in, linked Supabase CLI
~~~

If a migration change is intended, review the diff, then `UPDATE_CATALOG=1 npm test` to refresh `backend/contracts/final-catalog.json`. To mutation-test, copy `supabase/migrations`, break one statement and run with `MIGRATIONS_DIR=<copy>`.

## Fresh Supabase project: ordered setup and verification (NOT performed)

Use only a NEW, empty Free project in an organization on the Free plan. Never run these against `jaysxjjdvaitgrbqjlhe`. No secret values belong in Git or chat.

1. **Project**: create it in the dashboard, then `npx supabase login` in a normal terminal and `npx supabase link --project-ref <new ref>`.
2. **Extensions first** (Database, Extensions): enable `pg_cron`, `pg_net`, `pgcrypto` (schema `extensions`, normally default) and `supabase_vault`. Verify before migrating: `select extname,extversion from pg_extension where extname in ('pg_cron','pg_net','pgcrypto','supabase_vault');` and `select to_regclass('net.http_request_queue'), to_regclass('net._http_response');` (006/007 revoke on those two tables; a pg_net version without them would make the migration fail).
3. **Migrate**: `npx supabase db push` applies the nine files in order. Each file already contains its own `begin`/`commit`. Expect warnings from the `net` revokes (the platform role owns those tables); an error is not expected.
4. **Vault configuration** (the only environment-specific database input): create `pinpoint_project_url` (the API URL) and `pinpoint_publishable_key` (the public key) with `select vault.create_secret('<value>','<name>');` in the SQL editor. Do not create `pinpoint_push_dispatcher`; migration 008 removes it.
5. **Edge Functions**: deploy `sms-report` and `deliver-push` from `supabase/functions` with JWT verification off (their own checks authenticate; see `supabase/config.toml`).
6. **Edge secrets**: `FCM_SERVICE_ACCOUNT` (restricted sender key) via `npx supabase secrets set --env-file <private file>`. Set `SMS_GATEWAY_FORMAT`, `SMS_GATEWAY_ID`, `SMS_GATEWAY_SECRET` only when a real gateway exists; until then the receiver fails closed.
7. **Auth**: email confirmation on, OTP length 6, confirmation template from `supabase/templates/confirmation.html`, custom SMTP only when the group chooses one. Create the demo accounts, then assign the official: `insert into private.officials(user_id,barangay) values(...)`. Fill `private.barangays` with the approved names and centroids.
8. **Android**: `local.properties` URL and publishable key; `app/google-services.json` for the FCM project.
9. **Verify**: (a) `python backend/scripts/catalog_drift.py` should print `identical: true`; (b) the extension and Vault-name queries above; (c) `select jobname,active from cron.job where jobname like 'pinpoint-%'` returns two active jobs, and after a minute `cron.job_run_details` shows successful runs; (d) `python backend/scripts/hosted_authz_probe.py ...` (ordinary-client checks, see SECURITY_FAILURE_PATH_VALIDATION.md); (e) the guarded emulator workflow in `backend/EMULATOR_TESTING.md`; (f) insert one test outbox row for a registered device and watch a push arrive (FCM), then delete it.

Known platform risks to check on a clean project: the `net.http_request_queue` and `net._http_response` names (step 2), `pg_cron` support for second-level schedules (1.5 or newer; 1.6.4 is deployed), the `vault.create_secret` and `vault.update_secret` signatures used by 006/007, default privileges on the `public` schema (the replay models Supabase's grants-to-API-roles default and confirms the migrations' explicit revokes), and running migrations that contain their own `begin`/`commit` under `supabase db push`.

## Proposed production corrections

None. The replay found no defect that needs a forward migration. Observations for the group, not changes: a priority-only update by an official (same status, new priority) writes a history row and a second notification (verified by a characterization test, BG-005); `register_device` reassigns a device row to the latest account that presents the same installation id (needed for account switching; it requires knowing that private id); originals have no delete policy, so an abandoned original upload cannot be removed by its owner. Any change would be a new forward migration for review, not an edit of an applied file.

<!-- ELEVEN-NOTE --> Update November 23: two forward migrations were added after this validation (010 priority-only status policy, 011 Alangilan jurisdiction); the replay now covers eleven files. The hosted project's migration history uses different timestamps than the file names, so do not use a plain `db push` there; see backend/APPROVED_DECISIONS.md.
