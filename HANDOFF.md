# PinPoint team handoff

Start here for ownership and current project state. Details: [backend/README.md](backend/README.md). Task status: [CHECKPOINTS.md](CHECKPOINTS.md). Evidence: [backend/VERIFICATION.md](backend/VERIFICATION.md).

**Budget rule: Supabase Free and free Firebase FCM only. Never enable Blaze or attach billing.**

## Latest backend validation — November 23

Current branch backend/official-map-validation builds on backend/map-stack-pagination (draft PR #7). The official-account hosted map passed 1/1 in 12.58 s with independent SQL ID/role expectations: six first-page markers, 13 after load-more, one Garbage marker, assigned stack/details with edit controls and other-barangay details without them. No business mutation control was activated. All 36 reports/15 notices/15 history rows retained full-row fingerprints; session/owned emulator registration cleanup verified. Test APK build and lint passed (0 errors/34 warnings). Runtime remains the prior 40f96668 candidate. No phone operations. See [official map evidence](backend/OFFICIAL_MAP_VALIDATION.md).

The next independent backend gap is hosted private-photo authorization/integrity using existing labeled synthetic assets. Phone remains unavailable until the user says otherwise. Prior branch/build paragraphs below are historical.

## Latest backend integration follow-up

The user is using the phone for other work and asked us to proceed without it. Leave the phone alone until it is available again; use an isolated emulator and independent backend tasks. No phone commands/settings/build changes were made in the BG-004 batch.

Current branch backend/map-stack-pagination builds on backend/map-hosted-navigation (draft PR #6). BG-004 removes the stacked-marker chooser cap of 25 and uses a bounded lazy list. Native 26/75-report selection, Cancel/reopen and existing map/form/notification checks passed 6/6; ten JVM tests/builds pass, lint 0 errors/34 warnings. Candidate APK SHA-256 40f966685f549045b533e59dbbb637cdaba674131f62939f7038404a203c094b was installed only on the owned emulator, which was then stopped. The phone has the earlier recorded build below; no fresh phone verification is claimed. See [stacked report evidence](backend/MAP_STACK_VALIDATION.md).

## Current backend continuation

The user separately authorized Claude to continue Codex backend implementation/integration during the handover. Claude completed hosted map navigation and BG-003; Codex has resumed backend ownership and verified the ordinary phone. This backend continuation is separate from the UI/UX work package below. Latest branch backend/map-hosted-navigation; draft PR #6 stacks on #5, #4 and #3, all currently unmerged.

Latest phone build SHA-256: 659bebd4b9736529b14abc706cefddf46bd36b3be317fa56dd6b36c07b0e1752. On the TECNO, all 11 chooser rows matched SQL, selected details and Back passed, and the existing citizen session/hosted report and notice state were preserved. Phone is left on PinPoint Home, awake with USB stay-awake 2, airplane off/Wi-Fi on. See [hosted map and phone evidence](backend/MAP_HOSTED_NAVIGATION_VALIDATION.md). Historical branch/build notes below describe earlier batches.

## Current ownership — user assignment, November 22

| Owner | Responsibility |
| --- | --- |
| User with Codex | All backend implementation and fixes: Auth, PostgreSQL/schema/RLS/RPCs, private Storage, Edge Functions, FCM server delivery, SMS adapter, security and integration contracts. Preserve the existing session, queue and upload behavior when coordinating mobile changes. |
| Claude | Restructure the Kotlin/Compose UI and navigation to mirror the user's provided UI/UX; connect every screen to the existing backend integration; test the whole application, including backend behavior and security. This includes finding and documenting backend failures even where Claude did not change backend code. |
| User/group | Supply the authoritative UI/UX references, approved barangays/thresholds, account/device access and evaluation participants; approve hosted-data/deployment/secret changes and phone operations. |

Before UI restructuring or whole-app verification, Claude must read [CLAUDE_HANDOFF.md](CLAUDE_HANDOFF.md). That work package defines design matching, the complete functional/backend test matrix and the evidence required for completion. Backend defects go to Codex with a reproducible case and exact redacted error; coordinate changes to shared data/ViewModel files through the branch/PR.

Latest backend work: the queue correction in 2c94c80 remains in the delivered runtime. Codex identified TECNO Battery Lab's separate Intelligent Management restriction: Android Unrestricted alone did not prevent kernel freezing. Setting only PinPoint to No Restrictions allowed existing WorkManager uploads in 1.310 s awake and 1.337 s screen off after observed Wi-Fi availability; a reverse control reproduced the delay and restoring the setting recovered the same report. The transfer-API experiment was removed. Six new diagnostic fixtures recovered once with matching photos. Final five scheduler / ten JVM checks and build/lint pass (0 errors, 35 warnings). BG-001 is mitigated on this configured phone; wider CP-25 acceptance remains partial. See [background setup](backend/ANDROID_BACKGROUND_SETUP.md) and [investigation](backend/BACKGROUND_SYNC_INVESTIGATION.md). Claude must retain both OEM-policy cases in whole-app/backend regression.

The user subsequently authorized unattended project-specific phone/PC investigation, test builds, radio/per-app setting changes and synthetic hosted fixtures. Those steps did not require repeated permission during this batch. The zero-budget, no-secrets and project-only boundaries remain; do not treat this as authority over unrelated apps/accounts or new paid services. Radios and temporary USB stay-awake are restored; PinPoint retains Android Unrestricted and OEM No Restrictions.

Completed task 2: [hosted notification pagination](backend/NOTIFICATION_PAGING_VALIDATION.md) passed with the real Kotlin feed and 149 rows, plus ordinary-phone report navigation/read persistence after restart. Temporary fixtures were removed and the original 15 notices preserved. PR #2 is merged into main at 066e6ae. The latest user instruction keeps the phone powered on/awake while they sleep; USB stay-awake 2 is intentionally left enabled for this batch.

Latest task 3: [hosted report feed/filter validation](backend/REPORT_FEED_VALIDATION.md) passed 17 SQL cases, an insert burst and phone combined filters/details. All 97 temporary reports were removed; original 36 unchanged. Current branch: backend/report-feed-validation from merged PR #2. Phone map rendering and whole-app acceptance remain open. The phone stays awake on USB.

Latest task 4: [hosted voting/urgency validation](backend/VOTING_VALIDATION.md) passed 32 API checks plus 30 local tests. Concurrent retries, private vote reads, role/direct-write denials, closed reports and official floors passed; local 3/10 crossings passed. All six reports/two history rows were removed. Current branch backend/voting-validation builds on the unmerged CP-21 branch. Group thresholds and whole-app/mobile acceptance remain separate.

Latest map validation: [native marker integration](backend/MAP_INTEGRATION_VALIDATION.md) passed 1/1 on the emulator using local fixtures and real rendered-feature IDs/hit testing. Page/filter controls and exact report-ID callback passed. Current branch backend/map-integration-validation builds on CP-22. Full-app details navigation, phone map behavior and outdoor GPS remain separate.

## What exists

Android app (Kotlin/Compose) on a Supabase Free backend: sign-in with email confirmation code, reports with photos and an offline queue, community feed and map pins, status updates by assigned officials, notifications with push, votes, filters and pagination. An SMS receiver is deployed but locked until a gateway is configured.

## Build and run

1. Use JDK 17 and the Android SDK (platform 36). Android Studio is optional.
2. Create `local.properties` in the project root (it is git-ignored): `sdk.dir=...`, `SUPABASE_URL=https://jaysxjjdvaitgrbqjlhe.supabase.co`, `SUPABASE_PUBLISHABLE_KEY=` (Supabase dashboard, Settings, API Keys). Never put secret or service-role keys here.
3. Put the app's `google-services.json` (from the existing Firebase project) in `app/`. It is git-ignored.
4. Build: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. The APK is `app/build/outputs/apk/debug/app-debug.apk`.
5. Backend tests: `cd backend; npm install; npm test` (30 tests, including 11 background-observer diagnostics) and `npm run check:edge`.

## Test accounts

Two confirmed demo accounts (citizen and official) exist. Their passwords are not in Git. Ask the backend owner for the private credentials file. Do not commit it or paste passwords into chat or issues.

## Run the tests on a device

- Emulator workflow (citizen photo report, official status change, citizen notification): `backend/scripts/run-emulator-workflow.ps1`, see [backend/EMULATOR_TESTING.md](backend/EMULATOR_TESTING.md).
- A physical phone needs the explicit `-AllowPhysicalDevice` switch. Decline the Google "Save password?" popup, or it covers the app and fails the test.
- Real-device measurements (offline sync, GPS, mobile data, push timing, SUS): follow [backend/CP25_PHONE_TESTS.md](backend/CP25_PHONE_TESTS.md) and record only what you measure.
- Ten offline recovery samples are recorded. The newest seven recovered once with matching photos, with one wrong-network deviation and one stalled background observation requiring reopen. Automatic background latency is still unresolved. The guarded measurement runner requires PowerShell 7; see [backend/PHONE_TRIAL_RUNNER.md](backend/PHONE_TRIAL_RUNNER.md).

## Still open (the group must supply or decide)

| Item | Needed |
| --- | --- |
| Public signup email (CP-08) | A free Brevo SMTP account and the confirmation template, see [backend/EMAIL_CONFIRMATION.md](backend/EMAIL_CONFIRMATION.md) |
| SMS reporting (CP-23) | A spare Android phone with a SIM that can run SMS Gateway for Android (Play Protect blocked it on one phone), then the setup in the SMS section of backend/README.md |
| Barangay list | Replace the three Test Barangay rows in `private.barangays` with the approved names and coordinates |
| Vote thresholds | Confirm 3 for Medium and 10 for High, or change them |
| Measurements (CP-25) | Real phones, outdoor GPS, mobile data and a user study (SUS 70 or higher) |
| PR review | PRs #1 and #2 are merged. Review backend/report-feed-validation; whole-app acceptance remains separate. |
| UI/UX and whole-app acceptance | Claude must mirror the supplied design and verify every user flow against the backend; use CLAUDE_HANDOFF.md and retain failed/blocked cases |

## Rules that keep this safe

- Secrets (service-role key, FCM private key, SMS gateway secret, account passwords) live only in Supabase Edge secrets or private files, never in the app or Git.
- Authorization is enforced in PostgreSQL functions and policies; a hidden button is not security.
- Do not describe unmeasured acceptance targets as met.
- Whole-app acceptance requires UI evidence and backend/integration evidence. A successful build or a visually matching screen alone does not close the handoff.
