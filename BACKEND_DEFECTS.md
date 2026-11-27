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

## BG-005 — priority-only official change was recorded and notified as a status event

Owner: backend. Status: **Fixed, deployed and hosted-verified November 23** (forward file 202611230010; hosted version 20261123043105). Eighteen rollback-only policy/permission/SMS checks passed; original business data and function security metadata were preserved. See backend/APPROVED_POLICY_DEPLOYMENT.md.

Before: an assigned official's priority-only call wrote a `Status -> Status` history row and a second citizen notification and push-outbox row. Approved policy: only an actual status change writes those; a priority-only change updates the official priority, the effective priority (3 votes Medium, 10 votes High floors preserved, votes never lower an official assessment) and `updated_at`; exact repeats stay idempotent. Replay tests cover priority-only, repeats, a real status change still producing exactly one history/notification/outbox event, the vote floors and ordinary-role denial, and fail when the migration is absent. See [backend/APPROVED_DECISIONS.md](backend/APPROVED_DECISIONS.md).

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

## BG-016 — real-FCM probe could pass a failed observation and tap a stale notice

Owner: backend test tooling. Status: **Fixed** (November 23). Failed matrix observations were merely logged; cleanup could still produce a successful exit. Back did not prove activity destruction, and identical notification text could select an older fixture's notice. The hardened probe raises on failed expectations, observes lifecycle destruction, clears its own tray before a case and requires exactly the expected notification ID before tapping. It uses a distinct admin-labelled citizen/official fixture pair and protects all non-fixture registrations, with guarded transactional cleanup and independent session outcomes. Regression/failing-control evidence and the separate emulator-connection investigation: [backend/FIXTURE_PAIR_FCM_VALIDATION.md](backend/FIXTURE_PAIR_FCM_VALIDATION.md). No production application or RLS defect is claimed from the stalled emulator connection.

## BG-017 — the original-photo upload never refreshed a server-rejected token

Owner: Kotlin data. Status: **Fixed** (November 23), proven by the CP-44 matrix with a failing control. `WifiOriginals.call` used `accessToken(owner)` once, so an upload whose token looked unexpired locally but was rejected (for example device clock skew) ended in permanent `UPLOAD_401`. It now refreshes once and repeats the request, the same rule `SupabaseBackend` applies. See [backend/ORIGINAL_TUS_RECOVERY_VALIDATION.md](backend/ORIGINAL_TUS_RECOVERY_VALIDATION.md).

## BG-018 — a kill after the attach was acknowledged left the private original on disk

Owner: Kotlin data. Status: **Fixed** (November 23). Same window as BG-015 for the optional original: `original_state=Uploaded` was written before the local file delete, and nothing revisited the file. `ReportQueue.purgeSyncedPhotos` now removes it on the next schedule.

## BG-019 — password-reset verification deadlocked on a non-reentrant Kotlin Mutex

Owner: Kotlin auth/data. Status: **Fixed** (November 23), verified on the emulator against the loopback auth fake (`UiParityAndCameraDraftTest#passwordRecoveryAndChangePasswordWorkAgainstLoopbackAuth` and `AuthQueueFailureTest` 12/12). `SupabaseAuth.completePasswordReset` originally held `mutex.withLock` while calling `backend.json("auth/v1/user", "PUT", ..., authenticated = true)`, which re-entered `accessToken()` on the same non-reentrant `Mutex` and hung the recovery flow indefinitely after OTP verification. The fix releases `mutex` after persisting the verified recovery session and performs the authenticated password update outside the lock.

## BG-020 — a deleted or edited unsent report was still submitted from the worker's stale snapshot

Owner: Kotlin data. Status: **Fixed** (November 23), found by independent review of CP-47. `ReportSyncWorker` listed Queued rows once and submitted them from that snapshot, while the new `deleteUnsent` and `updateUnsent` could change a row meanwhile: a deleted text-only report was still committed and an edited one was committed with its old content. `ReportQueue.claimForSend` now atomically claims one row and returns its current content (or null if it vanished). `QueueClaimRaceTest` (2 cases) failed before the fix. See [backend/CP47_INDEPENDENT_REVIEW.md](backend/CP47_INDEPENDENT_REVIEW.md).

## BG-021 — status-bar icons were unreadable on a dark-mode phone

Owner: Kotlin UI. Status: **Fixed** (November 23), found on the real TECNO screen. `MainActivity` used the default edge-to-edge style, which picks icon color from the phone's dark mode, so the clock, signal and battery were white on the app's always-light header. It now forces dark icons for the status and navigation bars; verified on the device.

## BG-022 — raw technical text on screen (ISO timestamps, ExceptionClass: message)

Owner: Kotlin UI. Status: **Fixed** (November 23). Report Details and Notifications showed timestamps like `2026-11-21T13:21:22.170879+00:00`, and errors appeared as `IllegalStateException: ...`. `UiText` formats times in the device zone and maps errors to plain sentences; the stored feed and queue diagnostics are unchanged. 9 JVM tests.

## BG-023 — PhotoCompressor had no fallback when BitmapFactory returned nothing

Owner: Kotlin data. Status: **Hardened** (November 23), root cause **unconfirmed**. One of the owner's real gallery photos failed with `Cannot decode the selected image.` and could not be reproduced with synthetic inputs (the photo was not inspected). An Android 9+ `ImageDecoder` fallback now decodes to a bounded size with the orientation applied once and no EXIF; corrupt, empty and vanished sources keep their messages. Six forced-path tests; 4 fail with the fallback disabled. See [backend/CP49_PHONE_VALIDATION_AND_POLISH.md](backend/CP49_PHONE_VALIDATION_AND_POLISH.md).

## BG-024 — two submissions took about 27 s on the real phone

Owner: Kotlin data. Status: **Hardened & instrumented; physical re-verification pending phone reconnect** (November 23, CP-52). Forensic inspection of the CP-50 ledger (`phone-accept-ledger.json`) showed Wi-Fi trial #6 (`27.43 s`, `389 KB` TX) and cellular trial #10 (`27.58 s`, `189 KB` TX) vs 18 fast trials (`0.99–4.47 s`, `140–154 KB` TX), while all 12 offline-reconnect trials completed within `5.2 s`. Without per-phase worker timestamps, the ledger alone cannot separate a mid-upload transient socket failure hitting WorkManager's implicit 30 s default backoff from a JobScheduler expedited-quota / previous-`am instrument`-teardown deferral (the extra TX bytes also overlap normal 27 s background traffic on the phone). Three code hardenings and phase-timing telemetry are now landed: (1) `SupabaseBackend` sets explicit `writeTimeout(20s)` and a 30 s idle `ConnectionPool` (avoiding stale 5-minute NAT/LTE keep-alive sockets); (2) `PinPointRepository.submit` performs one bounded `400 ms` fast retry on transient transport `IOException` during the already-idempotent photo `POST` (preserving 400/409 byte-equality reuse and all `submit_report` RPC failure contracts); (3) `ReportQueue.schedule` sets `BackoffPolicy.EXPONENTIAL` with `WorkRequest.MIN_BACKOFF_MILLIS` (10 s floor instead of 30 s default); (4) `ReportSyncWorker.recentTimings` + `PhoneAcceptanceTest` record `worker_start_delay_ms`, `submit_exec_ms`, `worker_attempts`, and `worker_last_error` (and let `ReportSyncWorker` finish committing `SUCCEEDED` after `client_submit_ms` is recorded before `am instrument` terminates the process). Verified on `emulator-5554`: `AuthQueueFailureTest` 15/15 (3 new photo-drop tests), `ReportSchedulingTest` 5/5 (backoff asserted), `QueueClaimRaceTest` 2/2, `OriginalRecoveryTest` 13/13, `UiParityAndCameraDraftTest` 6/6, JVM 23/23, lint 0/37, backend 81/81, Edge pass. Separately, CP-50's single `42.5 s` foreground FCM push outlier (`push8` P1) was traced in `push8/push-matrix.json` to downstream Google FCM → Play Services MCS delivery jitter (`outbox_attempts = 1`, `outbox_error = ""`, `created_to_dispatch_seconds = 9.66 s`, `android.priority = HIGH` already set, and the next 3 cases in `push8` arrived in `4.9–8.9 s`). Larger physical-phone submission and push re-runs await phone reconnection. See [backend/CP50_PHONE_ACCEPTANCE_COHORTS.md](backend/CP50_PHONE_ACCEPTANCE_COHORTS.md).

## BG-024 update (CP-53)

Status: **Not reproduced in 40 physical submissions** (20 Wi-Fi, 20 cellular; max 2.1 s, mean 0.85 / 1.30 s) after the CP-52 hardening. The earlier ~27 s outliers cannot be attributed to a cause or declared fixed by this data; telemetry fields remain in place.

## BG-025 — Map pins hidden under the floating chips and legend (CP-51 regression)

Owner: Kotlin UI. Status: **Fixed** (November 24), found on the real TECNO screen. The camera framed reports with 80 px padding, so edge pins sat under the filter chips and legend. `ReportMap.kt` now pads 100/260/100/240 px; verified on the phone. Also: latitude/longitude fields now use the same label size so they stay equal height.

## BG-026 — Auth screen polish: Back on Register, OTP boxes, disabled Confirm link

Owner: Kotlin UI. Status: **Fixed** (November 24), found on the emulator against Board 1. System Back on Register exited the app instead of returning to Sign in (`BackHandler` added); the six code boxes were display-only so tapping them did nothing (now focus the real code field); "Confirm an existing account" looked enabled while disabled (now dimmed). See [backend/CP55_BOARD1_DESIGN_AND_PREGROUP_AUDIT.md](backend/CP55_BOARD1_DESIGN_AND_PREGROUP_AUDIT.md).

## BG-027 — Confirmation and reset emails carried a link to localhost, and codes were 8 digits

Owner: Supabase Auth configuration. Status: **Fixed on the hosted project** (November 27); found by two groupmates. The project still sent Supabase's default link email whose redirect target (Site URL) is `http://localhost:3000`, so a phone showed "This site can't be reached". Supabase blocks template edits on the Free plan with the built-in sender, so custom SMTP (Brevo Free) was enabled first; then `supabase/templates/confirmation.html` and `recovery.html` were published and the OTP length was changed from 8 to 6 to match the app's six-digit field. Account confirmation had already succeeded by the link; only the redirect page failed. No app change. Remaining: a real outside-address signup and Spam-folder check (a free Gmail sender is less trusted by receivers). The SMTP key lives only in Supabase.

## BG-028 — Barangay filter returned nothing for "alangilan"

Owner: Kotlin data layer. Status: **Fixed** (CP-56). The filter used an exact, case-sensitive match (`barangay=eq.`), so typing `alangilan` found none of the 34 hosted Alangilan reports. It now uses a case-insensitive contains match (`ilike`, with `%`, `_` and `\` escaped), and the local offline rows use the same rule. Test: `GroupmateFeedbackTest`.

## BG-029 — Smaller UI defects from the groupmate round

Owner: Kotlin UI. Status: **Fixed in code** (CP-56), phone confirmation pending. (a) Logout confirmed only when unsent reports existed; it now always asks. (b) The report-form photo preview ignored the camera's EXIF rotation, so a portrait shot looked landscape (the uploaded photo was already correct); the preview now applies the rotation, and tapping a photo opens a full-screen pinch-zoom viewer on the form and on Report Details. (c) Touching the map in the report form scrolled the page instead of moving the map; the map now asks its parent not to intercept while a finger is down. (d) A raw `UnknownHostException ... supabase.co` banner appeared when offline; feed errors now pass through `UiText.humanize`. (e) A full `PIN ROAD;Alangilan;text` typed into the SMS details box was prefixed twice; the prefix is stripped. (f) Profile could edit only the name; contact number and location are now editable (see CP-56, migration 202611270012; adds nullable `profiles.contact_number` and `profiles.home_location` and the RPC `save_profile_details`, private to the owner).
