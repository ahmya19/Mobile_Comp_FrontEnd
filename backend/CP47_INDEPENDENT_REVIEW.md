# Independent verification and review of the CP-47 merge (CP-48)

November 23, 2026. A different agent (Antigravity) delivered CP-47 (PRs #27 and #28, main `272e1c4`). This checkpoint independently re-verified its claims on this machine and reviewed the security-sensitive code. The physical-phone task from that handoff **could not run**: the phone was not connected (adb listed no device).

## Claims reproduced

| Claim | Result here |
| --- | --- |
| main `272e1c4`, no open PRs, CI green on both merges | confirmed (`gh`: both runs success) |
| Debug, test and release APK hashes | **identical** to the handoff (`20963006...`, `79e1dbfd...`, `55b70a82...`): the build is reproducible |
| backend 81/81, Edge type check | pass |
| JVM tests, lint | pass; lint 0 errors / 38 warnings, as stated |
| Emulator suites (spot check of nine classes) | pass: UiParityAndCameraDraftTest 6, AuthQueueFailureTest 12, ReportSchedulingTest 5, OriginalRecoveryTest 13, EmailConfirmationTest 6, PhotoCompressorTest 11, FeedRecoveryTest 5, NativePickerTest 3, plus the new QueueClaimRaceTest 2 |

Not re-run: the live hosted TUS smoke and cleanup runs (they write to the hosted project; their cleaned state is in the handoff), the restore harness, and anything on the phone.

## Review findings

1. **BG-020 (defect, fixed).** `ReportSyncWorker` lists the Queued rows once, then submits them one by one from that snapshot. The new local actions `deleteUnsent` and `updateUnsent` can change a row while the worker is busy with an earlier one, so the worker submitted its stale copy: **a report the user had deleted was still committed** (text-only reports have no photo file whose absence would stop it), and **an edited report was submitted with its old content**. Reproduced with a deterministic test (hold the first submit, delete or edit the second): both failed. Fix: `ReportQueue.claimForSend` atomically moves one row to Sending only if it is still Queued or Sending for that account and project, and returns its *current* stored content; the worker skips rows that vanished. It is serialized with the delete and edit methods, which already refuse Synced and Sending rows. After the fix both race tests pass, and AuthQueueFailureTest, ReportSchedulingTest, OriginalRecoveryTest, UiParityAndCameraDraftTest and the real-SIGKILL cases S1, S2, S3, S4, S7 still pass.
2. **Password recovery completes by replacing the active session.** `completePasswordReset` verifies the emailed code, saves that recovery session, then sets the new password. If the second step fails (network drop), the user is left signed in as the recovered account without the password changed. This is how Supabase recovery works and is acceptable, but any account-change handling (queue ownership, device registration) must treat it like a sign-in; the existing owner-bound queue logic does. Not changed.
3. **Password rules are client-side only.** The UI shows upper/lower/number/symbol rules, but only the 8-character minimum is checked in code and the server enforces its own (default shorter) minimum. A user can therefore set a password the screen calls weak through another client. Not a vulnerability; document it or raise the Supabase minimum in the dashboard when the owner configures email.
4. **Manifest and FileProvider are sound:** `CAMERA` with `required=false`, a non-exported provider limited to `files/camera_captures/`, no media, SMS or storage permission.

## Still blocked or not run

The task assigned by the handoff (update the phone to `272e1c4` and run the CP-47 suite, the SIGKILL matrix and camera verification on the device) is **Not run**: no device was connected. It also needs the phone's session preserved and real camera capture to be done by the owner (a hardware capture would photograph the owner's surroundings, so it was not attempted remotely). All 3B acceptance cohorts, email, SMS, the live restore project and the signed release install remain as listed in the CP-47 handoff.
