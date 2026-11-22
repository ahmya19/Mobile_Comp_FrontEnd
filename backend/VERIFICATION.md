# Verification and remaining acceptance work

Updated November 22, 2026. These are prototype results, not a claim that the proposal's field-study targets have been met.

Latest result: the established WorkManager runtime passes configured-TECNO background Wi-Fi checks awake and screen off after a separate Battery Lab per-app exemption (~1.3 s from observed availability). A reverse control reproduces freezing. Final five scheduler / ten JVM checks and build/lint pass; wider acceptance remains open. See the final unattended follow-up and [setup guide](ANDROID_BACKGROUND_SETUP.md).

## Initial read-only background investigation, November 22

Read-only run-8 SQL/gateway checks found the successful photo upload after foregrounding and about 0.153 s between Storage creation and report creation. No matching photo/submit POST appeared in the filtered pre-foreground gateway window; device-side failures and incomplete logs remain possible. Root cause is unresolved.

A host observer samples system connectivity and citizen-scoped server reads without instrumentation or app/radio changes. Eleven focused tests pass; the backend suite is 28/28. A three-second system-only emulator smoke check identified validated Wi-Fi without cloud login; a synthetic physical target was rejected before ADB access. This initial batch made no physical-phone operation, hosted business-data mutation, deployment, secret or APK change. The comparison was subsequently approved and completed below. Protocol and limits: [BACKGROUND_SYNC_INVESTIGATION.md](BACKGROUND_SYNC_INVESTIGATION.md).

## Automated and cloud checks

Subsequent approved phone comparison: two ordinary-form/gallery reports on the existing build recovered once with matching decoded photos. The resumed-activity control delivered about 8.639 s after host-observed availability (visibility caveat retained); the Home/background report stayed absent for 256.324 s after observed validation. Its mid-observation worker was ENQUEUED with zero attempts. It later recovered during an unobserved gap; the user reports only reconnecting USB. No trigger or reconnect latency is inferred from that gap. This reproduces a background non-start without instrumentation; BG-001 remains open.

The expedited-priority candidate passed debug/test APK builds, 10 JVM tests, lint (0 errors, 35 warnings) and 3 local scheduler checks on isolated API 36.1. The baseline failed priority and missing-foreground-info checks; the candidate passed 3/3, final 1.196 s. WorkManager test-driver constraints and a probe worker were used, so this is not a physical upload pass. Notification creation was checked on API 36.1; the old-Android foreground-service lifecycle remains untested. No candidate phone installation or hosted deployment/secret/schema change was made. Evidence and APK identity: [BACKGROUND_SYNC_INVESTIGATION.md](BACKGROUND_SYNC_INVESTIGATION.md).

| Check | Result |
| --- | --- |
| PostgreSQL business/security tests plus GSM-7/HMAC tests | 17 passed; actual application migrations run in PGlite with Auth/Storage test schemas |
| Background observer diagnostics | 11 passed; total backend suite 28/28; synthetic client/network fixtures, not a reproduced phone defect |
| Android JVM validation | 10 passed, including 6 notification paging/read-state regressions |
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
| 100% offline reports synced within 60 s, no loss | Ten phone reports recovered; seven new reports/photos independently verified once. One background observation stalled and recovered after reopen with no valid timer | Diagnose background recovery with an external observer; no blanket latency pass from these samples |
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
- Notification history loads 50 at a time. Six paging/read-state JVM regressions and an emulator UI check with 123 local notices pass. Hosted pagination with 50+ notices remains pending.
- Map markers were migrated from MapLibre's deprecated marker annotations to a GeoJSON source and circle layer (core SDK, no new dependency). On emulator-5554 the pins render, the view frames the loaded reports on first load, tapping a pin opens that report's details, and the map smoke test, lint (0 errors, 33 warnings) and unit tests pass. Long-press pin placement on the report form uses the unchanged long-click path and was not re-exercised by hand.
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

## CP-08 confirmation preparation, November 21

The user selected demo accounts for now. Added a six-digit confirmation screen, resend with a persisted cooldown and numeric Retry-After handling, encrypted pending-email restoration, unconfirmed-login routing and clearing of sensitive form fields. Prepared supabase/templates/confirmation.html; no hosted template, SMTP account or email delivery configuration was changed.

EmailConfirmationTest passed 6 tests on emulator-5554 in 12.14 seconds. Fake public Auth responses exercise the signup/verification session boundary, restoration, resend throttling and unconfirmed login. Compose checks cover input validation, clearing and disabled busy controls. A real invalid verification request returned HTTP 403 with error_code=otp_expired and no session. This exposed a client parser issue: Auth's numeric code was masking its symbolic error_code. The parser now prefers error_code and preserves the exact message, Token has expired or is invalid.

Debug/test APK builds and 4 JVM tests passed. Android lint remains 0 errors and 33 warnings. No new dependency or schema migration was required. Real inbox receipt, successful real-code verification and public signup remain pending under [CP-08](EMAIL_CONFIRMATION.md).

## Shared code

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

## Resumed emulator batch after 3b66064, November 21

Baseline rechecked: clean branch at 3b66064, 17 backend tests, 4 existing JVM tests, Edge type checks and debug build/lint passed. No hosted mutation or phone operation was performed in this batch.

Notification refresh previously replaced the newest 50 rows while retaining an older cached page. Ten new notifications could therefore remove ten previously visible rows between those pages. NotificationFeed now serializes refresh, load-more and read acknowledgement, refetching the number of loaded pages with a continuous cursor. Failures preserve the last complete window and retry cursor; a rejected read acknowledgement restores its unread marker. Notification work is scoped to the current account and cancelled on account changes.

Six new JVM regressions cover equal-timestamp ordering across 123 notices, 10 new inserts, a 60-insert burst, request failure/retry, failed/successful read acknowledgement and overlapping refresh/load-more requests. All pass; the JVM total is 10. Refresh now performs up to one read per loaded page every ten seconds, rather than refreshing just the first page.

NotificationAndMapUiTest passed 2/2 together in 9.60 seconds on emulator-5554:

- The existing notification screen loaded 123 local notices through its older-page button, opened report-2 from the correct older row and updated that row's read indicator.
- Native long-press on the existing report-form map filled both coordinate fields with finite coordinates near the initial map view, without submitting a report or requesting GPS.

The first map test timed out because Compose's synthetic gesture did not trigger MapLibre's native long-press detector. Replacing only that test gesture with Espresso's native longClick passed the isolated check in 4.72 seconds, then the combined run. No production map-gesture handler changed. The map now also exposes an accessible description and readiness state.

Debug and test APKs, 10 JVM tests and lint pass (0 errors, 33 warnings). Backend tests remain 17/17 and Edge checks pass. These local fixtures do not certify hosted pagination with 50+ notifications, outdoor GPS accuracy or physical-phone behavior. No new carrier, cellular or timing acceptance measurement is claimed.

## Approved phone batch, November 21; documented November 22

The user approved installing build 5efb41b on the connected TECNO CL8 (Android 15) and creating seven labeled hosted report/photo fixtures. The user controlled all radio changes. No schema, Edge deployment, secret, billing or persistent phone-setting change was performed. Phone testing has ended; the app was reopened normally and temporary test-window/debugger state cleared.

PhoneOfflineTrialTest and run-phone-offline-trial.ps1 exercised real compression, queue persistence, force-stop/restart, automatic worker recovery and authenticated cloud photo verification. All seven UUID/photo-hash records survived the restart and were ultimately verified as one hosted report with a matching photo. A final independent SQL read returned recovered_reports=7, attached_photos=7, duplicate_labels=0. The raw records remain outside Git, with no passwords, tokens or real GPS coordinates in the published evidence.

Run 7 used Wi-Fi despite planned cellular and retains its original mismatch error. Run 8's background observer stalled, returned No validated reconnect was observed after 500.616 s total instrumentation time, and recovered after PinPoint was reopened. Its timing is invalid; the full runtime is not upload latency. System diagnostics showed cellular validation, Wi-Fi off, background restriction disabled and no AOSP frozen flag. The cause is unresolved. Foreground cellular runs 9 and 10 then completed in 12.579/12.333 s from network availability, or 4.387/11.171 s from validation. These do not explain the earlier 48.5 s sample or prove the background target.

Including the earlier three samples gives ten recovery records, not ten unconditional automatic-latency passes. Detailed times, photo sizes, conditions and remaining measurement work are in [CP25_PHONE_TESTS.md](CP25_PHONE_TESTS.md). No handoff/GPS/push-timing/SUS target was closed by these offline trials.

The runner now rejects physical targets without an explicit switch, redacts credentials, preserves archived trial numbers and supports read-only re-verification of deviations. An independent host deadline was added after the stall: 330 s for observation, 90 s for other phases, stopping only PinPoint on timeout without clearing its queue. Local tests checked stdout/stderr/exit capture, malformed credentials, physical-target rejection and a 527 ms timeout of a deliberately delayed child. That host-timeout branch was not deliberately exercised on the real phone. Protocol: [PHONE_TRIAL_RUNNER.md](PHONE_TRIAL_RUNNER.md).


## Candidate follow-up, November 22

After approval, 8691cbd was installed with data retained. Its first ordinary background recheck had seven validated Wi-Fi samples followed by cellular that never validated; timing is invalid. An expedited request was BLOCKED behind an ordinary empty restoration drain. The report stayed intact and later recovered once with its matching decoded photo after foregrounding on validated Wi-Fi.

An expanded local test reproduced the restoration/submit ordering problem on 8691cbd (expected one request, got two). The correction omits empty report drains and directly schedules pending originals. Five local scheduler tests pass in 0.407 s, alongside ten JVM tests, debug/test APK builds and lint (0 errors, 35 warnings). At that stage the corrected APK was not installed and one fixture remained unused. The owner subsequently approved the installation and stable-Wi-Fi recheck; results follow below. See BACKGROUND_SYNC_INVESTIGATION.md for exact identities and limits.


## Final approved phone findings, November 22

Corrected build 2c94c80 (APK SHA-256 6970b799c3f07a9953ae8e38c3ba0fb5f973bd89e323679c0651f15e7ebe91f0) was installed and the actual offline restoration/submit boundary showed no empty blocker. It still missed background delivery for 263.945 s after Wi-Fi validation. Kernel UID freeze=1 was observed, inherited by the process despite Android isFrozen=false; normal foreground recovery changed frozen events to 0 and committed the same UUID/photo. This is a device-execution blocker, not a proven historical run-8 cause.

After explicit Unrestricted/extra-fixture approval, background delivery succeeded automatically at 127.422 s after observed validation (128.577 s after availability). All 35 visibility reads showed the app off screen and phone awake, with a kernel thaw sample near execution. The 60 s goal failed; BG-001 stays open. Five diagnostic fixtures were independently verified once with matching decoded photos, zero coordinates and zero originals. BG-002's empty-predecessor correction is fixed; the narrow fixture scopes for that earlier batch were consumed before the later unattended authorization. Full endpoints/limits are in BACKGROUND_SYNC_INVESTIGATION.md. Five local scheduler tests, ten JVM tests and build/lint remain passing; no server schema/deployment/secret/billing change was made.

## Unattended OEM-policy follow-up - November 22

The user authorized this project-specific unattended phone/PC batch. Separate TECNO Battery Lab Intelligent Management remained selected despite Android Unrestricted and App Launch Allowed. Targeted Hiber signals and a controlled reverse test tie the sampled kernel UID freezing to this per-app policy. Set only PinPoint to No Restrictions using [ANDROID_BACKGROUND_SETUP.md](ANDROID_BACKGROUND_SETUP.md).

The established WorkManager APK recovered automatically in 1.310 s from observed Wi-Fi availability / 0.096 s from validation while awake. Reverting just the OEM policy reproduced no row for 82.831 s after validation; restoring No Restrictions recovered that same report without reopening PinPoint. The final rebuilt APK passed a screen-off check at 1.337 / 1.337 s; all three samples were asleep, app off screen and kernel-unfrozen. These are host polling endpoints, not exact app-acknowledgement latency.

A native user-initiated transfer experiment also stalled under the restriction (292.384 s absent after validation); with the policy changed it passed at 1.264 s. It was removed, retaining the existing WorkManager implementation. Final runtime tests: 5 scheduler checks in 0.398 s, 10 JVM tests, debug/test builds and lint (0 errors, 35 warnings). Tested installed final APK SHA-256: d008315e7a25bf8800a684e8143b5bc82b2eb2c8494127d3d56965761073fdcf.

Six new distinct fixtures recovered once; all citizen-authenticated downloads decoded and matched their pre-reconnect compressed-photo hashes (512x512 JPEG, 165,343 bytes). Final SQL found 6 reports/photos, 0 coordinates/originals. One preparation fixture is untimed, and the first failed native trial recovered before the OEM-policy change; do not credit that recovery to the change. Keep the six cases separate from prior cohorts. Full outcomes and visibility limits: [BACKGROUND_SYNC_INVESTIGATION.md](BACKGROUND_SYNC_INVESTIGATION.md).

Radios and temporary USB stay-awake were restored (airplane off, Wi-Fi on, original stay-awake 0); phone left asleep with the tested APK and PinPoint-only OEM exemption. No global power-saving/freezer policy or security protection was disabled; no unrelated app, billing, schema, deployment or secret was changed. BG-001 has a verified device-specific mitigation; CP-25's wider device/carrier/overnight/unplugged and statistical acceptance remains partial.

## CP-14 hosted paging and ordinary phone - November 22

HostedNotificationPagingTest passed 1/1 (37.645 s) on the isolated API 36.1 emulator using real Supabase and Kotlin repository/feed/component code. Independent SQL ordering matched the loaded first 100 after an 11-notice equal-timestamp insert burst and all 149 rows afterward. Older-notice callback, server-acknowledged mark-read and refreshed persisted state passed.

Ordinary phone checks passed two load-more actions, correct linked report, read state after returning and after app restart. The phone was kept awake on USB per the latest user request. RLS/privilege checks passed with expected 401/403/42501 denials; wrong-owner mark-read was the expected 204 no-op. No outbox rows were created for fixtures. Cleanup removed 134 notices and one synthetic report, restored the count to 15 and preserved the original IDs/read flags/timestamps fingerprint.

Builds and ten JVM checks passed. Final lint passed with 0 errors and 34 warnings. Five scheduler checks passed; the hosted test deliberately skipped without its opt-in flag. The initial JUnit setup signature error was fixed before the live pass. See [NOTIFICATION_PAGING_VALIDATION.md](NOTIFICATION_PAGING_VALIDATION.md) for scope, repeat protocol and evidence boundaries. This does not close whole-app UI, SMTP, carrier/SMS or CP-25 field acceptance.

## CP-21 hosted feed and ordinary phone - November 22

HostedReportFeedTest passed 1/1 in 53.091 s: 17 SQL filter cases, equal-timestamp pagination, refresh after seven inserts, filter resets, overlapping refreshes and coordinate-ID inclusion. Ordinary TECNO controls returned two expected combined-filter reports, all 20 citizen-owned Beta rows and correct details. Smaller overlapping host scrolls corrected an initial capture omission.

Cleanup removed all 97 synthetic reports and preserved all 36 original rows by full-row fingerprint; no photos/notices/outbox were seeded. Debug/test builds, ten JVM checks and lint passed (0 errors, 34 warnings). Five scheduler checks passed; the hosted report test skipped by default. Runner physical/path/archive guards passed. Runtime/schema/dependencies are unchanged. Map rendering, timezone transitions, live fault injection and wider CP-25/whole-app acceptance remain separate. See [REPORT_FEED_VALIDATION.md](REPORT_FEED_VALIDATION.md).

## CP-22 voting and urgency - November 22

32 hosted API checks passed using two ordinary demo sessions: concurrent retries, idempotency, private reads, direct-write/role denials, closed votes, null/missing targets and official Medium/High floors. Independent SQL confirmed states, zero votes, two history rows and no notifications. Guarded cleanup removed six reports/two history rows; original 36 reports/zero votes unchanged. Thirty local tests passed, including upward/downward 3/10 boundaries using ten local users. Hosted ten-account/mobile UI acceptance is not claimed. Runtime/schema/thresholds unchanged. See [VOTING_VALIDATION.md](VOTING_VALIDATION.md).

## Native map integration - November 22

MapIntegrationTest passed 1/1 in 5.635 s on isolated API 36.1; test APK build passed. Real MapLibre rendered-feature IDs verified initial/page/filter states, invalid-coordinate exclusion and the native tap report-ID callback. Local callbacks supplied fixtures: this is not hosted map/full-app details navigation or GPS acceptance. No runtime/hosted/phone changes; phone remained Awake with USB stay-awake 2. See [MAP_INTEGRATION_VALIDATION.md](MAP_INTEGRATION_VALIDATION.md).
