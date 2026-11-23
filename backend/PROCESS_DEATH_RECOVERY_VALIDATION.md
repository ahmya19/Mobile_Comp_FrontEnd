# Queue and photo recovery across real OS process death (CP-40)

November 23. One owned isolated emulator (`emulator-5556`, AVD `PinPoint_Backend_API36`, API 36, Google Play). The physical phone was neither queried nor touched, no hosted project/account/push/FCM was used, and the debug-only test backend is a loopback fake. Private evidence: `work\lifecycle-20261123-*` (outside Git).

## Method (what is real and what is simulated)

- **Real:** the app process is killed with `run-as com.example.pinpoint kill -9 <pid>` (SIGKILL, the signal the low-memory killer sends). The harness checks the PID is gone, that the replacement process has a different PID, and, for the picker scenario, that the **same task id** survives. WorkManager recovery in the replacement process is real, and durable state is read independently from the app's SQLite files (copied with run-as and opened on the host) and queued photo bytes (SHA-256 on the host), not from callbacks.
- **Not used as substitutes:** `ActivityScenario.recreate()` and `am force-stop` (force-stop removes the task). `am kill` was rejected because it refuses processes that are not cached.
- **Simulated:** the backend. `backend/scripts/process_death_harness.py` runs a fake Supabase endpoint on the PC, reached over `adb reverse`, so it outlives the app; faults are placed at exact request boundaries (offline, RPC held, commit-then-hold). The app reaches it through `SupabaseBackend.installPersistedForTesting` (debug builds only, loopback URLs only, isolated test session store), so a restarted process keeps using it. The user's real session and the hosted project are never involved.
- **Harness lesson:** killing an *instrumented* process makes Android force-stop the package and remove its task, so the retained-task scenario drives the real app from the host (adb + UI dump), not through instrumentation.

## Results (final run, all on the final build; PIDs differ every case)

| Case | Observed |
| --- | --- |
| S1 save acknowledged, then SIGKILL before any network | Row on disk owned by the signed-in user, state Queued, private photo bytes identical to the compressor output. The restarted process synced exactly one report, one object whose SHA-256 matches, then removed the local copy. |
| S2 photo uploaded, RPC held (never committed), SIGKILL, retry same UUID | Row `Sending` on disk, zero commits at kill. After restart: one report, one object, the duplicate upload was answered 400 and the stored bytes were read back and compared; hashes match. |
| S2b existing object with different bytes at the report path | Not accepted: the duplicate was compared, the row became `Failed` (non-retryable), zero reports committed, the seeded object unchanged. The user-visible text is `HTTP_400: The resource already exists`, correct in effect but not helpful (polish item). |
| S3 server committed, acknowledgement lost, SIGKILL | Local row `Sending`, photo kept until acknowledgement. After restart: Synced, two RPC attempts, exactly one new commit, one object. |
| S4 account A queued, B signs in, process killed and restarted | A's row stays owned by A and `Queued`; B committed nothing and uploaded nothing (including after the restart). When A signs back in, one commit owned by A. |
| S5 real system picker, process SIGKILLed with the task retained | Same task id before/after, new PID. The app restored the unsaved selection ("Photo selected successfully"), and **Submit then uploaded the photo and committed the report with no error**: the picker grant outlives the process while the task lives (it is lost only when the task is gone, as observed earlier). Unsaved form text beyond that was not asserted; no draft-persistence feature is specified, none was added. |
| S6 original copy + saved resumable-upload session, SIGKILL, restart | Original file bytes durable, `original_state` and `upload_url` unchanged after restart, no server request while offline. |
| S7 SIGKILL after `Synced`, before the local photo copy is deleted | **Defect BG-015**, fixed (below). |

## Defect found and fixed (BG-015)

If the process died after a report was marked Synced but before its compressed copy was deleted, nothing ever revisited the file: the private photo stayed on disk indefinitely. Failing control (before the fix, S7): `copy_removed_after_restart=false`, the `<id>.jpg` remained. Fix: `ReportQueue.purgeSyncedPhotos` runs whenever the queue schedules (account restoration and every enqueue) and deletes the copies of Synced rows. After the fix: `copy_removed_after_restart=true`, no file left. The existing 12 `AuthQueueFailureTest` cases, `ReportSchedulingTest` and the picker/compressor/feed/map suites still pass.

## Blocked / not covered (precise)

- **TUS resume (HEAD-driven PATCH offset, interrupted chunk, lost attach acknowledgement):** `WifiOriginals` pins `https`, port 443 and the `*.supabase.co` / `*.storage.supabase.co` hosts and binds every socket to a real unmetered Wi-Fi `Network`. A local TUS fake cannot satisfy that without weakening a security check, so only the durable-state slice (S6) was tested. The earlier live 6 MiB resume on the phone remains the evidence for the transport.
- Cellular/Wi-Fi gating, OEM scheduling deadlines and USB-powered timing are not claimed; emulator timings are observations.
- I/O-failure injection for the file copy and SQLite commit was not added; the acknowledged boundary is proven by S1 (kill after acknowledgement) and the earlier unit/instrumented failure tests.
- Real hosted positive check: not run (no hosted write was needed to prove the lifecycle).

## Reproduce

Build both APKs, install on an owned emulator, then:

```powershell
python backend\scripts\process_death_harness.py --serial emulator-5556 --work <private dir outside the repo> [--scenarios s1,s2,s2b,s3,s4,s5,s6,s7]
```

The host-side parts (serial guard that refuses any non-emulator serial, fake-server fault semantics, missing-file detection) run in CI through `npm test`; the emulator matrix is manual.
