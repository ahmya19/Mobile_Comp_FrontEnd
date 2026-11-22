# Real FCM, native photo picker and read-path recovery (emulator)

Scope: backend continuation, November 23. Emulator `emulator-5556` (AVD `PinPoint_Backend_API36`, API 36, Google Play) only. The physical phone was not queried, installed on, launched or touched. Budget unchanged (Supabase Free + existing Firebase FCM). Private evidence (ledgers, logs, per-case JSON) is outside Git.

Evidence kinds are kept apart: **real FCM** (Google delivers to the emulator), **hosted API** (ordinary session or privileged read-only oracle), **emulator UI**, **local fault injection** (loopback fake server or direct service call) and **physical** (none in this batch).

## 1. Real FCM delivery and recipient isolation (CP-35)

Route exercised, nothing added to production: ordinary official session `submit_report` + `update_report_status` RPCs, then history, notification and `push_outbox` rows, then the pg_cron dispatcher, the `deliver-push` Edge Function (the server FCM credential stays in Edge secrets), Google FCM, `PinPointMessagingService`, the tray, a tap and the report details. Driver: `backend/scripts/real_fcm_probe.py` (exclusive private ledger before any write; guarded cleanup).

**Recipient isolation.** Notifications go to the report owner's devices. Both existing device rows belong to the demo *citizen* (possibly the user's phone), so no citizen report was touched. The demo *official* had zero registrations; the fixture report is owned by the official, the emulator is the official's only registration, and before **every** status change the driver re-verifies with privileged read-only SQL that the official's device rows are exactly this emulator's installation and that the citizen's rows are byte-identical (md5 over full rows). Any drift aborts before sending.

Limitations: the official is a shared demo account, so isolation depends on the official not being signed in on the phone during the run (it had no registrations at the start and at every guard). One account plays reporter and assigned official, which exercises the real RPCs but not a separate reporter/official pair. No dedicated test account could be created with already-authorized tooling, so none was made.

Per-case result, final run on the final APK (private run `push-fcm-20261123-a5`; seconds are observations, not CP-25 latency acceptance):

| Case | FCM accepted (outbox `delivered_at`, attempts 1) | Tray shows exact notification ID | Tap opens correct report | Notes |
| --- | --- | --- | --- | --- |
| 1 foreground, Pending to Under Review | yes (dispatched 5.9 s after creation) | yes (6.6 s) | yes | real FCM |
| 2 activity destroyed (BACK), In Progress | yes (7.9 s) | yes (8.7 s) | yes, still correct after rotation recreation | real FCM |
| 3a notification permission revoked, Resolved | yes (3.2 s) | no (expected) | n/a | the real system dialog was answered "Don't allow"; the in-app notifications list still showed the update |
| 3b permission granted, Under Review | yes (1.4 s) | yes (2.5 s) | yes | the denied-period update is not redelivered |
| 4 force-stopped (`am force-stop`), In Progress | yes (0.3 s) | no | n/a | negative control: package stopped, no delivery promised, no OS restriction altered |
| 5a sign-out | n/a | tray empty | n/a | official device rows 1 to 0, citizen rows unchanged |
| 5b status change while signed out | outbox marked delivered (no device) | no | n/a | no leak to the emulator |
| 6a sign-in again | n/a | n/a | n/a | exactly one row, same installation ID |
| 6b delivery after recovery, Resolved | yes (0.4 s) | yes (2.5 s) | yes | real FCM |

Run history: an earlier run was misleading because of driver bugs (a tray regex that never matched, and a cleanup that ignored that `report_history` does not cascade). Both were fixed, the stranded fixture was removed by exact ID, and the later runs started from the restored baseline. Not observed: on-device FCM receipt time other than tray appearance, and duplicate delivery from Google (cannot be driven safely).

**Simulated cases** (`PushPayloadSimulationTest`, 3 tests, direct service calls, not Google FCM): the same notification delivered twice is one tray entry (the tray tag is the notification ID, so a duplicate replaces rather than stacks; no exactly-once guarantee is claimed or added), distinct notifications stay distinct, a late payload for a signed-out account A is dropped after switching to B (B still receives its own), and a payload with no session is dropped. The earlier BG-008 tests remain.

Cleanup: after the last status change the driver waited for every outbox row of the fixture to be delivered, then deleted exactly the fixture's notifications, history and report (identity-guarded). Counts returned to 36 reports, 15 notifications, 15 outbox, 15 history and 2 devices; the citizen's device fingerprint and the official's zero registrations were verified; the app was signed out locally (`logout?scope=local`).

## 2. Native photo picker to durable queue to private upload (CP-36)

The app uses `ActivityResultContracts.GetContent("image/*")`, which on API 36 opens the real **Android Photo Picker** (`com.google.android.photopicker`, "Pinpoint will only have access to the photos you select"). No broad media permission is requested or added. `NativePickerTest` (3 tests, UiAutomator, real picker, no stubbed result) uses a MediaStore image created by the test:

- select through the system UI, see "Photo selected successfully", `recreate()` the activity (selection kept), submit; the queue holds its own compressed copy (at most 256 000 bytes, landscape, at most 1280 px); the source is deleted; the sync worker commits **one** report and uploads one private object whose SHA-256 equals the queued copy (loopback fake server; the hosted project was not written in this workstream);
- backing out of the picker leaves the form usable and no queue row;
- a source deleted before submit shows an error, creates no queue row and leaves no files.

`PhotoCompressorTest` (8 tests): EXIF orientation (90, 180, transpose), GPS not copied, 8000x6000 bounded, a very detailed image either fits or is rejected with a clear message, corrupt bytes, non-image text, vanished source.

Defects found and fixed with failing controls: **BG-011** (orientation ignored; 2 of 8 compressor tests failed before the fix) and **BG-012** (temp file left by a failed save; the vanished-source test failed on the leftover `<id>.tmp`). Not claimed: camera capture, a physical gallery, the Wi-Fi original-upload path in this workstream (covered earlier), process death with a live picker grant, hosted object verification for a picked photo.

## 3. Map and read-path recovery (CP-37)

`FeedRecoveryTest` (5, local server): a failed refresh (HTTP 500, captive-portal HTML 200, dropped connection) keeps the loaded reports and shows an error; retry returns exactly the oracle IDs with no duplicates; a failed first load shows an empty list plus an error and recovers; a failed load-more keeps its page and cursor and retry adds the older rows once; a slow older response cannot overwrite a newer filter; a cancelled refresh leaves the feed usable.

`MapRecoveryTest` (3, native MapLibre): a feed error keeps the markers; the new **Retry loading reports** control (BG-013) reuses the current filter and recovers to exactly the server's reports with no stale marker, and is disabled while loading. With the basemap source unreachable (debug-only loopback tile override `MapTiles.overrideForTesting`, loopback URLs and debug builds only) the basemap really requested the dead source, markers still render and a marker tap opens the correct report. Public OpenStreetMap, Supabase and Google were not stressed. No offline map is claimed: only an HTTP cache of visited tiles exists. The error text still uses the app-wide `ExceptionClass: message` form (unchanged; polish item).

## Regression and checks

Backend 54/54 (`npm test`), Edge type check, JVM unit tests 11/11, build, lint 0 errors / 37 warnings (was 34). Emulator suites after the changes: `AuthQueueFailureTest` 12/12, `MapIntegrationTest` 4/4, `NotificationAndMapUiTest` 2/2, `ReportSchedulingTest` 5/5, `PhotoCompressorTest` 8/8, `NativePickerTest` 3/3, `FeedRecoveryTest` 5/5, `MapRecoveryTest` 3/3, `PushPayloadSimulationTest` 3/3. `LocationPermissionTest` was skipped by its opt-in guard (a skip is not a pass).

## Still not covered

BG-005 (priority-only notifications) is unchanged and awaits a product decision. Physical push timing and OEM behavior, outdoor GPS, camera, fresh-project restore, carrier SMS and the group decisions remain out of scope.
