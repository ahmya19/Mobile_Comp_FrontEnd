# Backend and integration defects

## BG-001 — ordinary-app background report upload delayed

Owner: Codex. Status: **Device-specific mitigation verified** on the configured TECNO; broader CP-25 acceptance remains open. Claude must retain restricted and unrestricted OEM-policy cases in whole-app/backend regression.

On the TECNO CL8 / Android 15, ordinary-form report/photo submissions survive offline saving but can remain unsent after validated connectivity while PinPoint is on Home. The baseline cellular observation had no report for at least 256.324 seconds after validation. After the separate BG-002 correction, the clean Wi-Fi observation still had no report for 263.945 seconds after validation. Workers had zero attempts; eventual recovery is not a timely automatic-delivery pass.

Read-only checks in the corrected Wi-Fi trial found the app UID's kernel cgroup frozen, inherited by the process, despite Android's isFrozen=false process flag. Normal foregrounding thawed the group and recovered the same report/photo. This established an execution blocker in that trial. The later Hiber/OEM-policy comparison below identifies a reproducible configuration dependency; it does not retrospectively prove historical run 8's exact cause. Run 8's exact diagnostic, No validated reconnect was observed, remains recorded separately.

The owner separately approved Unrestricted battery policy and one extra comparison. That report delivered automatically without reopening PinPoint, 127.422 seconds after host-observed Wi-Fi validation (128.577 seconds after availability). The observation has valid polling endpoints but fails the 60-second target. Kernel freezing was still sampled, with a thaw near execution. One result does not prove Unrestricted alone caused recovery or establish usual latency.

The earlier five separately approved diagnostic fixtures recovered once, with independently downloaded/decoded 512×512 JPEGs matching their saved compressed hashes (165,343 bytes each). Final SQL found five reports, five unique IDs, five photos, zero coordinates and zero originals. Keep these separate from the ten historical persistence/recovery samples. The baseline background report recovered during an unobserved gap; the user reports USB reconnection only. Its trigger and latency cannot be inferred.

The unattended follow-up identified Battery Lab's **Intelligent Management** policy for PinPoint, separate from Android Unrestricted; App Launch was already Allowed. Targeted Hiber logs recorded a PinPoint UID freeze and job-proxy event. A user-initiated transfer experiment also failed under that policy (no row for 292.384 s after Wi-Fi validation), so it was removed. The existing WorkManager build passes with only PinPoint set to **No Restrictions**: 1.310 s awake and 1.337 s screen off from host-observed availability. A reverse control on the same WorkManager build restored Intelligent Management and reproduced freezing/no row for 82.831 s after validation. Restoring No Restrictions recovered the same report without reopening PinPoint.

Six additional synthetic fixtures were verified once with matching decoded photos, no GPS/originals and local Synced acknowledgement. One preparation fixture is untimed; the failed native trial recovered before the OEM-policy change, so its recovery is not attributed to that change. Positive results are host polling endpoints on a USB-powered phone, not exact app-ack latency or statistical/carrier/overnight certification. Preserve the existing durable/account/idempotency contracts. Further platform acceptance remains CP-25; the user authorized this unattended project-specific batch. Current phone has the tested WorkManager APK and per-app No Restrictions; original radio/display settings are restored. See [setup](backend/ANDROID_BACKGROUND_SETUP.md). Fixtures remain hosted.

## BG-002 — empty restoration job blocks an expedited report

Owner: Codex. Status: **Fixed in 2c94c80**, verified locally and at the corrected phone queue boundary. This does not close BG-001.

First candidate 8691cbd enqueued an ordinary empty drain on account restoration. A subsequent offline submission's expedited work became BLOCKED behind it. The new restoration-then-submit regression reproduced this with Restoration must not add an empty predecessor expected:<1> but was:<2> (the assertion's exact unmodified text is retained in the private test log).

The correction skips report work when the active account/project has no Queued or Sending rows and schedules waiting originals directly. Genuine report drains retain expedited priority, quota fallback, CONNECTED, APPEND_OR_REPLACE and idempotent retries. Five local scheduler tests pass, along with ten JVM tests, debug/test APK builds and lint (0 errors, 35 warnings). Tests use a probe worker rather than live Supabase uploads. The corrected phone showed no empty restoration request and its real submission was ENQUEUED with only a completed predecessor. The old-Android foreground-service lifecycle remains untested.

Detailed evidence, timestamps, endpoint limits and source links: [backend/BACKGROUND_SYNC_INVESTIGATION.md](backend/BACKGROUND_SYNC_INVESTIGATION.md). Keep raw device dumps, serials, credentials and private queue copies outside Git.

## BG-003 — reports at one coordinate could not be opened from the map

Owner: Claude implemented the backend-integration fix; Codex verified the phone follow-up. Status: **Fixed and verified locally, on the hosted emulator and on the ordinary TECNO phone**.

Hosted data has 11 reports at exactly 13.78, 121.07. Tapping that point opened only the first rendered feature, so the other ten were unreachable from the map. SMS reports will stack the same way, since each barangay has a single centroid. Reproduction before the fix: `MapIntegrationTest#stackedMarkersOfferAChooserSoEveryReportIsReachable` failed with `ComposeTimeoutException: Condition still not satisfied after 10000 ms` waiting for a chooser. `ReportMap` opens one report directly and shows a chooser when several markers are hit. The ordinary TECNO pass matched all 11 chooser rows to SQL and selected the oldest report, whose full details matched; Back returned to Map. Existing session and hosted report/notification fingerprints were preserved. Evidence and limits: [backend/MAP_HOSTED_NAVIGATION_VALIDATION.md](backend/MAP_HOSTED_NAVIGATION_VALIDATION.md). The initial chooser's 25-entry cap is removed by BG-004 below; the phone pass itself does not establish larger-stack or outdoor GPS acceptance.

## BG-004 — stacked chooser discarded loaded reports after entry 25

Owner: Codex. Status: **Fixed and verified on the isolated emulator**, physical phone deliberately untouched while the user was using it.

The native map rendered all 26 report IDs but the chooser retained only 25 via hits.take(25), making older loaded reports unreachable. The new regression failed with the expected 26-versus-25 chooser count. The fix keeps all loaded hits and uses a bounded LazyColumn with stable report-ID keys. Final native checks cover report 26, a 75-report stack, Cancel/reopen and selection of report 75; existing small-stack, map filter, form pin and notification regressions also pass (6/6 combined). Ten JVM tests and builds pass; lint 0 errors/34 warnings. No hosted data, schema, dependency or phone changes. Details and remaining acceptance limits: [backend/MAP_STACK_VALIDATION.md](backend/MAP_STACK_VALIDATION.md).

## BG-005 — priority-only official change is recorded and notified as a status event

Owner: backend. Status: **Open, product decision needed** (found November 23 by the migration-replay and security work; pinned by a characterization test).

When an assigned official calls `update_report_status` with the report's current status and a different priority, the function writes a history row `Under Review -> Under Review` and inserts a second notification and push-outbox row ("Your ... report is under review."), although nothing about the status changed. Reproduction: `backend/tests/security-failure-paths.test.mjs`, test "characterization: a priority-only change...". An exact repeat changes nothing. Proposed fix, for review as a new forward migration (not an edit of an applied one and not deployed): when only the priority changes, update priority and `updated_at` without history, notification or outbox rows. The group should confirm whether priority changes should notify. See backend/SECURITY_FAILURE_PATH_VALIDATION.md.

## BG-006 — a captive-portal page marked a queued report Failed

Owner: backend/Kotlin. Status: **Fixed** (November 23), verified on the emulator against a local fake server.

A 200 response whose body was a web page threw `JsonSyntaxException`, which is not an `IOException`; `ReportSyncWorker` therefore treated it as permanent and set the report to `Failed` until the user tapped retry. `SupabaseBackend.json` now throws an `IOException`, so the worker retries. Reproduction and proof: `AuthQueueFailureTest#captivePortalPageIsRetriedNotMarkedFailed` (fails when the conversion is reverted). Details: [backend/AUTH_QUEUE_FAILURE_VALIDATION.md](backend/AUTH_QUEUE_FAILURE_VALIDATION.md).

## BG-007 — a server-rejected token that looked valid locally could not recover

Owner: backend/Kotlin. Status: **Fixed** (November 23), emulator fault injection only.

If a device clock hid a token's expiry, the server's 401 became a permanent failure. `SupabaseBackend.request` now refreshes once and repeats the request (a 401 is rejected before execution, so repeating a POST is safe). Proof: `AuthQueueFailureTest#tokenTheServerRejectsIsRefreshedOnceAndTheRequestRepeated`. Real hosted token-expiry behavior is not exercised.

## BG-008 — notifications were not bound to the receiving account

Owner: backend/Kotlin. Status: **Fixed** (November 23), emulator verified; physical-phone tray behavior unchecked.

A tapped notification opened its report in whichever account was signed in, and a signed-out account's report notifications stayed in the tray. The notification intent now carries the recipient id, `AppNavigation` honors it only for that account, and sign-out clears the report notifications. Proof: `NotificationRoutingTest` (JVM) and `AuthQueueFailureTest#notificationsAreRecipientBoundAndSignOutClearsThem`.

## BG-009 — the official-map runner silently skipped its setup on Windows PowerShell 5.1

Owner: backend tooling. Status: **Fixed** (November 23). Found during the whole-app regression.

`Invoke-PinPointProcess` used `ProcessStartInfo.ArgumentList` (PowerShell 7 only) and the `run-as sh -c "..."` step lost its inner double quotes on 5.1, so the oracle was not copied and the old result file was not removed; the runner still passed using leftover files. `backend/scripts/AndroidShell.ps1` now quotes arguments for both versions, and `run-hosted-official-map.ps1` verifies the on-device oracle equals the supplied one and that no stale result file remains. See [backend/WHOLE_APP_BACKEND_REGRESSION.md](backend/WHOLE_APP_BACKEND_REGRESSION.md).

## BG-010 — the map driver's guarded cleanup did nothing on Windows

Owner: backend tooling. Status: **Fixed** (November 23). Found when its post-check reported `CLEANUP-MISMATCH`.

`npx.cmd` (cmd.exe) truncates an argument at its first newline, so the multi-line cleanup transaction ran only `begin;` and left the two labelled fixtures on the hosted project (restored to baseline by a single-line rerun of the same guarded SQL, verified). `map_nav_hosted.py` now sends one line and refuses comments; `SqlTransportTest` (Python) fails without the fix, and the node PGlite tests execute the exact single-line form. The newline hazard guards were also added to `hosted_authz_probe.py`.

## BG-011 — photo orientation was discarded on save

Owner: backend/Kotlin data. Status: **Fixed** (November 23). `PhotoCompressor` re-encoded the decoded bitmap without applying the EXIF orientation tag, so a portrait phone photo was stored and uploaded sideways (EXIF is dropped on re-encode). It now applies all eight orientations before compression; GPS and other metadata stay out of the upload. `PhotoCompressorTest` failed 2 of 8 before the fix (expected 200x400, was 400x200) and passes 8/8.

## BG-012 — a failed photo save left a temp file

Owner: backend/Kotlin data. Status: **Fixed** (November 23). `ReportQueue.enqueue` opened `<id>.tmp` before compressing, and its failure cleanup only covered the final files, so a corrupt or vanished source left an orphaned file. It now compresses first and always deletes the temp file. `NativePickerTest.sourceDeletedBeforeSubmit...` failed on the leftover file before the fix.

## BG-013 — feed errors had no retry, and a failed empty list read "No reports match"

Owner: Kotlin UI state. Status: **Fixed** (November 23). The Map and report-list screens showed a feed error but no way to retry it, and a failed first load displayed "No reports match these filters." A **Retry loading reports** control (reusing the current filter and pages) was added and the empty message is hidden while an error is shown. Remaining polish: the error text still uses the app-wide `ExceptionClass: message` form.

## BG-014 — hosted-test drivers could delete the wrong rows and mislabel cleanup

Owner: backend tooling. Status: **Fixed** (November 23), proven locally only. Five tooling defects, not a production RLS exploit and not run against the hosted project: (1) the workflow driver deleted an inferred "sole new report"'s notices and history even after its identity checks failed; (2) verification exceptions skipped cleanup and logout; (3) forbidden upload attempts were not individually ledgered or verified; (4) the FCM probe's signed-out status change bypassed the per-send guard; (5) logout failures were swallowed or stopped other sessions' teardown, and runs were labelled cleaned from row counts. See [backend/HOSTED_TOOLING_FAILURE_SAFETY.md](backend/HOSTED_TOOLING_FAILURE_SAFETY.md).

## BG-015 — a kill between "Synced" and deleting the local photo left a private copy forever

Owner: Kotlin data. Status: **Fixed** (November 23). `ReportSyncWorker` marked a report Synced and only then deleted its compressed copy; a process death in that window left `<id>.jpg` in app-private storage with nothing ever revisiting it. Reproduced with a real SIGKILL (harness case S7: the copy remained after restart), fixed by `ReportQueue.purgeSyncedPhotos` on every schedule (S7 passes). Polish noted, not changed: a different-body object conflict surfaces as `HTTP_400: The resource already exists`. See [backend/PROCESS_DEATH_RECOVERY_VALIDATION.md](backend/PROCESS_DEATH_RECOVERY_VALIDATION.md).
