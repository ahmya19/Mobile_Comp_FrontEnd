# CP-25 background sync investigation

November 22, 2026. Owner: Codex. Status: diagnostic tools ready; ordinary-app phone reproduction pending. CP-25 remains open.

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

No production queue, retry, UI or phone-setting change was made. The phone failure has not been reproduced in this batch. The original environment is needed to distinguish these hypotheses.

## Prepared external observer

backend/scripts/observe-background-sync.mjs uses Node built-ins, explicit-device ADB connectivity reads and ordinary citizen-authenticated Supabase reads. It sends no instrumentation, app launch, force-stop, report submission, upload, radio, permission or job-run command. Its only POST is demo sign-in, which can update Auth session/audit bookkeeping. A unique label must match zero existing reports before arming.

It archives selected connectivity facts and report IDs/timestamps/photo metadata, excluding raw dumps, network names/addresses, serials, credentials and tokens. Credentials/output must be outside the repository. Existing archives are never overwritten. Physical targets require an explicit approval switch. Server keys, account mismatch, unsafe labels and unrelated photo paths are rejected.

Timing uses host monotonic poll-completion times. Availability, validation and first observed server row are separate. Unknown network formats, read failures, wrong transport and long sampling gaps invalidate positive timing claims. A report observed before validation is retained with a null validation interval. Polling uncertainty includes sampling and request intervals: these are not exact reconnect latency, app acknowledgement latency or a 60-second acceptance pass.

The report query returns at most two rows, enough to flag duplicates but not count arbitrary duplicates. A photo check downloads the private JPEG, checks size/envelope and records SHA-256; it does not decode or compare a local source hash. It cannot replace the earlier persistence/hash tests.

Local verification: **11 focused diagnostic tests pass; full backend suite 28/28**. Coverage includes active-network/history parsing, unknown formats, a simulated run-8 observation gap, missing validation, timing gaps, read failures, wrong transport, duplicate rows and citizen/photo guards. A three-second system-only emulator smoke check identified validated Wi-Fi with timing_valid=false and no cloud authentication. A synthetic physical target without approval was rejected before ADB access. Repository-local output and an existing private archive were also refused. These verify the tool, not the phone defect. No Android rebuild is needed.

## Next experiment — requires new approval

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
