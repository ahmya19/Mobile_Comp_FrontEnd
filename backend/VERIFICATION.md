# Verification and remaining acceptance work

Recorded November 21, 2026. These are prototype results, not a claim that the proposal's field-study targets have been met.

## Automated and cloud checks

| Check | Result |
| --- | --- |
| PostgreSQL business/security tests plus GSM-7/HMAC tests | 14 passed; actual application migrations run in PGlite with Auth/Storage test schemas |
| Android JVM validation | 4 passed |
| Edge Functions | Deno type check passed for sms-report and deliver-push |
| Android build | Debug APK and instrumentation APK built; lint has zero errors, warnings retained |
| Backend package audit | Zero reported vulnerabilities in the npm test tooling dependency tree; this is not a complete security audit of the application or all Deno transitive dependencies |
| Hosted Auth | Confirmed citizen and official fixtures sign in; my_profile supplies the trusted official role |
| Hosted reports | Same UUID/content retry produces one row; citizen status change denied; assigned official update and exactly-one notification verified |
| Hosted photos | Private compressed JPEG upload/read passed; official read passed; anonymous read denied; profile persistence passed |
| Scheduled dispatcher | Active ten-second due-work job; no Edge call when outbox is empty; cron-only status update reached FCM in 4.12 seconds in one sample |
| Dispatcher replay | First use accepted, replay rejected; reusable dispatcher token removed from Vault after migration to one-use nonces |

SQL tests cover owner isolation, no client role assignment/direct report writes, status transitions, idempotency, voting, original photo privacy, SMS deduplication and push leases. Migrations 006-008 depend on hosted Vault/pg_net/pg_cron and were verified on the cloud rather than PGlite.

## Android checkpoint evidence

Device: existing Pixel_8 Google Play AVD, API 37, emulator-5554, headless software rendering. Only this test emulator's connectivity was changed. No physical phone was available.

| Phase | Observed result |
| --- | --- |
| Sign-in | Passed after enabling the emulator's virtual Wi-Fi; cached session opens the app offline |
| Offline enqueue | Compressed photo and optional original saved in private files with a durable SQLite record |
| Force-stop/reopen while offline | Owner, draft, queue state and both photo files survived |
| Reconnect | Worker committed the report and removed the acknowledged compressed local file |
| Metered Wi-Fi | Passed in 9.16 s test runtime: compressed report synced, original remained local and pending |
| Unmetered Wi-Fi | Passed in 7.59 s test runtime: original uploaded/attached and its local copy was removed |
| Resumable start | Upload intentionally interrupted after one acknowledged 6 MiB chunk; persistent session saved |
| Resumable restart | App force-stopped; HEAD/PATCH resumed and the completed original matched the saved bytes |
| Pagination/filter | One-row cursor pages reached every current citizen report without duplicates; future-date query empty; community barangay filter UI produced the expected empty state |
| Map | Map opens, attribution exists, and navigation back works; this does not measure GPS accuracy or visually certify every map tile |
| FCM background | Data-only server-cron delivery passed in 12.62 s total test runtime with no activity launched |
| FCM foreground/tap | Data-only server-cron delivery passed in 19.28 s total test runtime; notification opened the correct report and description |
| Account switching | Passed in 8.21 s: logout, official sign-in/role, queue-owner guard, token reassignment/removal, and citizen sign-in |

Test runtimes include setup and assertions; they are not report-sync or push-latency averages. The host test runner and fixture passwords are private local files, not repository content. BackendCheckpointTest requires an explicit phase; account credentials are supplied by its host through private instrumentation arguments.

Final baseline: 16 backend tests, 4 JVM tests, Deno checks, debug/app-test builds and lint passed (0 errors, 33 warnings). npm audit found no vulnerabilities in the backend tools or a separate lockfile of the pinned Edge runtime dependencies.

## Failures found and corrected

- Initial sign-in: UnknownHostException. Connectivity diagnostics showed no active emulator network. Enabling its virtual Wi-Fi restored access; the original sign-in test passed.
- Cellular-only sync: expected Synced but remained Queued. The AVD had no active cellular network. This is an unavailable test environment, so actual cellular testing remains pending. A metered-Wi-Fi policy test passed separately.
- Map: ExceptionInInitializerError caused by MapLibreConfigurationException. The HTTP customization accessed MapLibre before initialization. Initializing MapLibre first fixed the crash; the map smoke test passed.
- Notification test: the old ActivityScenario harness failed teardown with Activity never becomes requested state DESTROYED after a notification task launch. A harness that observes explicitly launched activities fixed teardown; receipt and notification-tap assertions now pass.
- Edge checking after removing legacy Firebase tooling: Deno could not resolve npm imports in manual node_modules mode. The check command now uses node-modules-dir=none and passes without rewriting npm's running command wrapper.

## Security advisor context

The security advisor reports 11 authenticated SECURITY DEFINER RPCs. They are intentional: mutations validate the authenticated owner or assigned official, use an empty search_path, and revoke anonymous execution. Tests exercise their rejection paths. Review new RPCs against the same boundaries. [Advisor documentation](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable).

The table inventory warns that private.officials, private.push_outbox, private.sms_receipts and private.barangays have RLS disabled. This is a defense-in-depth concern; the inventory's claim that anyone with the anon key can read them does not match the live grants: both anon and authenticated lack private schema USAGE and officials table SELECT. SQL tests deny direct access. The private schema is not an exposed API schema. No RLS change was silently applied to those existing tables. A separately reviewed hardening option is:

~~~sql
alter table private.officials enable row level security;
alter table private.push_outbox enable row level security;
alter table private.sms_receipts enable row level security;
alter table private.barangays enable row level security;
~~~

Only backend owners/service roles should access those tables; test their functions before adopting additional policies. [RLS reference](https://supabase.com/docs/guides/database/postgres/row-level-security).

The advisor also flags disabled leaked-password detection. That feature requires Pro or above; it was not enabled because this project must stay Free. Use unique passwords and complete public-registration testing. [Provider documentation](https://supabase.com/docs/guides/auth/password-security).

Hosted pg_net tables have platform-managed grants that the project postgres role could not narrow with REVOKE. They are not exposed through PostgREST. Dispatcher headers therefore use a random, single-use, 60-second nonce rather than a reusable secret. Only the server role can consume it against hashes in a private RLS-enabled table. Never expose the net or vault schemas through the API. Migration 007 records the earlier ACL attempt/credential rotation; 008 replaces that credential design.

## CP-25 field acceptance matrix

| Target from proposal | Current evidence | Required next check |
| --- | --- | --- |
| Mobile report payload at most 300 KB | Compressed JPEG capped at 256,000 bytes; originals excluded from metered uploads by policy | Measure complete request bytes including metadata on a phone |
| Average submission at most 10 s | Prototype calls and queue tests pass | Record repeated timed submissions under stated network conditions |
| 100% offline reports synced within 60 s, no loss | One forced-restart queue scenario passed | Multiple reports, prolonged offline periods, process/device restarts, counts before/after |
| At least 95% network-switch resume, no full images over cellular | Acknowledged-chunk restart/resume and metered deferral passed | Repeated real Wi-Fi/cellular switches with byte counters and stable upload IDs |
| At least 90% SMS parsed within 2 min, one 160-character SMS | GSM-7 parser/HMAC/dedup tests pass | Selected gateway/SIM, approved centroids, real carrier sample set |
| GPS accuracy within 20 m | Permission, timeout and pin controls implemented | Outdoor device measurements against known reference points |
| Notifications within 30 s | Background receipt; foreground/tap and server recovery pass; one provider acceptance sample 4.12 s | Timestamp phone receipt across foreground/background, permission denial and reconnect |
| SUS at least 70 | No participant study conducted | Group recruits participants and collects/scores the SUS questionnaire |

Record device/OS, build commit, connection type, sample count, timestamps, expected/actual IDs, bytes and exact failure messages. Do not turn an unmeasured row into a pass.

## Remaining product checks

- Public signup confirmation/email delivery and a continuous two-phone citizen/official rehearsal.
- Actual GPS accuracy, cellular handoffs, statistical acceptance targets and user study.
- Select SMS gateway, configure its credentials and approved barangay centroids, then adapt the exact payload format.
- Confirm the provisional 3/10 vote thresholds with the group.
- The notification list currently displays the latest 100 items. Full older-notification pagination is not implemented.
- Map marker APIs in the pinned SDK are deprecated but functional; a later SDK/annotation migration remains maintenance work.
- Team build, review and acceptance of the shared branch/PR.

## Shared code

[Draft PR #1](https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1) contains the backend and mobile integration on backend/pinpoint-free. GitHub confirms DreiwanabeTexh has write collaborator access. No merge was performed. Final review included business/ownership boundaries, current schema/function grants, queue identity, Wi-Fi binding, recipient checks, credentials, whitespace and the documented acceptance gaps.
