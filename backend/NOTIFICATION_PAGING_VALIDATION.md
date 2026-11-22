# CP-14 hosted notification pagination

## Final-build rerun — November 23, 2026

Codex reran `HostedNotificationPagingTest` after Claude's shared Auth/request changes, on candidate `1a387476bd0b7892f480781d41468e4e22384376`. App SHA-256: `7c43e7794a2e9a7b094da18a6b3aeafd71eb333459223644b5add9c52f98c149`; test APK: `6956221dbf62cfe1eb1b8bdd47bfbe205e8f6681d74bd1f980805d5b01506fad`. Only the isolated API 36 emulator `emulator-5556` was addressed.

The guarded PowerShell runner passed **1/1 in 25.845 s**. A new synthetic report and 123 equal-timestamp notices supplied the initial 100-row window. After the emulator reached its host checkpoint, 11 higher-ID notices were inserted. Fresh independent SQL arrays matched the initial 100, refreshed 100 and complete 149 rows, including all 134 fixtures without gaps/duplicates. The older-notice component callback opened the expected report, its read RPC was acknowledged, and both a separate SQL query and the test refresh verified persistence. Fixture outbox count remained zero. This rerun did not repeat the earlier ordinary-client access-control probe or phone navigation checks below.

All 134 notices and their report were removed along with the separate feed test's 97 reports by guarded, exact-ID transactional cleanup. Counts and full-row fingerprints of all 12 saved tables matched the pre-batch baseline, including the original 15 notice rows/read states, 36 reports, two devices and 45 Storage objects. No cleanup remains. No phone, attachment, deployment, migration, secret or billing change; the test locally signed out its own session and the owned emulator was stopped.

Private evidence: `work/paging-final-20261123-b01/ledger.json`, `notice-oracle.json`, `notice-test.log`, `cleanup.sql` and `result-and-cleanup.json` in the Codex task workspace. Test runtime includes the host handshake; no delivery-latency or field acceptance claim is made. The sections below retain historical results.

Status: **verified on hosted Supabase, the isolated emulator and the ordinary TECNO app**, November 22, 2026. Base main is 708b466 (PR #1 merged). Branch: backend/notification-pagination-validation; [PR #2](https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/2) is merged into main at 066e6ae. The app runtime, schema and server functions were unchanged in this batch.

## Hosted fixture and ordering checks

The existing demo citizen had 15 notifications. One labeled synthetic report and 123 notification fixtures were inserted as test setup, without push-outbox rows. All 123 shared the report's timestamp; deterministic UUID suffixes supplied a second ordering key. Privileged fixture setup does not demonstrate client write permission.

After the actual Kotlin feed loaded two 50-row pages, the host inserted 11 more notices with the same timestamp and higher UUID suffixes. The test compared the refreshed first 100 IDs with an independent SQL snapshot, then drained the remaining page and compared all 149 IDs, in order, without gaps or duplicates. The 134 test notices remained separate from the 15 original notices. No notification push was queued for these fixtures.

The opt-in HostedNotificationPagingTest uses the real PinPointRepository.pageNotifications, NotificationFeed and NotificationsScreen. It verified the correct report callback for an older notice, awaited mark-read completion, queried hosted persistence and refreshed the feed again. The isolated API 36.1 emulator run passed **1/1 in 37.645 seconds**. Component navigation is distinct from the whole-app phone navigation check below.

## Access-control checks

Ordinary citizen/official sessions with the publishable key verified:

- All 138 rows before the insert burst belonged to the signed-in citizen; another account saw zero rows for the protected notice.
- A wrong-owner mark_notification_read returned HTTP 204 with the owner's read flag unchanged. This RPC's zero-row no-op is expected; an exception is not required.
- The owner RPC returned 204 and the persisted read flag became true.
- A direct client notification insert was denied with HTTP 403 / PostgreSQL code 42501; the attempted row was absent afterward.
- An anonymous notification read was denied with HTTP 401 / code 42501.

Test-created Auth sessions were signed out locally. The phone's pre-existing demo session remained available.

## Ordinary phone checks

The existing installed APK (SHA-256 d008315e7a25bf8800a684e8143b5bc82b2eb2c8494127d3d56965761073fdcf) and real citizen session were used; no test instrumentation was installed on the phone.

The actual Notifications screen required two Load older notifications actions to reach notice 003. Tapping it opened the uniquely labeled report description. Returning showed the notice read. After force-stopping/reopening only PinPoint, the same notice remained read after two more load-more actions. Hosted data independently confirmed the read flag; exactly three fixture notices were read by the API, emulator and phone checks.

The phone stayed powered on and awake. User-requested USB stay-awake **2** remains enabled; no screen-off, phone shutdown or reboot test was run. Wi-Fi/airplane settings and per-app OEM exemption were retained. The phone was returned to PinPoint Home.

An old UnknownHostException banner was visible while loaded notification rows were displayed, then absent after app restart. Its origin was not timed; this is a feedback/UX observation for Claude, not evidence that the successful hosted operations failed. Keep it in whole-app UI review.

## Cleanup and final checks

Guarded transaction cleanup removed exactly 134 labeled notices and their one synthetic report. It refused unexpected owners, labels, attachments, history/votes or outbox references. The original 15 notice IDs, read flags and timestamps matched a saved state fingerprint before and after cleanup. Fixture reports/notices remaining: zero. Prior CP-25 fixtures and unrelated data were retained.

Debug/test APK builds and ten JVM tests passed. Final scheduler checks passed 5/5; the hosted test was intentionally skipped when the opt-in flag was omitted, verifying its default guard. The earlier harness initialization error, signOutTestSession() should be void, was corrected before the successful live run. Final lint passed with 0 errors and 34 warnings. No production dependency, billing, schema, RLS, Edge Function, secret or runtime behavior was changed.

## Repeat the hosted test safely

Use a new prefix/report identity and capture the account's existing baseline. Seed 123 same-timestamp notices, preserve the first 100 expected IDs, then run the guarded emulator-only script with private credentials/output outside Git:

~~~powershell
.\backend\scripts\run-hosted-notification-paging.ps1 `
  -EmulatorSerial emulator-5556 -CredentialFile C:\private\demo-accounts.json `
  -FixturePrefix CP14-YYYYMMDD-A01 -FixtureReportId YOUR_FIXTURE_REPORT_UUID `
  -OutputFile C:\private\cp14-hosted.log -Install
~~~

When files/cp14-notification-stage.json says ready-for-burst, insert exactly 11 newer-ordered fixture notices from the host. Supply files/cp14-notification-oracle.json atomically in the isolated app's private files directory. The oracle contains prefix, report, before_first_100, after_first_100 and all_ids arrays, obtained independently from SQL order by created_at desc,id desc. The test waits at most 180 seconds for it. Reserved control files are emulator-only; no privileged database credential belongs in the APK.

The runner rejects physical serials, repository-local credentials/output and existing archives. Synthetic-serial, repository-path and existing-archive guards were checked without invoking ADB; the existing archive hash stayed unchanged. It redacts demo credentials in logs and requires matching private completion evidence in addition to a passing JUnit result. Repeat phone checks with ordinary UI separately. Retain exact failures, clean up only the new fixtures, and preserve pre-existing data.

## Continuation

CP-14's hosted-paging gap is closed for these scenarios. Retry/read-rollback fault injection remains covered by the existing local tests; this batch did not inject live server faults. Claude still owns reference-based UI/UX mirroring and whole-app/backend regression. The private continuation prompt is PinPoint-Codex-to-Claude-CP14-Handoff.md in the user's Documents folder, copied to the task's outputs directory. It records current private evidence paths, actual Codex quota snapshots and remaining project work without secret values.
