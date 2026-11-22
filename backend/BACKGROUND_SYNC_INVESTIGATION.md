# CP-25 background sync investigation

Latest outcome: a separate TECNO Battery Lab policy explains the reproducible delay. With only PinPoint set to No Restrictions, the established WorkManager runtime passes awake and screen-off background Wi-Fi checks (~1.3 s after observed availability); reversing the policy reproduces freezing. Wider acceptance remains open. See the [unattended comparison](#unattended-oem-policy-comparison---november-22) and [setup guide](ANDROID_BACKGROUND_SETUP.md).

November 22, 2026. Owner: Codex. Status: empty-job ordering corrected; physical background delivery still blocked, with kernel UID freezing observed. CP-25 remains open.

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

The corrected candidate passes **5/5 local scheduler checks (0.407 s), 10 JVM tests, debug/test APK builds and lint (0 errors, 35 warnings)**. The added checks exercise the actual restoration/submit sequence and preserve original-only scheduling. APK SHA-256: 6970b799c3f07a9953ae8e38c3ba0fb5f973bd89e323679c0651f15e7ebe91f0. Before the subsequent approval, it was not installed and one approved fixture remained unused. The owner then approved the corrected installation and that remaining trial over stable Wi-Fi; results follow below.

Local setup exposed an unrelated emulator issue: avdmanager wrote target=android-0 for the newly created minor-version image, followed by SurfaceFlinger crashes with Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma. Only the isolated AVD target was corrected to android-36.1 and the supported swiftshader renderer selected. Its crash buffer stayed clear during successful checks. The user's Pixel_8 AVD and global SDK tools were not changed.

## Physical follow-up

### Corrected phone recheck and kernel freezer finding

After explicit approval, corrected build 2c94c80 was installed and its hash verified. Offline account restoration created no pending report work; prior queue/session data remained available. The remaining approved fixture CP25-EJBG2-20261122-A01 was saved through the ordinary form and native picker, with no GPS/original. Its actual expedited request was ENQUEUED with zero attempts and only a completed prerequisite, rather than BLOCKED behind an empty ordinary job. The platform job was initially present and waiting offline.

With airplane mode retained and Wi-Fi enabled, the five-minute external observation still ended without a server row, including **263.945 seconds after observed validation**. Sixty readable visibility samples showed the phone awake and PinPoint neither resumed nor focused. Only Wi-Fi was observed; no transport deviation or host read error. A worker snapshot remained ENQUEUED/expedited with zero attempts; the system job query returned unknown at that later snapshot. The corrected code does not close the background delivery defect.

Targeted read-only kernel checks found the app UID's cgroup.freeze=1 and cgroup.events frozen=1. The process subgroup requested freeze=0 but inherited frozen=1 from its UID parent. Android's process diagnostic simultaneously said isFrozen=false; the main process was sleeping, untraced and not SIGSTOP-stopped. The kernel's frozen event means its processes cannot run until thawed, including through ancestor freezing. [Linux cgroup v2 freezer documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html). This establishes a process-execution blocker in this recheck; it does not identify which device controller initiated the freeze or retroactively prove historical run 8's cause.

Normal foreground recovery at 12:59:24 Asia/Manila changed both UID/process frozen events to 0 and UID freeze request to 0. The same report was created at 12:59:26, then independently verified once with its matching 165,343-byte decoded 512x512 JPEG. No kernel control was written and no Retry uploads used. Treat this as recovery after intervention, not automatic latency.

Read-only inspection found Allow Background Usage on and **Optimized** selected. The app-specific Unrestricted option states it may use more battery. At that point no policy had been changed and both approved recheck fixtures were used/recovered. The subsequent extra comparison below received separate approval.

## Approved Unrestricted comparison — automatic recovery, timing target unmet

The user approved changing only PinPoint to Unrestricted and creating one additional synthetic report/photo. The setting was applied and verified selected; the device-idle allowlist included PinPoint and its standby bucket was EXEMPTED. The user also explicitly delegated Wi-Fi off/on for this one trial while retaining airplane mode. The first offline acknowledgement still had a live Wi-Fi connection; no report was created until the host verified actual offline state. The new fixture CP25-UNRBG-20261122-A01 used the same code/APK, synthetic photo and no GPS/original. No further kernel/global setting or APK change was made.

This report uploaded automatically without reopening PinPoint. Host observations measured **128.577 s from first observed Wi-Fi availability**, or **127.422 s from validation**, to the first server-row observation. Timing is valid for these polling endpoints, with no read gap/error or transport deviation, but **the under-60-second target failed**. These endpoints differ from the historical app-acknowledgement trials and must not be pooled.

All 35 readable app-state samples showed the phone awake and PinPoint neither top-resumed nor focused. The UID kernel group was frozen in 33 samples; it was thawed in the initial transition and again at 13:19:14 Asia/Manila near upload execution, then frozen in the final sample. Thus Unrestricted did not eliminate freezing. One successful background recovery does not prove the setting alone caused it, establish a usual latency or identify the freezer controller.

Independent client verification found one report and a matching, decoded 512x512 JPEG of 165,343 bytes; the queue reached Synced and removed its local compressed file after acknowledgement. A final SQL read over all **five separately approved diagnostic UUIDs** found five reports, five unique IDs, five attached photos, zero coordinates and zero originals. All five downloaded photos match their saved compressed hashes. They remain hosted; cleanup needs approval. Keep this cohort separate from the ten historical persistence/recovery samples.

At the end of that batch, code fix 2c94c80 was installed; five scheduler tests and ten JVM tests pass, build/lint pass (0 errors, 35 warnings). Empty-predecessor correction is verified locally and at the actual phone restoration/queue boundary. BG-001 and CP-25's background latency acceptance remain open: further investigation must distinguish the UID-freezer controller and system job dispatch, then evaluate a supported user-initiated transfer path if required. Historical run 8 did not capture kernel cgroups, so this new finding is consistent with its stalled callback/timer but does not retrospectively prove its cause.

At that earlier stopping point, PinPoint was left Unrestricted, Wi-Fi on and airplane mode on. Those narrow trial permissions had been consumed. The later unattended authorization and comparison below supersede that stopping point and record the final restored radio state. No provider/billing/schema/Edge Function/secret change was made.

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

## Unattended OEM-policy comparison - November 22

The user explicitly authorized unattended project-specific phone/PC work, candidate installs, radio/per-app setting controls and synthetic hosted fixtures. No additional billing/provider/account setup was included. All new submissions used the ordinary form and native picker with the same synthetic photo, Alangilan, no GPS and no original. No PinPoint test instrumentation or Retry uploads was used during measurements. Radio state was verified by the host rather than inferred from a setting toggle.

### Controller and separate policy

Targeted system logs recorded Hiber/stateManager freezing PinPoint's UID and Hiber/proxyManager reporting a job-proxy event for the same app. The native job was retained while the app was visible/offline, then became unknown to JobScheduler shortly after Home while the UID group was frozen. This is stronger evidence than Android's isFrozen=false process flag alone; the exact proprietary meaning of the proxy event's numeric argument is not inferred.

Phone Master > App Launch already showed Pinpoint Allowed; no switch was changed there. Android App Battery Usage remained Unrestricted. A separate installed Battery Lab screen, **Power Saving Management for Apps > Pinpoint**, showed **Intelligent Management** selected. Its text explicitly describes background restrictions. Only this app's radio was changed to **No Restrictions**, and the selection was verified by reopening the dialog. No global optimization, other app, root/kernel control or security protection was changed.

### Controlled results

Times below are host monotonic poll-completion endpoints. Availability and validation are distinct; the 0.096 s interval is below the polling scale and must not be presented as exact network-to-app-acknowledgement latency. Server-created UTC and PC UTC can differ slightly; no cross-clock subtraction is used.

| Fixture suffix | Runtime | OEM policy / condition | Availability to server observed | Validation to server observed | Result |
| --- | --- | --- | --- | --- | --- |
| UIDT-A01 | Experimental 82b9e4e | Intelligent Management / preparation | Not measured | Not measured | Recovered during an unobserved gap; no timing verdict |
| UIDT-A02 | Experimental 82b9e4e | Intelligent Management / Home, awake | No row in 300 s observation | Absent for 292.384 s after validation | Failed timely recovery; later recovered outside observation |
| UIDT-A03 | Same experimental APK | No Restrictions / Home, awake | 1.264 s | 1.264 s | Automatic recovery; one matching report/photo |
| WM-OEM-A01 | Existing 2c94c80 APK | No Restrictions / Home, awake | 1.310 s | 0.096 s | Automatic recovery; one matching report/photo |
| WM-OEM-N01 | Same existing APK | Intelligent Management / reverse control, Home, awake | No row in 90 s observation | Absent for 82.831 s after validation | Delay/freezing reproduced; restoring No Restrictions recovered same report |
| WM-OEM-A02 | Rebuilt established runtime | No Restrictions / screen off | 1.337 s | 1.337 s | Automatic recovery; one matching report/photo |

Full labels use CP25-UIDT-20261122-A01/A02/A03 and CP25-WM-OEM-20261122-A01/N01/A02. The delivered CSV retains all six cases; no mean/pass percentage is pooled with the earlier five diagnostics or ten historical persistence/recovery samples.

The UIDT-A02 observation had 60 readable, awake, non-resumed/non-focused app samples, 59 with kernel freezing. The WM-OEM-N01 reverse control had 19 such samples, 18 frozen. The three positive observations each had three readable, non-resumed/non-focused samples, all kernel-unfrozen; WM-OEM-A02's three samples were all asleep according to PowerManager. The native service was observed active in UIDT-A03; the WorkManager positives used an APK without that service. Sampling does not establish continuous visibility/state between reads.

UIDT-A01's preparation did not establish a retained job/timed reconnect, so its eventual recovery remains untimed. A subsequent harness run caught Select a valid category before a report was created; category/read-back and saved-screen checks were tightened. A Windows Unicode menu-label mismatch was fixed in the private harness, not the app. UIDT-A02's server row was created at 09:40:26 UTC, before the OEM-setting change recorded at 09:40:53 UTC. It therefore cannot be credited to that change. It recovered while settings were being inspected outside the timed window, with no sampled recovery trigger. Keep these limitations visible.

The reverse control changes only the OEM policy on the established WorkManager runtime after its positive check. It reproduces the delay, and restoring No Restrictions recovered the same UUID/photo without reopening PinPoint. Together with the kernel and targeted Hiber signals, this establishes a reproducible device-policy dependency for these cases. It does not retroactively prove historical run 8's exact cause, whose kernel cgroups were not captured.

### Runtime choice, local checks and final device state

The user-initiated JobScheduler experiment scheduled only explicit submits on API 34+, shared the serialized/idempotent drain, showed a generic required notification and retained WorkManager fallback. The Android guidance recommends WorkManager for short interruptible transfers; the native path did not avoid this phone's restriction. Its added permission/service/resources/tests were removed from the delivery branch after comparison. The experiment is retained on local branch experiment/pinpoint-user-report-transfer, with commits 82b9e4e and ffeedc9; removal is 8ca02e2. There is no net app-source change from e9036f4 / the 2c94c80 runtime correction. [Android UIDT guidance](https://developer.android.com/develop/background-work/background-tasks/uidt).

The experimental platform-capability test failed against the previous APK with NameNotFoundException for the absent service; ten combined scheduler/platform tests passed against the experiment on the isolated API 36.1 emulator. Those checks did not certify the TECNO and are not counted as final-runtime tests. Final established runtime checks: **5/5 scheduler tests (0.398 s), 10/10 JVM tests, debug/test APK builds and lint (0 errors, 35 warnings)**. No production dependency, schema, server function, secret or billing change was made. The earlier 28-test backend baseline was not rerun for these Android/device-only comparisons.

The existing APK used for awake/reverse WorkManager checks has SHA-256 6970b799c3f07a9953ae8e38c3ba0fb5f973bd89e323679c0651f15e7ebe91f0. The rebuilt and physically installed/screen-off-tested final APK has SHA-256 **d008315e7a25bf8800a684e8143b5bc82b2eb2c8494127d3d56965761073fdcf**. Its installed hash was independently read back; the rebuilt DEX has no experimental transfer classes. Source is the same established scheduler/queue implementation.

Final read-only SQL over the six exact fixture UUIDs found **6 reports, 6 unique IDs, 6 attached photos, 0 coordinates and 0 originals**. Each ordinary citizen-authenticated download decoded as a 512x512 JPEG, contained 165,343 bytes and matched the private pre-reconnect compressed-photo hash. Each queue reached Synced and removed its local compressed file after acknowledgement. These six remain hosted for review; no unrelated data was modified.

The phone is left with the tested final APK, Android Unrestricted as before, OEM No Restrictions for PinPoint, and App Launch Allowed as before. Airplane mode was restored **off**, Wi-Fi **on**, and the temporary USB stay-awake override restored to its original **0**; the screen was left asleep after the final trial. The isolated test emulator was stopped. Full setup: [ANDROID_BACKGROUND_SETUP.md](ANDROID_BACKGROUND_SETUP.md).

BG-002 stays fixed. BG-001 is mitigated for this configured TECNO/Wi-Fi setup, with a reproducible failing control. CP-25 remains partial for other devices, carrier/overnight/unplugged behavior and proposal statistics, handoffs, outdoor GPS, push timing and SUS. Do not claim a universal under-60-second guarantee or whole-app acceptance.
