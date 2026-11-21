# PinPoint project checkpoints

Last updated: November 21, 2026 (Asia/Manila).

## Where we are now

**The first backend implementation batch is complete locally. Cloud activation and device verification are next.** The existing frontend is preserved. Firebase project `pinpoint-citizen-group2-2026` exists, its Android configuration is installed locally, and Firestore rules and indexes are deployed.

**Current checkpoint: CP-04, enable cloud email/password authentication.** Then finish Storage and Functions setup, and verify a real citizen-to-official reporting flow. No new implementation is required before starting CP-04.

Local evidence from the implementation batch: both Android debug build modes pass, 4 Android JVM tests pass, 10 backend emulator tests pass, and Android lint completes with no errors. These checks do not prove that the full app works on a physical phone or that live FCM messages arrive.

## Reading the statuses

| Status | Meaning |
| --- | --- |
| Done | The stated checkpoint and its verification are complete. |
| Local verified | The backend behavior passed local emulator tests; cloud/device verification remains. |
| Implemented | Code exists and builds; the complete user flow still needs verification. |
| Needs setup | Account, console, deployment or access setup is outstanding. |
| Planned | Implementation has not started. |

A feature is not finished merely because its screen exists or its code compiles. Each checkpoint below states what must be observed before it can be closed. Statuses describe the last recorded verification; external console changes must be checked before updating them.

## Batch 1 Repository and backend foundation

| ID | Task | Status | Evidence or remaining completion condition |
| --- | --- | --- | --- |
| CP-01 | Clone the existing frontend into the requested folder | Done | Repository is at `C:\Users\USER\project\MobileConputing`; remote is `ahmya19/Mobile_Comp_FrontEnd`. Do not repeat frontend creation or its original upload. |
| CP-02 | Evaluate the backend and database options | Done | Firebase selected against Supabase and custom SQL API alternatives; architecture and tradeoffs recorded in `backend/README.md`. |
| CP-03 | Create Firebase project, register Android app and prepare Firestore | Done | Cloud project and Android package `com.example.pinpoint` created; default Firestore database in `asia-southeast1`; rules and report index deployed. Android configuration is ignored by Git. |

## Batch 2 Cloud activation Current priority

Owner: backend developer, with the Firebase account owner completing account sign-in and billing steps.

| ID | Task | Status | Close this checkpoint when |
| --- | --- | --- | --- |
| CP-04 | Initialize Authentication and enable Email/Password | Needs setup | Email/Password is enabled in this project's console; the Android app registers and signs in a test citizen successfully. Last API initialization attempt returned `BILLING_NOT_ENABLED`; the console required sign-in. Ordinary Firebase email/password authentication should be distinguished from the API path that failed. |
| CP-05 | Enable billing where required and create the Storage bucket | Needs setup | The account owner enables Blaze for Storage/Functions, creates the bucket and confirms the Android config identifies it. Billing has not been attached by this work. |
| CP-06 | Deploy Storage rules and Cloud Functions | Needs setup | Deployment succeeds for the checked-in Storage rules, `updateReportStatus` and `sendStatusNotification` in `asia-southeast1`. Firestore rules/indexes are already deployed. |
| CP-07 | Prepare citizen and official test accounts | Needs setup | Two test accounts exist; a trusted backend assigns official and barangay claims to the official; claims refresh after signing in again. No account can grant itself an official role. |

The backend does not need to wait for billing to continue local development: the checked-in `demo-pinpoint` emulator workflow is available. Full live verification does depend on cloud setup.

## Batch 3 Core app integration Implemented and awaiting live verification

Owner: backend and integration developer. Coordinate screen changes with the mobile developer.

| ID | Task | Status | Close this checkpoint when |
| --- | --- | --- | --- |
| CP-08 | Registration, login, session restoration and logout | Implemented | On a device, a new citizen registers, logs in, remains signed in after reopening the app, then logs out without another account receiving their notifications. Depends on CP-04. |
| CP-09 | Private profile and display-name editing | Implemented | A citizen saves their display name and sees it after reopening; another account cannot read or modify their profile. Profile privacy rules already pass emulator tests. |
| CP-10 | Create a text report and retrieve the correct details | Implemented | A device submits category, description, location and barangay; one Firestore document is created; My Reports opens that same ID and values; invalid input shows an error instead of success. |
| CP-11 | Upload and display a report photo | Implemented | After CP-05/06, a selected photo is compressed, uploaded and displayed for the saved report; upload failure does not produce a false success. Size, owner, MIME and immutability restrictions already pass emulator tests. |
| CP-12 | Live My Reports, community feed and home cards | Implemented | Citizen A's report appears in A's list and B's community feed; B's My Reports stays separate; empty/loading/error states behave correctly. Current feeds are limited to the latest 100 records. |
| CP-13 | Official status updates and status history | Local verified | After CP-06/07, an official opens another citizen's report and updates it; only the assigned barangay is allowed; the report and history match. Authorization, transitions and idempotent status retry already pass emulator tests. |
| CP-14 | Notification history and read/unread state | Local verified | A live official update creates exactly one notification record for the update in the citizen's notification list; opening the notification opens the right report and persists its read flag. Backend creation and access restrictions pass emulator tests. |
| CP-15 | FCM push delivery | Implemented | After CP-06/07, a status update reaches the citizen's phone in foreground and background with permission granted; stale tokens and logout/account switching are checked. Real FCM is not exercised by local emulator tests. |
| CP-16 | Run the first complete two-account device test | Planned | CP-08 through CP-15 work together: citizen signs in, submits a photo report, official changes status, citizen sees history and push, and the new status persists after reopening. Record device, build, test account roles and actual results. |

## Batch 4 Remaining proposal features

| ID | Task | Status | Owner and completion condition |
| --- | --- | --- | --- |
| CP-17 | Agree a durable offline report/photo queue contract | Planned | Shared. Confirm queue fields, stable report IDs, account ownership, photo file lifetime, retry behavior and pending/synced/failed states before either teammate duplicates implementation. |
| CP-18 | Save and synchronize offline reports | Planned | Mobile developer owns durable device storage/background scheduling; backend developer owns idempotent ingestion and validation. Reports survive app/process restarts and synchronize without duplicates after reconnection. Firestore caching alone does not close this checkpoint. |
| CP-19 | Network-aware smart uploads and recovery | Planned | Shared. Compressed uploads on cellular, full variants on Wi-Fi, persisted upload sessions and network-switch recovery are implemented and measured. Current compression on every connection is only the initial baseline. |
| CP-20 | GPS pin capture and map display | Planned | Mobile developer owns permissions, GPS/map UI and pin adjustment; backend developer supports coordinate validation and retrieval. A submitted coordinate appears at the correct map pin. Optional coordinate fields already exist. |
| CP-21 | Feed/map filtering and pagination | Planned | Shared. Add barangay/category/date/status filters, indexes and pagination; records beyond the initial 100 are reachable without duplicates or gaps. |
| CP-22 | Community voting and derived urgency | Planned | Backend developer owns vote uniqueness, authorization and aggregation; mobile developer owns voting controls. One vote per account is enforced on the server and Low/Medium/High urgency follows agreed thresholds. Current official-set priority is not community voting. |
| CP-23 | SMS gateway and report parser | Planned | Backend developer owns gateway authentication, input validation, replay protection and report ingestion. A formatted SMS creates one text-only report using the named barangay location; malformed or repeated gateway requests are handled correctly. Confirm gateway device/SIM and message format first. |

## Batch 5 Validation and team delivery

| ID | Task | Status | Close this checkpoint when |
| --- | --- | --- | --- |
| CP-24 | Establish automated backend and Android check baseline | Done | Baseline recorded: 10 backend emulator tests, 4 JVM tests, cloud/emulator debug builds, lint with zero errors and clean npm audits. Add tests when new security/business behavior is implemented; rerun relevant checks after changes. This baseline does not close CP-16 or CP-25. |
| CP-25 | Measure the proposal's acceptance targets | Planned | Record actual submission time, payload size, offline sync time/loss, upload-resume success, SMS delivery, GPS accuracy and push latency. Compare against targets rather than assuming SDK support proves them. Targets should be confirmed with the project lead before evaluation. |
| CP-26 | Review and share backend changes through GitHub | Planned | GitHub write permission was confirmed on November 21, 2026: the signed-in account has `push: true`. Review the complete local diff, then commit/push or open a PR when requested. This work has not pushed the backend changes. |
| CP-27 | Final team handoff and documentation | Planned | Setup guide reflects the deployed system; all unresolved issues and test results are recorded; teammates can build/run it; the project lead has the evidence needed for the final report. |

## What we will do next

1. **Activate and verify cloud authentication (CP-04).**
2. Complete Storage/billing and deploy Functions (CP-05/06).
3. Prepare citizen/official accounts and run the complete device flow (CP-07 through CP-16).
4. Agree the offline queue contract, then implement offline sync and upload recovery with the mobile developer (CP-17 through CP-19).
5. Add map queries/filtering, voting and SMS, then measure the proposal targets (CP-20 through CP-25).

If cloud setup is pending, progress locally on CP-17 and emulator-backed backend work. Preserve the cloud/device verification checkpoints until actual results are recorded.

## How we will keep checkpoints current

After each work batch, update this file in the same set of project changes:

1. Identify the checkpoint IDs worked on.
2. Record the implemented behavior and verification evidence.
3. Change the status only when its stated completion condition is satisfied.
4. Keep unfinished setup, errors and device tests visible.
5. Name the next checkpoint and its prerequisites.

Use this short update format in chat and in the log below:

```text
Checkpoint IDs:
Completed or changed:
Verified by:
Still pending:
Next checkpoint:
```

This is a working progress tracker, not a Git commit or backup. Application changes still need version control under CP-26.

## Checkpoint log

| Date | Checkpoints | Recorded result | Next |
| --- | --- | --- | --- |
| 2026-11-21 | CP-01 to CP-03, CP-08 to CP-15, CP-24 | Backend foundation and mobile bindings implemented; Firestore deployed; local checks passed. Cloud auth, Storage, Functions and device/push validation remain incomplete. | CP-04 |
| 2026-11-21 | Tracker created; CP-26 access verified | Added per-task status, ownership, completion conditions and planned batch order. GitHub API confirms write permission is now available. No application code or cloud settings changed in this tracker update. | CP-04 |

Detailed setup and architecture: [backend/README.md](backend/README.md).
