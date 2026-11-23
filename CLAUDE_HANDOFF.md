# Claude work package: whole-app completion

**Current assignment, November 23:** after Codex finishes CP-43, Claude receives a heavy completion batch covering backend TUS recovery/restore/cleanup, approved PDF UI and integrations, then all pending phone/field/release acceptance. Backend fixes are within this assignment; the older Codex-backend/Claude-UI split below is historical. Use the detailed private `PinPoint-Heavy-Completion-Handoff.md` supplied by the user for ordered steps and evidence gates, and the current [HANDOFF.md](HANDOFF.md) for delivery state. Return a fresh Codex handoff at the end or before usage limits. The phone is for the next batch when actually connected and released; preserve its session and keep it powered. Completed historical cohorts need rerunning only when affected code/build changes or new failures justify it.

## Historical UI work package (reference)

User assignment, November 22, 2026: implement the UI structure to mirror the provided UI/UX and test the entire PinPoint Citizen App, including its backend. Codex remains the backend implementation/fix owner; current responsibilities are in [HANDOFF.md](HANDOFF.md).

Repository: `C:\Users\USER\project\MobileConputing`. PRs #1–#9 are merged; backend baseline is dbb2568. CP-30 automated CI passed on GitHub in PR #10; inspect its merge state and HANDOFF.md for the next independent backend task (migration replay/coverage audit). The phone is unavailable; leave it untouched until the user says otherwise. Continue the existing Kotlin/Compose app and integrated backend. Earlier branch/next-task notes below are historical.

Latest backend continuation update: Claude implemented hosted map navigation plus BG-003, and Codex verified the ordinary TECNO chooser/details/back route using existing data/session. The backend continuation handover is separate from this UI/UX work package. Current branch backend/map-hosted-navigation, draft PR #6 depending on #5/#4/#3; inspect actual state before editing. Read [backend/MAP_HOSTED_NAVIGATION_VALIDATION.md](backend/MAP_HOSTED_NAVIGATION_VALIDATION.md) for the latest evidence; older native-test limitations below are historical. Wider filter/official/outdoor and full-app acceptance remain open.

Latest independent backend follow-up: user is using the phone; proceed without phone operations until it is available. Branch backend/map-stack-pagination builds on draft PR #6 and removes the chooser cap. Native 26/75-report selection and Cancel/reopen pass with related regressions (6/6); no phone/hosted changes. Read [backend/MAP_STACK_VALIDATION.md](backend/MAP_STACK_VALIDATION.md) before changing the chooser. Earlier phone-build/branch notes are historical.

Latest backend verification: backend/official-map-validation builds on draft PR #7 and verifies the official hosted map with existing data (1/1, 12.58 s), including assigned/other-barangay controls. Report/notice/history fingerprints and local session/device cleanup were verified. Test/doc changes only; no phone operations. See [backend/OFFICIAL_MAP_VALIDATION.md](backend/OFFICIAL_MAP_VALIDATION.md). The independent backend continuation remains separate from this UI design assignment; use the current handover scope.

The Storage follow-up and dependent PR stack are merged. Follow the current delivery/CI task in HANDOFF.md. The receiving tool follows the current backend handover scope, separate from this UI assignment, and supplies a return handoff with the exact next task.

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

Before changing credentials, email delivery, gateway configuration, schema or server behavior, read the corresponding setup in backend/README.md and coordinate the backend work with Codex. Stay on Supabase Free and free FCM. The user controls new-provider signup and passwords; Play Protect stays enabled. The current unattended project-phone investigation was explicitly authorized, including per-app settings and synthetic fixtures; do not repeat already-covered approval questions. Check scope/device availability for later unrelated operations.

## 4. Preserve the current unresolved cases

Read [backend/CP25_PHONE_TESTS.md](backend/CP25_PHONE_TESTS.md) before interpreting phone timings and [backend/PHONE_TRIAL_RUNNER.md](backend/PHONE_TRIAL_RUNNER.md) before using the measurement harness. Ten reports have recovery evidence. Run 7 used Wi-Fi instead of planned cellular; run 8's background observer stalled and recovered after reopening, with invalid timing. Foreground runs 9–10 do not resolve that background issue or explain the earlier 48.5 s sample.

Codex reproduced the background case with the ordinary app and an external observer, then identified and reverse-tested the separate TECNO Battery Lab policy. Follow [backend/ANDROID_BACKGROUND_SETUP.md](backend/ANDROID_BACKGROUND_SETUP.md) during regression; do not replace this evidence with a generic Android Unrestricted check. Compare app, WorkManager, system connectivity and server evidence; full instrumentation runtime includes user waiting and is not upload latency. A host deadline now bounds future stalled tests; the watchdog branch was tested locally, not deliberately timed out on the phone.

For email setup consult [backend/EMAIL_CONFIRMATION.md](backend/EMAIL_CONFIRMATION.md); demo accounts remain the current path. Real SMTP, SMS/carrier checks, approved barangays/thresholds, outdoor GPS, handoffs, push timing and SUS remain open where the recorded evidence says so.

CP-14 follow-up: read [backend/NOTIFICATION_PAGING_VALIDATION.md](backend/NOTIFICATION_PAGING_VALIDATION.md). Hosted ordering, insert-burst refresh, owner/read rules and ordinary-phone navigation/read state after restart passed; scoped fixtures are removed. Retain these scenarios during UI restructuring. Review the observed stale global network-error banner in the new UI. The latest user request keeps the phone on/awake, with USB stay-awake 2; do not run screen-off/reboot tests while that preference applies.

CP-21 follow-up: read [backend/REPORT_FEED_VALIDATION.md](backend/REPORT_FEED_VALIDATION.md) before changing feed/filter UI. Hosted 17-case ordering/filter checks and insert refresh passed; phone combined filters, all 20 Beta rows and correct details passed. All 97 fixtures are removed. Preserve those regressions; map marker rendering and whole-app acceptance remain open.

CP-22 follow-up: read [backend/VOTING_VALIDATION.md](backend/VOTING_VALIDATION.md) before changing vote/urgency controls. 32 ordinary hosted API checks passed; local threshold regression expanded the suite to 30 tests. All fixtures are removed. Preserve retry, account privacy, role denial and official-floor behavior; group threshold approval and mobile UI acceptance remain pending.

Native map follow-up: read [backend/MAP_INTEGRATION_VALIDATION.md](backend/MAP_INTEGRATION_VALIDATION.md). Local screen fixtures with actual MapLibre rendering verified page/filter marker IDs, invalid-coordinate exclusion and exact marker-tap callback (1/1, 5.635 s). The full AppNavigation-to-details route was not exercised. Complete that route and phone/outdoor cases during whole-app regression. Latest branch backend/map-integration-validation depends on CP-22.

## 5. Deliver evidence and coordinate fixes

Codex retains the 2c94c80 empty-job fix (BG-002). The established WorkManager path now passes configured-TECNO background Wi-Fi checks: 1.310 s from observed availability / 0.096 s from validation awake, and 1.337 / 1.337 s with the screen off. All sampled app-state reads stayed off screen and kernel-unfrozen in those positives. Battery Lab > Power Saving Management for Apps > Pinpoint > No Restrictions is separate from Android Unrestricted; App Launch was already Allowed. Reverting just the OEM policy to Intelligent Management reproduced freezing and no row for 82.831 s after validation; restoring No Restrictions recovered that same report without reopening PinPoint. The additional native-transfer experiment also stalled under the restriction and was removed.

Six new diagnostic fixtures recovered once with matching decoded photos, zero coordinates and zero originals. Keep them separate from the earlier five diagnostics and ten historical persistence/recovery samples. The final tested APK SHA-256 is d008315e7a25bf8800a684e8143b5bc82b2eb2c8494127d3d56965761073fdcf; runtime source matches the 2c94c80 correction. Five local scheduler checks, ten JVM tests and build/lint pass (0 errors, 35 warnings). BG-001 has a device-specific mitigation; other vendors, carrier/overnight/unplugged behavior and proposal statistics remain unverified. See [background investigation](backend/BACKGROUND_SYNC_INVESTIGATION.md) and [BACKEND_DEFECTS.md](BACKEND_DEFECTS.md). Preserve saved-on-device versus server-acknowledged UI states, including during the failing policy control. Do not add runtime battery-setting overrides or count an eventual recovery as a timing pass. Radios/display settings were restored; only PinPoint's approved per-app exemptions and the tested APK remain.

Maintain one test matrix with: screen/flow; reference frame; expected result; actual result; device/build/network; local/mock/live scope; UI screenshot plus backend evidence; Pass/Fail/Blocked; exact redacted error and reproduction. Compare cloud ownership, IDs, counts, read flags and photo integrity, not only visible success messages.

Record every backend failure for Codex in BACKEND_DEFECTS.md, with role/barangay, sanitized request/response, timestamp, expected/actual IDs/state and relevant code path. Summarize those records in the handoff. Record authorization denial tests as expected passes when they match the policy. Keep phone serials, private credential-file contents, tokens and raw precise location outside Git/screenshots/logs shared with the group.

Update CHECKPOINTS.md and backend/VERIFICATION.md with observed evidence and remaining blockers. Commit reviewable UI/test batches on a coordinated branch and open a PR against the latest main; preserve others' uncommitted work. Completion requires reference-based UI comparisons, complete flow coverage, fresh relevant build/test results and explicit unresolved cases. Mark each external dependency Blocked with its required input instead of claiming the whole app or backend is fully verified.
