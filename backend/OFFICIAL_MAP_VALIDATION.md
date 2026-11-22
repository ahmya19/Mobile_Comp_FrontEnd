# Official-account hosted map validation — November 23, 2026

Verified on the isolated PinPoint_Backend_API36 emulator, API 36.1, using the existing official demo account and existing hosted reports. This batch adds tests/a runner and documentation; app runtime, dependencies, schema and deployed functions are unchanged. The user's physical phone remained unavailable and was not queried, operated or updated.

## Observed results

HostedOfficialMapTest passed **1/1 in 12.58 seconds** through MainActivity/AppNavigation with the ordinary public-key client. The my_profile RPC identified the expected user as an official and matched the assigned barangay from an independent SQL oracle.

| Scenario | Observed result |
| --- | --- |
| First 25 reports | Exact six rendered coordinate-report IDs matched SQL |
| Load more to all 36 reports | Exact 13 rendered IDs matched SQL; load-more button disappeared |
| Existing assigned-barangay stack | Native tap showed the 11-report chooser; selecting the oldest entry opened matching full description/category/coordinates |
| Assigned report details | Official controls and Save status were present; no mutation control was activated |
| Garbage category filter | Exact one rendered ID matched SQL |
| Existing report outside the assigned barangay | Correct details were readable; Official controls and Save status were absent |
| Return navigation | Back returned to Map; the session retained the expected official user |

The oracle was built from a separate privileged read-only SQL snapshot, not from the repository responses under test. Coordinates/descriptions and account values stay in private evidence. The test creates no reports, uploads or Auth accounts, and does not invoke status/priority/vote/profile mutations. Edit-control visibility is a UI/integration check; existing CP-22/SQL denials remain the evidence for server-side write authorization.

## Preservation and session cleanup

Before/after full-row fingerprints matched for all **36 reports, 15 notifications and 15 history rows**. Votes stayed zero and push-outbox rows stayed 15. All existing outbox entries were already delivered before the test. No new notice/push fixture was queued and no business-data cleanup was needed.

Normal application startup may register the emulator for FCM. Teardown finished its activities, cancelled its official device-registration worker, invoked remove_device only for that emulator installation and signed out with local scope. Independent SQL confirmed zero remaining rows for that installation. The completion file confirmed session cleanup. Auth sign-in/logout and device bookkeeping are ordinary test-session operations, separate from unchanged report data. Local sign-out preserves other sessions: [Supabase sign-out scopes](https://supabase.com/docs/guides/auth/signout).

The hosted test intentionally skipped when its opt-in flag was absent. Test APK build passed; final lint passed with 0 errors and 34 warnings. Unchanged Node/JVM/runtime suites retain their previous recorded baselines; they were not rerun for this test-only change. The emulator was stopped and reserved oracle/result files removed afterward. No phone or other AVD commands were issued.

## Repeat using existing data

Use PowerShell 7, an isolated emulator, private confirmed official credentials and a fresh independent SQL oracle. This bounded scenario expects 26-50 existing reports (two 25-row pages), additional coordinate IDs after page one, an assigned-barangay stack and a filtered coordinate report outside that barangay. If those prerequisites no longer hold, update the read-only scenario rather than recreating old fixtures.

The schema-1 oracle includes project_url, official_uid, official_barangay, total_reports, page1/all ID arrays, coords keyed by report ID, stack.ids/pick and filter.category/ids/target. Selected records include ID, description, category, status, barangay and coordinates. Keep this file and credentials/evidence outside Git.

~~~powershell
.\backend\scripts\run-hosted-official-map.ps1 `
  -EmulatorSerial emulator-5556 -CredentialFile C:\private\demo-accounts.json `
  -OracleFile C:\private\official-map-oracle.json `
  -OutputFile C:\private\official-map.log -Install
~~~

The runner validates project/account identity, rejects physical serials/repository paths/existing evidence, redacts official credentials in the archive, bounds instrumentation at eight minutes and requires both JUnit success and matching cleanup/completion evidence. Physical/path/existing-archive guards were checked without network or ADB operations; the existing archive hash stayed unchanged. Oracle preparation and independent baseline comparison remain host responsibilities; the runner performs neither SQL setup nor business-data cleanup. Inspect failed/partial evidence and session cleanup before retrying.

Private workspace evidence: work/official-map-before.json, official-map-oracle.json, official-map-live.log, official-map-completion.json, official-map-after.json, official-map-device-cleanup.json, official-map-default-skip.log, official-map-build.log and official-map-lint.log. No privileged credentials are placed in the emulator oracle/APK.

## Remaining scope

This verifies the ordinary official's existing-data map read/navigation path and assigned-versus-other-barangay controls. Broader filter/permission/error combinations, physical-phone official use, outdoor GPS, private-photo authorization/integrity and wider field acceptance remain separate checkpoints. No public email/carrier delivery or statistical latency measurement is claimed.
