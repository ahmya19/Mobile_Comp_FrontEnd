# CP-21 hosted report feed validation

Verified November 22, 2026 against Supabase Free, the isolated API 36.1 emulator and the existing TECNO app. Base main is 066e6ae following merged PR #2. Branch: backend/report-feed-validation. This batch changes tests and documentation; app runtime, dependencies, schema and server functions are unchanged.

## Hosted ordering and filters

The baseline contained 36 reports. Privileged setup inserted 90 labeled reports across two synthetic barangays and the existing citizen/official owners, with two categories, all five statuses, two creation dates and null/paired synthetic coordinates. No photo, notification or push-outbox fixture was created. Setup does not demonstrate ordinary client write permission.

After the real Kotlin ReportFeed loaded two 25-row community pages, the host inserted seven reports with the same timestamp and higher UUID suffixes. Independent SQL ordered by created_at desc,id desc supplied expected IDs. Refresh retained a contiguous 50-row window; draining the cursor matched all 67 Alpha reports without missing or duplicate IDs. Coordinate-bearing IDs matched the SQL subset. Coordinates are synthetic; GPS accuracy and marker rendering were not measured.

HostedReportFeedTest signs in normally and uses the real PinPointRepository/ReportFeed. All **17 cases** matched SQL on the first page and complete traversal:

- Community Alpha/Beta and citizen-owned Alpha/Beta.
- Garbage and Road Damage categories.
- Pending, Under Review, In Progress, Resolved and Rejected statuses.
- Old-day, new-day and inclusive equal-timestamp date boundaries.
- Empty future-date and missing-barangay results.
- Combined owner, barangay, category, status and date filters.

Filter changes restarted at one page. Overlapping Alpha/Beta refresh calls finished with the latest Beta filter and expected IDs. The opt-in test passed **1/1 in 53.091 seconds**. This is full test runtime, not latency acceptance; live failure/retry injection was not performed.

## Ordinary phone

The existing citizen session and installed APK were used; no phone instrumentation or new app build was installed. APK SHA-256: d008315e7a25bf8800a684e8143b5bc82b2eb2c8494127d3d56965761073fdcf.

The Alpha filter showed the newest burst report; opening it displayed the correct unique description, category, barangay, status and synthetic coordinates. Clearing and applying Alpha/Garbage/Resolved/2026-11-20 returned exactly reports 028 and 008 in SQL order. Clearing again and selecting Beta produced all 20 citizen-owned Beta titles in expected order, with no load-more control. An initial host scrolling capture skipped an off-screen title; smaller overlapping scrolls captured all 20. This was a capture limitation, not a reproduced backend omission.

Phone timezone: Asia/Manila. UI calendar dates use the phone zone; repository cases used explicit UTC instants. Fixtures fall within both tested day windows; timezone-transition coverage is not claimed. Phone map pins/navigation and outdoor GPS remain separate acceptance work.

Filters were cleared and the phone returned to PinPoint Home. User-requested USB stay-awake 2 remains enabled. No phone screen-off, shutdown or reboot test was run; existing OEM exemptions were retained.

## Cleanup and checks

Guarded cleanup removed exactly 97 reports matching the batch prefix and explicit UUID set. It checked owners, labels, attachments and notification/history/vote/SMS/storage references. All 36 original reports retained the same full-row fingerprint. Fixture reports remaining: zero; no Storage cleanup was needed. Previous CP-25 fixtures remain intact.

Debug/test APK builds and ten JVM checks passed. Lint passed with 0 errors and 34 warnings. Five scheduler checks passed; the hosted test skipped without its opt-in flag. The runner parsed and rejected a physical serial, repository output and an existing evidence archive before ADB; the archive hash stayed unchanged. No paid service, server deployment, secret or security policy changed.

## Repeat protocol

Capture the baseline and identity ledger outside Git. Seed 90 reports using a fresh prefix and UUID namespace, preserve the first 50 SQL-ordered IDs, then run:

~~~powershell
.\backend\scripts\run-hosted-report-feed.ps1 `
  -EmulatorSerial emulator-5556 -CredentialFile C:\private\demo-accounts.json `
  -FixturePrefix CP21-YYYYMMDD-A01 -FixtureGroupId YOUR_FIXTURE_GROUP_UUID `
  -OutputFile C:\private\cp21-hosted.log -Install
~~~

When files/cp21-report-stage.json reaches ready-for-burst, insert seven newer-ordered same-timestamp reports from the host. Atomically supply files/cp21-report-oracle.json in the isolated app's private files directory. Include prefix, group, before_first_50, alpha_ids, alpha_coordinate_ids and cases; each case has name, filter and ids from independent SQL. Filter fields are mine, barangay, category, status, after and before. The test waits at most 180 seconds for the oracle; instrumentation is bounded at eight minutes and matching completion evidence is required.

The runner neither seeds nor cleans hosted data. Retain exact redacted failures, clean up only recorded fixture identities and verify the baseline afterward. Repeat ordinary-phone checks separately. Claude should preserve these scenarios during UI restructuring and complete map/whole-app coverage in CLAUDE_HANDOFF.md.
