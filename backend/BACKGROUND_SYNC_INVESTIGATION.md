# CP-25 background sync investigation

November 22, 2026. Owner: Codex. Status: ordinary-app background delay reproduced; scheduling candidate prepared for validation. CP-25 remains open.

## What the existing evidence establishes

Run 8 returned `No validated reconnect was observed.` after 500.616 seconds of instrumentation runtime despite a five-minute device-side loop. Independent system diagnostics showed validated cellular connectivity, Wi-Fi off, background data restriction disabled and no AOSP frozen-process flag. Bringing PinPoint forward released the observer; reopening the ordinary app after runner teardown recovered the same report/photo. The timing stays invalid. An earlier run-7 verification also stalled before its retry succeeded.

Read-only queries of the existing run-8 data and gateway logs produced this timeline:

| Event | November 21, UTC |
| --- | --- |
| Host independently observed validated cellular | 15:46:21.057 |
| Host recorded bringing PinPoint forward | 15:50:15.578 |
| Gateway logged successful photo POST | 15:50:26.805 |
| Storage object created | 15:50:27.019861 |
| Report row created | 15:50:27.172438 |

Storage creation to row creation took about 0.153 seconds. In the queried 15:41–15:51 UTC window, the exact run-8 photo path had one logged request, a successful POST. A second filtered query ending at 15:50:15 found no matching photo or submit_report POST for that citizen. These queries do not exclude device-side attempts, DNS/transport failures or incomplete gateway logging. They narrow the successful server path, but do not establish the cause of the preceding stall.

No useful blocked-thread stack or paired device uptime/elapsed trace was captured. Android documents that elapsedRealtime includes deep sleep while uptime-based waits exclude it; this is a possible measurement issue to investigate, not a proven cause. [SystemClock](https://developer.android.com/reference/android/os/SystemClock). WorkManager execution depends on constraints and system scheduling; CONNECTED does not guarantee an immediate run. [WorkManager scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Hypotheses and distinguishing evidence

1. Instrumentation affects the observation/execution path. Repeat with the ordinary app and a host observer. Success without instrumentation would weaken the ordinary-app failure hypothesis; one successful run cannot rule out an intermittent issue.
2. A queued worker is delayed in background. If system connectivity remains validated but the report is absent, capture pending job/process state before reopening. Do not force the worker or change battery exemptions during the observation.
3. Device-side requests cannot reach Supabase despite network validation. Compare targeted app errors and the new UUID's gateway log window. Network validation alone does not prove app-to-Supabase reachability.

The initial read-only batch made no app change. The subsequently approved ordinary-app trials below reproduced a background delay without instrumentation. This rules out instrumentation as a necessary condition for this new failure; it does not establish the exact cause of historical run 8.

## Approved ordinary-app trials, November 22

User approved the TECNO and exactly two hosted report/photo fixtures. Both used the existing APK (SHA-256 5de576c1fac1cc4178ced09833e3d2745484b495fef9e54937c907e6acf3fb26), ordinary form and native gallery picker, one synthetic 512×512 JPEG, Alangilan, no GPS and no original. The user controlled all radios. No instrumentation, force-stop, Retry uploads or settings exemption was used on the phone.

| Trial | Observed result |
| --- | --- |
| CP25-FG-20261122-A01 | Cellular; one report observed about 8.639 s after first host-observed availability, or 7.431 s after validation. PinPoint was top-resumed before reconnection; Quick Settings remained open at the post-check, so continuous visual foregrounding is not established. |
| CP25-BG-20261122-A01 | Pressed Home while offline, then reconnected. No server row by the end of the five-minute observation, including 256.324 s after first observed validation. All 60 visibility samples were readable, awake and showed PinPoint neither top-resumed nor focused (largest sampling gap 5.270 s). No host read errors or wrong transport. Automatic-latency target failed in this observation. |

At 01:01:24 Asia/Manila, a stable private WorkManager database copy showed the background ReportSyncWorker ENQUEUED, run_attempt_count=0 and schedule_requested_at populated, with a persisted system-job ID. This confirms it had not started at that snapshot. The persisted marker does not establish successful OS registration. The job-dump extraction did not reliably establish current registration, so absence of a platform job or a specific OEM restriction is not claimed.

The observation ended at about 01:04. Work resumed after a gap at about noon; the same background report was already Synced. Server creation was 12:02:57 Asia/Manila, corroborated by the worker SUCCESS log. The user reports only reconnecting USB, without reopening PinPoint. Device/network/app behavior during the unobserved gap is unknown; neither an 11-hour reconnect latency nor USB as the proven recovery trigger is claimed. A post-gap process check was cached, not frozen, and not force-stopped; that cannot establish its earlier process state.

Both fixtures now exist once. Both authenticated downloads decode as 512×512 JPEGs, have 165,343 bytes and match the private queued compressed-photo SHA-256. Queue state is Synced and the local compressed copies were removed after acknowledgement. The original ten recovery samples remain a separate cohort. These two diagnostics do not constitute two new automatic-timing passes, a force-stop test, or full acceptance. Fixtures remain hosted; deletion requires approval.

## Scheduling candidate and limits

The tested baseline used ordinary deferrable report work. First candidate 8691cbd requested expedited work when the active owner/project had Queued or Sending reports, retaining CONNECTED, stable UUIDs, account checks, APPEND_OR_REPLACE and retries, with normal-work fallback if quota was exhausted. It left empty restoration drains ordinary; the follow-up below removes those empty report requests. Optional originals remain unmetered. Older Android versions receive WorkManager's required foreground notification without report/account details.

This prioritization suits a short user-initiated report/photo send, but is a candidate mitigation rather than a proven explanation or phone fix. Expedited work can still be deferred by system load/quota. [Android expedited-work guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work). The exact OS/scheduler reason for the observed non-start remains unresolved. A fresh install/retest requires new phone/data approval after local checks pass; the two-fixture approval above has been used.

First candidate 8691cbd passed debug/test APK builds, 10 JVM tests, lint (0 errors, 35 warnings) and three local ReportSchedulingTest checks (final 1.196 s). The two additional warnings concern matched test-library versions; production dependencies were not changed. Baseline comparison failed priority and missing-foreground-info checks. Execution used WorkManager's test driver and a probe worker, not Supabase or the TECNO scheduler. The old-Android foreground-service lifecycle remains untested. First-candidate APK SHA-256: 91a007c06cb1edb326ff5a2049382d2265327aa392ad4657feda4e9416714451. The user subsequently approved its installation and two rechecks.

### First candidate recheck and ordering correction

The first approved recheck, CP25-EJBG1-20261122-A01, saved one new synthetic report/photo through the ordinary form. The installed APK hash matched 8691cbd and existing queue/session data remained available. No GPS or original. Before pressing Home, a private worker snapshot showed its expedited request BLOCKED behind an ordinary ENQUEUED request created at account restoration. That predecessor had no reports to drain when created. It makes the next urgent request depend on ordinary scheduling.

The five-minute phone observation ended with no server row. Network samples included seven validated Wi-Fi samples at 22.595–29.542 s, then 228 cellular samples with no validation. The unexpected-transport flag invalidates timing; this is not a clean cellular latency test. The report/photo stayed queued and intact. Afterward the user enabled Wi-Fi for recovery; a validated-Wi-Fi read still found the ordinary predecessor enqueued and the expedited child blocked. Bringing PinPoint forward at about 12:42:17 Asia/Manila recovered the same UUID; server creation was 12:42:20. The authenticated 165,343-byte JPEG decoded and matched its saved SHA-256. No Retry uploads or new report was used for recovery.

The original three local tests missed the restoration-then-offline-submit sequence. A new test reproduced it against 8691cbd with `Restoration must not add an empty predecessor expected:<1> but was:<2>`. Three of five expanded checks failed before correction. The correction creates no report work when the active account/project has no Queued/Sending rows; waiting originals are scheduled directly under their existing unmetered rule. Genuine pending report work retains expedited priority, fallback, network constraint and APPEND_OR_REPLACE.

The corrected candidate passes **5/5 local scheduler checks (0.407 s), 10 JVM tests, debug/test APK builds and lint (0 errors, 35 warnings)**. The added checks exercise the actual restoration/submit sequence and preserve original-only scheduling. APK SHA-256: 6970b799c3f07a9953ae8e38c3ba0fb5f973bd89e323679c0651f15e7ebe91f0. It is not installed on the phone yet. One of the two approved fixtures/rechecks remains unused. A proposed remaining recheck uses stable Wi-Fi to isolate background scheduling; installing the changed APK and revising that connection plan need approval. No additional fixture beyond the remaining approved one is proposed.

Local setup exposed an unrelated emulator issue: avdmanager wrote target=android-0 for the newly created minor-version image, followed by SurfaceFlinger crashes with Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma. Only the isolated AVD target was corrected to android-36.1 and the supported swiftshader renderer selected. Its crash buffer stayed clear during successful checks. The user's Pixel_8 AVD and global SDK tools were not changed.

## Prepared external observer

backend/scripts/observe-background-sync.mjs uses Node built-ins, explicit-device ADB connectivity reads and ordinary citizen-authenticated Supabase reads. It sends no instrumentation, app launch, force-stop, report submission, upload, radio, permission or job-run command. Its only POST is demo sign-in, which can update Auth session/audit bookkeeping. A unique label must match zero existing reports before arming.

It archives selected connectivity facts and report IDs/timestamps/photo metadata, excluding raw dumps, network names/addresses, serials, credentials and tokens. Credentials/output must be outside the repository. Existing archives are never overwritten. Physical targets require an explicit approval switch. Server keys, account mismatch, unsafe labels and unrelated photo paths are rejected.

Timing uses host monotonic poll-completion times. Availability, validation and first observed server row are separate. Unknown network formats, read failures, wrong transport and long sampling gaps invalidate positive timing claims. A report observed before validation is retained with a null validation interval. Polling uncertainty includes sampling and request intervals: these are not exact reconnect latency, app acknowledgement latency or a 60-second acceptance pass.

The report query returns at most two rows, enough to flag duplicates but not count arbitrary duplicates. A photo check downloads the private JPEG, checks size/envelope and records SHA-256; it does not decode or compare a local source hash. It cannot replace the earlier persistence/hash tests.

Local verification: **11 focused diagnostic tests pass; full backend suite 28/28**. Coverage includes active-network/history parsing, unknown formats, a simulated run-8 observation gap, missing validation, timing gaps, read failures, wrong transport, duplicate rows and citizen/photo guards. A three-second system-only emulator smoke check identified validated Wi-Fi with timing_valid=false and no cloud authentication. A synthetic physical target without approval was rejected before ADB access. Repository-local output and an existing private archive were also refused. These verify the tool, not the phone defect. No Android rebuild is needed.

## Original two-trial protocol — completed above

Two ordinary-app trials on the TECNO when free: a foreground control, then a background comparison. Use the existing build/demo citizen, synthetic photos, no personal GPS or originals, and two labeled hosted report/photo fixtures. Include host observation plus targeted read-only job/process/server checks. No installation, billing, deployment or persistent setting change. User controls radios. Keep fixtures until separately approved cleanup.

1. Record device/build/label. In the ordinary form offline, save one report/photo and confirm its saved state. Do not use instrumentation or Retry uploads.
2. Start the observer offline and wait for ARMED; the PC keeps Internet independently. Keep PinPoint visible for the control. For the comparison, press Home without force-stopping and keep the screen awake as in the control. Vary only app visibility.
3. Reconnect through cellular with Wi-Fi off and retain the selected visibility condition for up to five minutes. Preserve timeouts/mismatches; do not reopen or retry while measuring.
4. If absent, capture targeted job/process evidence before reopening. Record recovery as a separate intervention and verify the same UUID. Eventual recovery is not an automatic timing pass.
5. Compare gateway/device evidence before selecting a production fix or regression seam. Force-stop/restart and screen-off behavior are separate later variables.

After approval and offline preparation, from the repository root:

~~~powershell
node .\backend\scripts\observe-background-sync.mjs `
  --serial YOUR_APPROVED_SERIAL --allow-physical-device `
  --credentials 'C:\private\pinpoint-demo-accounts.json' `
  --label CP25-BG-NEW-UNIQUE-LABEL --transport Cellular --seconds 300 `
  --output 'C:\private\cp25-bg-new.jsonl'
~~~

Use a distinct label/output for each trial; the description begins with that exact label. Node 24 and the existing Android SDK suffice. For an emulator connectivity-only smoke check, use --serial emulator-5554 --system-only --seconds 3 and an unused private output, omitting credentials/label. Polling/ADB requests have five-second timeouts; final photo verification can add a further request after the observation limit. Ctrl+C ends observation after an in-flight read.
