# Auth, session and offline-queue failure validation, November 23, 2026

Checkpoint CP-33. Everything here ran on the owned isolated emulator (`PinPoint_Backend_API36`, API 36) against a **local loopback fake server** through a debug-only backend seam and an isolated session store. No hosted request was made, and no physical phone was used.

## Result

`AuthQueueFailureTest` passed **12/12** (about 2.3 s per run), and 30 consecutive runs passed after fixing a race in my own notification test (see Flakiness). It exercises the real `SupabaseBackend`, `SupabaseAuth`, `ReportQueue`, `ReportSyncWorker` (through `TestListenableWorkerBuilder`) and `PinPointMessagingService`. Scheduling is replaced by a probe worker so only the explicit worker calls run.

| Failure case | Outcome verified |
| --- | --- |
| Refresh token revoked (HTTP 400) | Session ends, the report stays `Queued` (not `Failed`), nothing is sent; sign-in again delivers it once with the same report id; a second run creates no duplicate |
| Refresh answered 503, or the connection dropped | Session is kept, worker asks WorkManager to retry, report stays `Queued`, then delivers once |
| Photo uploaded, server commits, response lost | Retry verifies the duplicate photo and the idempotent RPC: one photo object, one report, local row `Synced`, saved photo file removed only after acknowledgement |
| Photo uploaded, connection dropped before commit | Photo reused on retry: one photo, one report |
| Process died while `Sending` | The durable row is picked up and delivered once |
| 200 response that is a web page (captive portal) | **Retried**, not marked failed (defect BG-006, fixed) |
| Server 500 vs. validation 400 | 500 retries; 400 marks `Failed` until the user taps retry, then delivers (2 reports total as expected) |
| Server rejects a token the client thinks is valid (401) | **One** forced refresh and one repeat of the request, delivered once (defect BG-007, fixed) |
| Account switch | A's queued work is never sent while B is signed in (zero submit requests); B's queue is separate; switching back delivers A's report with A's token; creations equal exactly {A's, B's} |
| Late worker finishes after the user switched | A's report is created under A, row `Synced`, B's session is untouched and B's queue empty; the in-flight request carried A's token |
| Sign-out | Only `scope=local` is requested (the user's other sessions are not revoked); no token afterwards |
| Notification for another account | A notification for a different recipient is not shown; sign-out clears the account's report notifications |

## Defects found and fixed (runtime Kotlin changes)

- **BG-006: a captive-portal page marked a queued report Failed.** A 200 response with a non-JSON body threw `JsonSyntaxException`, which is not an `IOException`, so the worker classified it as permanent and set `Failed`. `SupabaseBackend.json` now converts it to an `IOException`, so it is retried like a dropped connection. Proof: with the conversion reverted, `captivePortalPageIsRetriedNotMarkedFailed` fails.
- **BG-007: a token the server rejects but the client believes unexpired could not recover.** If the device clock hid an expiry, the 401 became a permanent failure. `SupabaseBackend.request` now refreshes once (via `SupabaseAuth.accessToken(rejectedToken=...)`) and repeats the request; a 401 is rejected before execution, so repeating a POST is safe. Proof: reverting it fails `tokenTheServerRejectsIsRefreshedOnceAndTheRequestRepeated`.
- **BG-008: notifications were not bound to the account that received them.** A tapped notification opened its report in whichever account was signed in, and a signed-out account's notifications stayed in the tray. The notification intent now carries the recipient id; `AppNavigation` opens it only for that account (`notificationTargetAllowed`, JVM-tested); sign-out clears the report notifications.

Mutation evidence: reverting each behavior one at a time was caught by the intended test (portal page, 401 refresh, `scope=local` to `global`, any-refresh-error-signs-out, IO errors non-retryable). Removing only two of the three account-binding layers (worker check, repository check, token-accessor check) was not caught because the third still held; removing all three failed `accountSwitchNeverUploadsAnotherAccountsQueuedWork...`.

## Flakiness found in my own test

`notificationsAreRecipientBoundAndSignOutClearsThem` failed about one run in four: `NotificationManager.notify` and `cancel` complete asynchronously, so a single read of `activeNotifications` could be stale. The test now polls for the expected state (10 s limit). After the fix: 30 consecutive passes, and three more on the final build.

## Seams and files

Production: `SupabaseBackend.installForTesting` / `resetForTesting` (debug builds only, loopback URL only, isolated session preferences; `create()` returns the test instance when installed), `SupabaseAuth.accessToken(rejectedToken)`, `NotificationRouting.kt`, small edits in `PinPointMessagingService`, `MainActivity`, `AppNavigation` and `PinPointViewModel.signOut`. Tests: `FakeSupabaseServer.kt` (loopback socket server, no new dependency), `AuthQueueFailureTest.kt`, `NotificationRoutingTest.kt` (JVM).

Regression: JVM tests 11/11; lint 0 errors, 34 warnings (unchanged); `ReportSchedulingTest` 5/5, `EmailConfirmationTest` 6/6, `NotificationAndMapUiTest` 2/2, `MapIntegrationTest` 4/4.

## Limits

This is emulator fault injection. It does not show OEM battery behavior (the TECNO policy mitigation), cellular handoff, real Auth token formats or refresh-token reuse detection by the hosted Auth service (the fake models a revoked token, not reuse), or real FCM delivery. Notification checks use a direct call to the service, not an FCM message from Google. The old-Android foreground-service lifecycle remains untested.

## Reproduce

~~~powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e class com.example.pinpoint.AuthQueueFailureTest com.example.pinpoint.test/androidx.test.runner.AndroidJUnitRunner
~~~
