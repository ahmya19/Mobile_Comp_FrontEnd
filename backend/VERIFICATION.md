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
- Notification history now loads 50 at a time with a stable created_at/id cursor and a "Load older notifications" button. It builds, lints and passes JVM tests; a live check with the demo accounts is still pending.
- Map marker APIs in the pinned SDK are deprecated but functional; a later SDK/annotation migration remains maintenance work.
- Team build, review and acceptance of the shared branch/PR.

## CP-16 emulator UI rehearsal, November 21

The existing Pixel_8 Google Play emulator (API 37) was confirmed available for PinPoint. All actions targeted emulator-5554; physical-phone testing remained deferred.

WorkflowRehearsalTest and the emulator-only PowerShell runner passed three consecutive phases using the existing Compose screens and live backend:

| Phase | Result | Test runtime |
| --- | --- | --- |
| Citizen | UI login, form entry, selected JPEG result, durable submission, acknowledged report, displayed photo, no official controls | 16.65 s |
| Official | UI logout/login, community report, assigned-barangay controls, Pending → Under Review and persisted history | 19.76 s |
| Citizen review | UI logout/login, notification opens the correct report and persists its read flag | 8.87 s |

Hosted verification of report a3695397-0d75-4e88-94e8-0aa81602c88b returned Water Interruption, Under Review, both photo paths present, one history row and one read notification. These runtimes cover complete test phases and are not submission-latency averages.

The native photo-picker result is stubbed with a generated JPEG; Auth, queue workers, Storage uploads, report mutation and notification reads use the live services. Sequential role switching on one emulator does not test simultaneous two-phone push. Real gallery permissions, GPS accuracy, cellular switching and carrier SMS remain unverified.

The initial harness used the full description to match a card whose title is capped at 80 characters, then lost its Compose hierarchy during the long wait. Matchers now use the displayed title, transient root absence is tolerated, and the runner uses short independent sessions. The corrected three-phase run and Android lint passed (0 errors, 33 warnings). No production application logic changed in this batch.

One later repeat timed out waiting for Home after the official sign-in click; an isolated official retry passed without changing Auth or application code. The harness now replaces credential fields, closes the keyboard, waits for the enabled sign-in control and includes redacted UI diagnostics on failure. Two complete consecutive repeats then passed: citizen/official/review took 24.02/22.64/11.03 seconds and 18.73/21.67/11.54 seconds. These are functional repeat checks, not statistical acceptance results. Runner checks also verified literal Android-shell handling of special characters, rejection of a physical-device target, and rejection of malformed credential JSON without echoing its contents.

## Shared code

## CP-08 confirmation preparation, November 21

The user selected demo accounts for now. Added a six-digit confirmation screen, resend with a persisted cooldown and numeric Retry-After handling, encrypted pending-email restoration, unconfirmed-login routing and clearing of sensitive form fields. Prepared supabase/templates/confirmation.html; no hosted template, SMTP account or email delivery configuration was changed.

EmailConfirmationTest passed 6 tests on emulator-5554 in 12.14 seconds. Fake public Auth responses exercise the signup/verification session boundary, restoration, resend throttling and unconfirmed login. Compose checks cover input validation, clearing and disabled busy controls. A real invalid verification request returned HTTP 403 with error_code=otp_expired and no session. This exposed a client parser issue: Auth's numeric code was masking its symbolic error_code. The parser now prefers error_code and preserves the exact message, Token has expired or is invalid.

Debug/test APK builds and 4 JVM tests passed. Android lint remains 0 errors and 33 warnings. No new dependency or schema migration was required. Real inbox receipt, successful real-code verification and public signup remain pending under [CP-08](EMAIL_CONFIRMATION.md).

[Draft PR #1](https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1) contains the backend and mobile integration on backend/pinpoint-free. GitHub confirms DreiwanabeTexh has write collaborator access. No merge was performed. Final review included business/ownership boundaries, current schema/function grants, queue identity, Wi-Fi binding, recipient checks, credentials, whitespace and the documented acceptance gaps.

## CP-16 rerun after email confirmation and notification pagination, November 21

On emulator-5554, the three-phase workflow passed again against the live backend (citizen 13.81 s, official 18.48 s, review 7.37 s). An earlier failure was a harness issue: reports from previous runs lengthened Home, pushing Quick Report below the visible area, so the click missed. The test now scrolls to it and waits for the category field. No application logic changed for this fix.

On the physical TECNO phone, EmailConfirmationTest passed 6/6. The workflow run there was blocked by Google Password Manager's "Save password?" popup covering the app after sign-in; the same harness failure was the actual cause on the emulator, so the phone workflow should be retried with autofill saving declined. Notification pagination compiles and lints but has no live test: the demo accounts have fewer than 50 notices, so "Load older notifications" is not exercised.

## CP-23 simulated SMS test, November 21

Against the hosted sms-report function with SMS_GATEWAY_FORMAT=sms-gate and temporary random secrets (since removed), signed requests were sent from a script; no carrier or gateway app was involved.

| Case | Result |
| --- | --- |
| Valid sms:received, PIN WATER to Test Barangay A | HTTP 200, one report (source sms, no owner, barangay centroid) |
| Retry, new delivery id, same message | HTTP 200, same report id, no duplicate |
| Tampered body | 400 invalid or expired signature |
| Timestamp 15 minutes old | 400 invalid or expired signature |
| Wrong deviceId | 400 unsupported event |
| Text without the PIN format | 400 format guidance |
| Barangay not in the approved table | 400 configure an approved centroid |
| sms:sent event | 400 unsupported event |

The test report and receipt were deleted and the secrets unset; the function again answers "SMS gateway is not configured". Three Test Barangay rows (coordinates rounded to about 1 km) remain in private.barangays as placeholders; replace them with the group's approved list. Real receipt of a carrier SMS and the 90% / two-minute target are unmeasured.
