# Batch 3 (first pass): physical-device regression and release-build checks (CP-46)

November 23, 2026. The phone (TECNO CL8, Android 15) was released by its owner, charged to 98% and kept awake over USB. This pass covers what can be proven **without changing the phone's signed-in account**: isolated test suites and the real-SIGKILL lifecycle matrix on the physical device, plus release-build static checks. **The acceptance cohorts (3B) were not run**, and the whole app is not declared complete.

## Phone safety measures

The phone's own session was never touched: the secure-session file hash was identical before and after every run. All suites use isolated test session stores and a loopback fake backend on the PC (`adb reverse`); the persisted test-backend override was cleared after every scenario (verified empty), queued photos and MediaStore test images were removed, and only test receipts were deleted from the app's private area. Signing in as the fixture citizen on this phone would have required signing the owner's demo session out (removing its push registration), so it was not done.

## Isolated suites on the physical phone (current main build)

OriginalRecoveryTest 13, AuthQueueFailureTest 12, ReportSchedulingTest 5, PhotoCompressorTest 11 (three new format cases: PNG, WebP, 12 MP and 108 MP JPEG), FeedRecoveryTest 5, PushPayloadSimulationTest 3, MapRecoveryTest 3, MapIntegrationTest 4, NativePickerTest 3 (the real system picker): **all pass**. These are functional results; no timing claim.

## Real-SIGKILL lifecycle matrix on the physical phone (`process_death_harness.py --allow-physical-device`)

Same cases as CP-40, run on the device's own OS and process freezer. The harness now accepts a physical device only with an explicit opt-in flag and a plain adb serial (guard tested), and scales its gestures to the screen.

| Case | Result on the phone |
| --- | --- |
| S1 save acknowledged, SIGKILL before any network | durable row and photo bytes; restart syncs exactly 1 report/object, hashes match, local copy removed |
| S2 photo uploaded, RPC held, SIGKILL | restart: 1 report, 1 object, existing object byte-compared |
| S2b different-body object at the path | rejected, `Failed`, 0 reports, object untouched |
| S3 committed, acknowledgement lost, SIGKILL | restart: Synced, 2 attempts, 1 new commit |
| S4 account A queued, B signed in, restarts | B committed and uploaded nothing; A's later sign-in delivered once as A |
| S5 real picker selection, SIGKILL, **task retained** | same task id, new PID, selection restored, Submit uploaded and committed; the uploaded object decodes to 1200x800, proving the synthetic gallery image (inserted by the test app with MediaStore) was the one chosen |
| S6 original copy and saved upload session | preserved across SIGKILL |
| S7 SIGKILL after Synced, before the local copy delete | copy removed on restart (BG-015 fix holds on the device) |

Observations are functional, not latency acceptance, and the backend is a PC-side fake, so this does not test Supabase, cellular or the real Wi-Fi-bound HTTPS original transport.

**Open observation (not attributed):** in the first S5 attempts the harness's `adb push` image was not indexed by Android 15's media scanner, so the picker's first item was one of the owner's real photos. The app showed `IllegalStateException: Cannot decode the selected image.` and nothing was queued or uploaded. Synthetic PNG, WebP and very large JPEG inputs all compress fine on the same phone, so the failing photo's format is unknown (the owner's photo was deliberately not inspected). If the owner sees this error on a normal camera photo, report the photo type (for example HEIC, Ultra HDR, motion photo, screenshot) so it can be reproduced with a synthetic copy. The raw `ExceptionClass: message` error text is also a polish item.

## Release-build checks (unsigned release APK, static)

`app-release-unsigned.apk` SHA-256 `2ca0d132bfd586188012196da1e2f7965b9af9d2d3a4b10602934cc05b0df4a8` (version 1.0, code 1; the debug APK of the same source is `f837e6b6...` and the test APK `eb8215bc...`; the hashes change with every rebuild). `BuildConfig.DEBUG=false`, the Supabase URL is `https`; not debuggable, `allowBackup=false`, no cleartext override (targetSdk 36 blocks it); permissions are limited to internet/network/wifi state, fine and coarse location, notifications, foreground service, boot-completed and wake lock (no media, camera or SMS permission). The dex contains **no** secret key, service-role key, legacy JWT, fixture identity, test password, `example.com` address or loopback URL (positive controls found the project host and the storage host pin). The test-seam method names (`installForTesting`, `installPersistedForTesting`, `overrideForTesting`) are present but every one starts with `check(BuildConfig.DEBUG)` or returns null when not DEBUG, which is false here. **Not done:** a signed release installed and exercised (no release keystore exists in the repo or workspace, and none was created), runtime proof of the gates on a release install.

## Gates on the final source

Backend 76/76 (including the new physical-serial guard case), Edge type check, JVM 11/11, lint 0 errors / 37 warnings. Mocked-driver failing controls from earlier checkpoints are unchanged.

## Not run in this pass (blocked or deferred, with reasons)

- **3B acceptance cohorts** (offline/background 60 s, average submission time, payload size on real requests, switching, push timing, GPS, SMS, SUS): not run. They need the fixture citizen signed in on the phone (an account swap the owner has not approved), radio toggling, field walking, a spare SMS gateway, and real participants. Plan: use an owned emulator or a second installed profile for fixture-account hosted cohorts, and keep the owner's phone session for read-only checks.
- Real Wi-Fi-bound HTTPS original upload with interruption and byte counters (needs the fixture session; see the previous handoff's next task).
- Public email and real SMS (owner inputs), restore project (needs the $0 quote confirmation), UI parity implementation (needs the owner's decisions).

## Phone state at the end

Signed-in demo session and registrations unchanged; app updated in place to the current main debug build; temporary setting `stay_on_while_plugged_in` left at 2 (USB) as requested while the owner is away, original value 0 saved; battery 97% on USB.
