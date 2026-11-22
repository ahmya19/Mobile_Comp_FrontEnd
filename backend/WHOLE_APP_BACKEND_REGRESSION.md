# Whole backend-connected app regression on the isolated emulator, November 23, 2026

Checkpoint CP-34. This is the backend side of whole-app regression. It does **not** claim UI/UX parity (CP-28) or overall group acceptance (CP-29). Emulator only; the physical phone was not queried or used.

## Environment

- Emulator: owned AVD `PinPoint_Backend_API36`, Android API 36 (explicit serial `emulator-5556`, started headless by this session and still running when this was written). Normal host internet; Google Play Services present.
- Build: branch `backend/whole-app-regression` (application code identical to `cd7d619`; later commits add tests, scripts and documentation only, and the app APK hash below was unchanged in the final rebuild). Debug app APK SHA-256 `7c43e7794a2e9a7b094da18a6b3aeafd71eb333459223644b5add9c52f98c149`; test APK SHA-256 `6956221dbf62cfe1eb1b8bdd47bfbe205e8f6681d74bd1f980805d5b01506fad` (includes `LocationPermissionTest`).
- Hosted project: `jaysxjjdvaitgrbqjlhe`, ordinary demo citizen and official sessions with the public key only. Privileged SQL was used for oracles, verification and scoped cleanup, never as proof of client authorization.

## Result matrix

| Class or flow | Result | Time | Scope |
| --- | --- | --- | --- |
| `WorkflowRehearsalTest` via `workflow_hosted_regression.py` (citizen login, form, photo, offline queue, sync; official login, status change, history; citizen notification deep link, read state; sign-out/in between phases) | 3 phases passed | 13.06 s, 18.20 s, 7.47 s | Hosted writes, real UI, generated photo stands in for the system picker result |
| `HostedMapNavigationTest` via `map_nav_hosted.py` (citizen map: first page 6, load more 15, 11-report stack chooser, Garbage filter, marker taps open the right details) | 1/1 | 10.52 s | Hosted reads plus two labelled fixtures |
| `HostedOfficialMapTest` via `run-hosted-official-map.ps1` (official map, assigned vs other barangay controls) | 1/1 | 10.27 s | Hosted reads only |
| `AuthQueueFailureTest` (12 failure cases, see AUTH_QUEUE_FAILURE_VALIDATION.md) | 12/12 | 2.3 s | Local fake server |
| `LocationPermissionTest` decline: real system dialog "Don't allow", message shown, map-pin fallback still opens | 1/1 (repeated after a permission reset) | 4.8 s | Local; permission set by the host |
| `LocationPermissionTest` grant: real dialog accepted, simulated emulator fix, latitude and longitude fields filled | 1/1 | 9.3 s | Local; GPS is a **simulated** `adb emu geo fix` |
| `EmailConfirmationTest` | 6/6 | 11.9 s | Local plus one live invalid-code Auth request; no email sent |
| `ReportSchedulingTest` | 5/5 | 0.3 s | Local |
| `NotificationAndMapUiTest` | 2/2 | 9.6 s | Local fixtures |
| `MapIntegrationTest` | 4/4 | 16.3 s | Local, real MapLibre |
| JVM unit tests | 11/11 | n/a | Local |
| Lint | 0 errors, 34 warnings | n/a | Local |
| Backend suite | 54/54 | about 6 s | Local PGlite and Node/Python |

Not run, with reasons: `HostedReportFeedTest` and `HostedNotificationPagingTest` need large seeded fixture batches (97 and 134 rows) whose earlier validation is unaffected except for the shared request path, which the hosted tests above exercise (paged reads, load more, filters, private photo read, notification read); `BackendCheckpointTest` is a multi-phase harness with offline and hosted writes covered by the workflow and failure tests; `PhoneOfflineTrialTest` is phone-only.

## Independent backend assertions

Before and after the workflow run, privileged SQL recorded every report, notification, history, outbox, vote, device and storage-object key. After the run it found exactly one new report, one history row, one notification, one outbox row, one photo object and one device registration. Checks that passed: owner is the demo citizen; description labelled `CP16`; category Water Interruption; status Under Review; photo path equals `<citizen id>/<report id>/photo.jpg`; no original; source `app`; one history row Pending to Under Review; one notification, read after opening. The stored photo was downloaded through the ordinary citizen session: valid JPEG, 785 bytes, 64 by 64 pixels, SHA-256 `b043ee0d2b25639f...` (kept in the private evidence). Official map and map-navigation runs compared rendered marker IDs with SQL oracles as recorded in their own documents.

## Cleanup

The workflow's new rows were removed by exact ID (notification, history, report) and its photo through the ordinary API (HTTP 200). The emulator's push registration, created at sign-in, was verified to be this emulator's installation and the demo citizen's, and removed with the ordinary `remove_device` RPC (HTTP 204). The map driver's two labelled fixtures were removed by its guarded transaction. Final hosted state equals the baseline: 36 reports, 15 notifications, 15 history rows, 15 outbox rows, 0 votes, 2 devices, 32 photo objects, 13 original objects. No session of this run remains (local-scope sign-out only).

## Defects found in the test tooling (fixed)

- **BG-009: the official-map runner silently skipped its setup on Windows PowerShell 5.1.** `Invoke-PinPointProcess` used `ProcessStartInfo.ArgumentList`, which exists only in PowerShell 7, and the `run-as sh -c "..."` step lost its inner double quotes. The oracle was not copied and the old result file not removed, yet the runner still passed using leftover files from an earlier run. `AndroidShell.ps1` now quotes arguments for both PowerShell versions, and the runner verifies that the oracle on the emulator equals the supplied oracle and that no stale result file remains.
- **BG-010: the map driver's guarded cleanup was silently ineffective on Windows.** `npx.cmd` (cmd.exe) truncates an argument at its first newline, so the multi-line cleanup transaction ran only `begin;`. The post-check reported `CLEANUP-MISMATCH` (the fixtures stayed on the hosted project until a single-line rerun removed them). `sql()` now sends one line, refuses comments, and two Python tests pin it (the transport test fails without the fix). The node PGlite tests now run the exact single-line form.

## Remaining emulator or phone work

Native photo gallery with real permission flows (only the picker result is stubbed), map tile errors and retry, notification delivery through real FCM to an emulator, and everything that needs a phone (outdoor GPS accuracy, cellular handoff, OEM battery behavior, overnight, two-device push timing). These were not performed.

## Reproduce

~~~powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
python backend\scripts\workflow_hosted_regression.py --credentials <private accounts.json> --work <fresh private dir outside the repo>
python backend\scripts\map_nav_hosted.py full --credentials <private accounts.json> --work <fresh private dir> --serial emulator-5556
# Location permission: reset the permission from the host, then run one scenario at a time
adb -s emulator-5556 shell pm revoke com.example.pinpoint android.permission.ACCESS_FINE_LOCATION
adb -s emulator-5556 shell pm clear-permission-flags com.example.pinpoint android.permission.ACCESS_FINE_LOCATION user-set user-fixed
adb -s emulator-5556 shell am instrument -w -r -e class com.example.pinpoint.LocationPermissionTest -e locationScenario deny com.example.pinpoint.test/androidx.test.runner.AndroidJUnitRunner
~~~

Repeat the revoke and clear-flags pair for ACCESS_COARSE_LOCATION, and for the grant scenario set a simulated fix first with `adb -s emulator-5556 emu geo fix 121.0583 13.7565`.
