# CP-25 phone test sheet

Run on the TECNO phone with the debug build from commit 225bfd5 or later. Record only what you measure. Leave a row blank rather than guess. Use a demo citizen account; never write passwords or tokens in this file.

Before starting, obtain the user's approval for phone tests and any required setting changes. Check battery above 50%, Wi-Fi/mobile data, location and notification permissions. If the phone shows a "Save password?" popup, choose Not now. Do not select Never or change account/autofill settings without permission. The ten offline recovery samples are now recorded with the exceptions below; handoff/GPS/push/SUS and wider background-latency acceptance remain pending; the configured-TECNO mitigation is verified in the final section.

## A. Offline sync (target: 0 lost reports)

Repeat 10 times. Each run: turn on airplane mode, submit a report with a photo, confirm it shows as saved on the device, force-stop PinPoint, reopen it, turn airplane mode off.

| Run | Time | Appeared as Pending after reconnect (Y/N) | Seconds until synced | Photo shown (Y/N) | Notes |
| --- | --- | --- | --- | --- | --- |

Pass condition: all 10 synced once, no duplicate, photo present.

Use the queue acknowledgement and hosted row to identify synchronization. Pending is the report's business status.

## B. Wi-Fi to mobile data switching (target: no lost or duplicated upload)

Repeat 5 times. Start a report upload on Wi-Fi, switch Wi-Fi off during the upload so it continues on mobile data (or waits), then check that exactly one report exists.

| Run | Switched at (s into upload) | Final state | Duplicate? | Original photo waited for Wi-Fi? | Notes |
| --- | --- | --- | --- | --- | --- |

## C. GPS accuracy (target: within 20 m)

Outdoors, clear sky. Stand at a landmark whose true coordinates you can read from a map app (long-press the exact spot). Use "use my location" in the report form 5 times at different places.

| Spot | True lat,lon | App lat,lon | Error in metres | Seconds to fix | Notes |
| --- | --- | --- | --- | --- | --- |

Compute the coordinate distance and record the reference method and its uncertainty. Keep precise real-world coordinates in a private local measurement file, outside Git; publish only rounded locations and aggregate errors. A consumer map pin is an approximate reference, so report that limitation when assessing the 20 m target.

## D. Push timing (two phones)

Needs a second phone or the emulator for the official account. Citizen phone: submit a report. Official: change its status. Note the clock time of each step.

| Run | Status change (hh:mm:ss) | Notification on citizen phone (hh:mm:ss) | Seconds | App state (foreground/background/closed) |
| --- | --- | --- | --- | --- |

## E. SUS questionnaire

Needs real participants. Use the standard 10-item System Usability Scale; target average at least 70. Record participant count and scores separately.

## Environment record

Phone model and Android version, build commit, SIM carrier, date, who ran each section.

## Results log

### A/B. Offline sync on the TECNO phone, November 21 (3 of 10 planned runs; stopped early by the user)

Measured from server timestamps and the phone's network log. Wi-Fi/mobile-data times come from adb connectivity records.

| Run | Reconnected via | Delay to report on server | Copies | Photo |
| --- | --- | --- | --- | --- |
| 1 | Wi-Fi | 2.6 s | 1 | Yes |
| 2 | Wi-Fi | 5.0 s | 1 | Yes |
| 3 | Mobile data only (Wi-Fi off) | 48.5 s | 1 | Yes |

All three synced exactly once with the photo after a forced stop while offline. Run 3 is the first real mobile-data upload; its delay is one sample and may reflect retry timing, so no rate is claimed. The run 3 description differed from the planned label, so it was matched as the only new report. These were the original three samples; the seven additional samples below complete the recovery count, with timing limitations retained.

### A. Seven additional offline trials, November 21; documented November 22

User approved the tested app installation, manual radio changes and seven labeled hosted report/photo fixtures. Device: TECNO CL8, Android 15, app build 5efb41b. Each report used the actual SQLite queue/compressor and a generated 512×512 JPEG (185,272–185,504 bytes after compression), no GPS and no original copy. Each UUID and saved photo hash survived force-stop/restart offline.

| Run | Reconnected via | Network available → local acknowledgement | Validated Internet → local acknowledgement | Copies / matching photo | Condition |
| --- | --- | --- | --- | --- | --- |
| 4 | Wi-Fi | 1.665 s | 1.664 s | 1 / Yes | App visibility not held |
| 5 | Wi-Fi | 1.645 s | 1.645 s | 1 / Yes | App visibility not held |
| 6 | Wi-Fi | 1.038 s | 1.032 s | 1 / Yes | App visibility not held |
| 7 | Wi-Fi, although cellular was planned | 1.668 s | 1.665 s | 1 / Yes | Rejected as a mobile-only trial; transport deviation retained |
| 8 | Cellular seen by system diagnostics | Invalid / unmeasured | Invalid / unmeasured | 1 / Yes, after reopening PinPoint | Background observer stalled; not an automatic timing pass |
| 9 | Cellular | 12.579 s | 4.387 s | 1 / Yes | PinPoint kept visible |
| 10 | Cellular | 12.333 s | 11.171 s | 1 / Yes | PinPoint kept visible |

All seven fixtures were independently rechecked in the hosted database: seven reports, seven attached photos and zero duplicate labels. Each photo was downloaded through authenticated client access, decoded and matched byte-for-byte against its saved SHA-256 hash. Together with the earlier three samples, ten reports have recovery evidence; **this is not ten successful automatic-latency measurements**. Run 8 required reopening and has no valid reconnect interval. No pooled average is reported across the different methods, photo sources and visibility conditions.

Run 7's original error was `Reconnect used a different transport than planned expected:<[Cellular]> but was:<[WiFi]>`; the Wi-Fi setting was on. Read-only re-verification confirmed its single report/photo while retaining the deviation. A first re-verification process also stalled; restarting that test completed the check without recreating the report.

Run 8's observer reported `No validated reconnect was observed.` Its instrumentation runtime was 500.616 s, despite a five-minute device-side wait limit. System diagnostics had shown validated cellular service, Wi-Fi off and background data restriction disabled. Bringing PinPoint forward released the observer; reopening it after the failed test recovered the same report/photo. The cause is unresolved: this could involve the background test/device execution path and is not evidence by itself of a specific carrier, Supabase or OEM fault. AOSP process diagnostics did not report the app as frozen. Do not use the full test runtime as upload latency or claim the under-60-second target passed.

For runs 9–10, the app stayed visible; both cellular recoveries completed. This does not explain the earlier 48.5 s sample or establish background behavior. Next, reproduce background recovery with the ordinary app and an external connection/server observer, then distinguish WorkManager scheduling, device background behavior and instrumentation effects before changing app code or phone settings.

Timing used the same phone's monotonic clock and 200 ms queue polling. Network availability and validated Internet are distinct events; radio/user-toggle time is excluded. [Runner and evidence protocol](PHONE_TRIAL_RUNNER.md). Raw records and the device serial remain outside Git. Seven labeled fixtures remain in the hosted project; cleanup would require approval. No schema, deployment, secret or paid-plan change was made.

### Ordinary-app diagnosis, November 22 — separate two-fixture cohort

The user approved two ordinary form/gallery fixtures on the existing APK, no instrumentation, force-stop, Retry uploads, GPS or originals. The resumed-activity cellular control was observed on the server about 8.639 s after host-observed availability (7.431 s after validation); Quick Settings was still open at the post-check, so continuous visual foregrounding is not established. The second report was saved offline, then PinPoint was sent Home before reconnecting. The report remained absent through the five-minute observer limit, including 256.324 s after observed validation. Sixty sampled states all showed the phone awake and PinPoint neither resumed nor focused. A mid-observation WorkManager snapshot showed the report worker enqueued with zero attempts.

The background report later recovered during an unobserved gap; the user reports only reconnecting USB. Server creation was 12:02:57 Asia/Manila. This does not establish its recovery trigger or an 11-hour reconnect latency. Both fixtures now exist once, with decoded 512x512 JPEGs of 165,343 bytes matching the saved compressed hashes. Keep this cohort separate from the ten earlier force-stop/recovery samples; it does not add two automatic-latency passes.

The expedited scheduling candidate passes three local WorkManager tests, ten JVM tests and build/lint (0 errors, 35 warnings), but is not installed/verified on the phone. CP-25 remains open. Protocol, limitations and candidate identity: [BACKGROUND_SYNC_INVESTIGATION.md](BACKGROUND_SYNC_INVESTIGATION.md). Further phone installation/fixtures require approval.

### First scheduling candidate recheck, November 22

The user approved installation of 8691cbd and two new fixtures; one was used. Recheck 1 briefly used validated Wi-Fi before switching to cellular that never validated, so its five-minute observation has invalid timing and an unexpected-transport flag. The saved report/photo remained intact. Worker snapshots showed its expedited request BLOCKED behind an ordinary empty restoration job. On validated Wi-Fi it recovered after PinPoint was foregrounded, once with its matching decoded photo. No retry or second report was created for recovery.

The correction skips empty report drains and directly schedules waiting originals. An exact restoration/submit regression failed against 8691cbd and all five expanded local scheduler checks pass after correction. The owner subsequently approved the corrected installation and remaining fixture over stable Wi-Fi; results follow below. This cohort is not a cellular latency pass. Full details: BACKGROUND_SYNC_INVESTIGATION.md.


### Corrected Wi-Fi and Unrestricted comparisons — November 22

Corrected 2c94c80 was explicitly approved and installed. Offline restoration created no empty report job; the new expedited request had only a completed predecessor. The remaining approved Wi-Fi fixture stayed absent through five minutes, including 263.945 s after observed validation. Sixty samples all showed awake, non-resumed/non-focused PinPoint. Kernel reads found its UID parent frozen=1 despite the Android process flag isFrozen=false. Normal foregrounding thawed the group and recovered the same report/photo.

The owner separately approved Unrestricted for PinPoint, one extra synthetic fixture and host Wi-Fi switching for that trial while airplane mode remained on. This report recovered automatically with no app foregrounding in the sampled record: 128.577 s from host-observed availability, 127.422 s from validation. All 35 app-state reads were awake and non-resumed/non-focused; 33 kernel samples were frozen, with a thaw observed near execution. The measurement is valid for these endpoints, but the under-60-second target failed. One comparison does not establish the setting as a reliable cure or usual latency.

Final SQL: five diagnostic reports, five unique IDs, five attached photos, zero coordinates/originals. All five authenticated JPEG downloads decode and match their saved 165,343-byte compressed hashes. Keep this cohort separate from the ten historical persistence/recovery samples. Before the later unattended authorization, no additional fixture scope remained. That earlier batch ended with Unrestricted, Wi-Fi on and airplane mode on; the unattended follow-up below supersedes that state. Detailed evidence and remaining BG-001 work: [BACKGROUND_SYNC_INVESTIGATION.md](BACKGROUND_SYNC_INVESTIGATION.md).

## Unattended OEM-policy follow-up - November 22

The user authorized this project-specific unattended phone/PC batch. Separate TECNO Battery Lab Intelligent Management remained selected despite Android Unrestricted and App Launch Allowed. Targeted Hiber signals and a controlled reverse test tie the sampled kernel UID freezing to this per-app policy. Set only PinPoint to No Restrictions using [ANDROID_BACKGROUND_SETUP.md](ANDROID_BACKGROUND_SETUP.md).

The established WorkManager APK recovered automatically in 1.310 s from observed Wi-Fi availability / 0.096 s from validation while awake. Reverting just the OEM policy reproduced no row for 82.831 s after validation; restoring No Restrictions recovered that same report without reopening PinPoint. The final rebuilt APK passed a screen-off check at 1.337 / 1.337 s; all three samples were asleep, app off screen and kernel-unfrozen. These are host polling endpoints, not exact app-acknowledgement latency.

A native user-initiated transfer experiment also stalled under the restriction (292.384 s absent after validation); with the policy changed it passed at 1.264 s. It was removed, retaining the existing WorkManager implementation. Final runtime tests: 5 scheduler checks in 0.398 s, 10 JVM tests, debug/test builds and lint (0 errors, 35 warnings). Tested installed final APK SHA-256: d008315e7a25bf8800a684e8143b5bc82b2eb2c8494127d3d56965761073fdcf.

Six new distinct fixtures recovered once; all citizen-authenticated downloads decoded and matched their pre-reconnect compressed-photo hashes (512x512 JPEG, 165,343 bytes). Final SQL found 6 reports/photos, 0 coordinates/originals. One preparation fixture is untimed, and the first failed native trial recovered before the OEM-policy change; do not credit that recovery to the change. Keep the six cases separate from prior cohorts. Full outcomes and visibility limits: [BACKGROUND_SYNC_INVESTIGATION.md](BACKGROUND_SYNC_INVESTIGATION.md).

Radios and temporary USB stay-awake were restored (airplane off, Wi-Fi on, original stay-awake 0); phone left asleep with the tested APK and PinPoint-only OEM exemption. No global power-saving/freezer policy or security protection was disabled; no unrelated app, billing, schema, deployment or secret was changed. BG-001 has a verified device-specific mitigation; CP-25's wider device/carrier/overnight/unplugged and statistical acceptance remains partial.
