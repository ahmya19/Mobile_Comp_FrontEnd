# Claude work package: UI/UX mirroring and whole-app verification

User assignment, November 22, 2026: implement the UI structure to mirror the provided UI/UX and test the entire PinPoint Citizen App, including its backend. Codex remains the backend implementation/fix owner; current responsibilities are in [HANDOFF.md](HANDOFF.md).

Repository: `C:\Users\USER\project\MobileConputing`. Work on `backend/pinpoint-free`; [draft PR #1](https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1) is open. The preceding implementation/evidence baseline is e8a473d; inspect the current HEAD and working tree before editing. Continue the existing Kotlin/Compose app and integrated backend.

## 1. Establish the design reference and baseline

Read HANDOFF.md and CHECKPOINTS.md first. Before wiring or testing data, read [backend/README.md](backend/README.md) and [backend/VERIFICATION.md](backend/VERIFICATION.md). Use their current build/test commands and evidence rather than treating this handoff as a fresh test pass.

Locate the user's provided UI/UX reference in the current attachments, design link or local files. Record its exact path/link and screen/frame names. Its location is not recorded in this repository handoff. If the reference is unavailable, ask the user for it before making visual matching decisions; meanwhile continue baseline checks and integration test preparation.

Create a screen inventory mapping each reference screen to its Compose screen, navigation destination and backend action/read. Include citizen and official roles, detail screens, forms, dialogs and loading/empty/error/offline/success states. Completion: every supplied screen is accounted for, reference gaps are explicit and the current baseline checks are recorded.

## 2. Mirror the UI/UX using the existing integration

Match layout hierarchy, spacing, typography, colors, assets/icons, component states, navigation and interactions to the reference. Reuse existing project styles/components and adapt the existing screens. Preserve backend-required states such as saved-on-device, sending, acknowledged sync, role restrictions and recoverable errors. Use the queue acknowledgement for sync state; Pending is a report's business status.

Bind screens through the existing ViewModel/repository, encrypted session store, account-bound SQLite queue, WorkManager uploads and notification handling. Retain stable report IDs, account/project isolation, Wi-Fi-only originals, status/history semantics and notification report IDs. Coordinate shared data/ViewModel changes with Codex. For a backend contract defect, provide Codex the expected contract, actual response, exact redacted error and a minimal reproduction.

Completion: screenshot comparisons cover every mapped screen and its important states; forms/navigation work with the real integration; keyboard/insets, scrolling, back navigation, font scaling and accessibility remain usable. Keep screenshots free of credentials and precise personal location. Visual mirroring remains pending until the supplied reference has actually been compared.

## 3. Test the whole app, including the backend

Claude owns running and documenting these checks even when the backend code was written by Codex. Exercise backend authorization through API/automated tests as well as through the UI. Existing successful checks are a baseline; rerun relevant checks after changes. Use local fixtures/emulator checks first. Hosted writes, test-data cleanup, deployments/secrets and physical-phone operations require the user's approval for the concrete scope.

| Area | Required checks |
| --- | --- |
| Auth/session | Demo login for both roles; invalid credentials; encrypted restoration; logout and account switching; unconfirmed-login routing; invalid/expired confirmation codes and resend limits. Real signup email receipt remains blocked until the user completes free SMTP/template setup. |
| Profile | Read/edit/validation/persistence; owner-only access; editable profile metadata cannot assign an official role. |
| Citizen report | Required fields, category/barangay, coordinate validation, real gallery selection/cancellation, preview, submit, duplicate retry, details and cloud photo display. Label generated/stubbed picker checks separately. |
| Offline recovery | Save a report/photo offline, force-stop, reopen, reconnect, verify the same UUID and one cloud copy with intact photo. Test ordinary foreground/background app behavior and account isolation. Preserve failures rather than counting eventual recovery as a latency pass. |
| Originals/network | Compressed uploads on mobile; originals wait for unmetered Wi-Fi; interrupted TUS upload resumes correctly; completed bytes match; actual network-switch testing and byte measurements remain separate from a simple mobile-only reconnect. |
| Feeds/pagination | My Reports, community/home, filters, empty/error/retry states, cursor ordering with equal timestamps and concurrent inserts. Notifications must load older pages without gaps or duplicates and retain correct read state. Hosted 50+ notification fixtures need approval. |
| Officials/history | Assigned-barangay status/priority updates; persisted history; exactly one notification under retry. Citizen and wrong-barangay mutations must be rejected by the backend, including direct API attempts. |
| Votes | One vote per account, toggle/removal, persisted count, urgency calculation and the official minimum. Current 3/10 thresholds are provisional pending group confirmation. |
| Map/GPS | Loaded markers, correct report on tap, filters, long-press pin updates both coordinates; denied permission/timeouts; actual outdoor GPS measurements with reference uncertainty. Keep precise real coordinates private. |
| Notifications/FCM | History/read state, correct report on tap, foreground/background receipt, account switching/logout and delayed previous-account messages. Two-account sequential emulator checks do not establish simultaneous two-phone timing. Permission/settings changes need approval. |
| Backend/security | Run SQL/business/security tests and Edge checks; verify RLS/RPC role and ownership rules, private profile/notice access, anonymous photo denial, owner/assigned-official original-photo access, idempotency and error paths. Keep secret/service-role/FCM credentials out of the APK and Git. |
| SMS | Parser, signatures, stale/tampered requests, replay/deduplication and unsupported inputs. Receiver stays fail-closed until an approved gateway, secrets and barangays are configured. Simulated HTTP tests do not establish carrier delivery. |
| Full workflow | Citizen UI report/photo → official community/details/status/history → citizen notification/report/read state, with persisted backend verification. Run emulator first, then approved phone tests. |

Before changing credentials, email delivery, gateway configuration, schema or server behavior, read the corresponding setup in backend/README.md and coordinate the backend work with Codex. Stay on Supabase Free and free FCM. The user controls new-provider signup, passwords and any required phone settings; Play Protect stays enabled.

## 4. Preserve the current unresolved cases

Read [backend/CP25_PHONE_TESTS.md](backend/CP25_PHONE_TESTS.md) before interpreting phone timings and [backend/PHONE_TRIAL_RUNNER.md](backend/PHONE_TRIAL_RUNNER.md) before using the measurement harness. Ten reports have recovery evidence. Run 7 used Wi-Fi instead of planned cellular; run 8's background observer stalled and recovered after reopening, with invalid timing. Foreground runs 9–10 do not resolve that background issue or explain the earlier 48.5 s sample.

Reproduce the background case using the ordinary app and an external observer before assigning a cause or changing retry behavior/device settings. Compare app, WorkManager, system connectivity and server evidence; full instrumentation runtime includes user waiting and is not upload latency. A host deadline now bounds future stalled tests; the watchdog branch was tested locally, not deliberately timed out on the phone.

For email setup consult [backend/EMAIL_CONFIRMATION.md](backend/EMAIL_CONFIRMATION.md); demo accounts remain the current path. Real SMTP, SMS/carrier checks, approved barangays/thresholds, outdoor GPS, handoffs, push timing and SUS remain open where the recorded evidence says so.

## 5. Deliver evidence and coordinate fixes

Codex reproduced an ordinary-app background non-start: no report for at least 256.324 s after observed validation, with an enqueued/unattempted worker at a snapshot. Both approved fixtures eventually recovered with matching photos. The expedited scheduling candidate passes 3 local WorkManager checks, 10 JVM tests and build/lint (0 errors, 35 warnings); it is not yet phone-verified. See [backend/BACKGROUND_SYNC_INVESTIGATION.md](backend/BACKGROUND_SYNC_INVESTIGATION.md) and [BACKEND_DEFECTS.md](BACKEND_DEFECTS.md). Preserve the unknown recovery trigger, foreground-visibility caveat and separate measurement cohorts. Fresh installation/fixtures require user approval; coordinate with Codex rather than marking BG-001 fixed.

Maintain one test matrix with: screen/flow; reference frame; expected result; actual result; device/build/network; local/mock/live scope; UI screenshot plus backend evidence; Pass/Fail/Blocked; exact redacted error and reproduction. Compare cloud ownership, IDs, counts, read flags and photo integrity, not only visible success messages.

Record every backend failure for Codex in BACKEND_DEFECTS.md, with role/barangay, sanitized request/response, timestamp, expected/actual IDs/state and relevant code path. Summarize those records in the handoff. Record authorization denial tests as expected passes when they match the policy. Keep phone serials, private credential-file contents, tokens and raw precise location outside Git/screenshots/logs shared with the group.

Update CHECKPOINTS.md and backend/VERIFICATION.md with observed evidence and remaining blockers. Commit reviewable UI/test batches and push to the existing PR branch; preserve others' uncommitted work. Completion requires reference-based UI comparisons, complete flow coverage, fresh relevant build/test results and explicit unresolved cases. Mark each external dependency Blocked with its required input instead of claiming the whole app or backend is fully verified.
