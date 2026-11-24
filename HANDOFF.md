# PinPoint team handoff

Start here for ownership and current project state. Details: [backend/README.md](backend/README.md). Task status: [CHECKPOINTS.md](CHECKPOINTS.md). Evidence: [backend/VERIFICATION.md](backend/VERIFICATION.md).

**Budget rule: Supabase Free and free Firebase FCM only. Never enable Blaze or attach billing.**

## Current delivery — November 23 (supersedes the dated sections below, which are history)

**Git:** PRs #1–#26 are merged (`main` `64311ad`); CP-47 completion changes are delivered in reviewable PRs from `64311ad`. Inspect actual GitHub PR/check state before continuing. Earlier stack/next-task paragraphs below are historical. Do not restart completed merge, deployment or identity-provisioning work.

**Historical final-build paging follow-up (merged in #14):** on candidate 1a38747, CP-21 passed 17 SQL-oracle cases and the insert-burst traversal (1/1, 67.646 s); CP-14 matched all 149 notices/read persistence (1/1, 25.845 s). Its 98 reports/134 notices were cleaned, preserving all 12 baseline fingerprints. Do not automatically repeat that batch for documentation-only changes. Current next tasks are listed below.

**Done this round (Antigravity & Claude continuing whole-app completion; evidence in the linked documents):**
- CP-54 [Splash, 3-slide Onboarding, Permission Primer, motion polish & groupmate kit](backend/GROUPMATE_TESTING_GUIDE.md): implemented remaining Board 1 screens matching `MOBILECOMPUI\PinPoint-Citizen-App (1).pdf` (branded adaptive PinPoint launcher icon + Android 12+ violet splash + PDF 1.1 animated `PinPointSplashScreen`, PDF 1.2 3-slide `HorizontalPager` `OnboardingScreen`, PDF 1.7 `PermissionPrimerScreen` for Location/Notifications/Camera, replayable anytime from Profile without signing out), plus app-wide Compose transitions & micro-interactions (`NavHost` tab fade / pushed slide, `AuthScreen` & `ProfileScreen` `AnimatedVisibility`, `HomeScreen` `AnimatedContent` + animated pager dots, `ReportScreen` smooth 5-segment fill, `ReportSuccessScreen` spring halo, `PinPointBottomBar` tactile spring Camera FAB). Added [GROUPMATE_TESTING_GUIDE.md](backend/GROUPMATE_TESTING_GUIDE.md) (install instructions, 6-task checklist, 10-point outdoor Alangilan GPS sheet, 10-item SUS questionnaire + feedback template). Verified on physical TECNO CL8 (owner session byte-identical): 14/14 isolated suites (`87/87` tests incl. new `OnboardingAndMotionTest` 3/3), JVM `23/23`, lint `0 errors / 36 warnings`, backend `81/81`, Edge pass, release DEX audit `0` hits, live phone screenshots visually inspected.
- CP-53 [phone UI and BG-024 verification](backend/CP53_PHONE_UI_AND_BG024_VERIFICATION.md): CP-51 UI verified on the TECNO, BG-025 map-pin fix, 40-submission and 20-case push results, owner session restored.
- CP-52 [BG-024 hardening & CP-50 outlier forensic diagnosis](BACKEND_DEFECTS.md): diagnosed CP-50's `42.5 s` foreground FCM outlier (`push8` P1) from ledger traces as downstream Google FCM→Play Services MCS jitter (`created_to_dispatch_seconds = 9.66 s`, `outbox_attempts = 1`, `outbox_error = ""`, `android.priority = HIGH` already set; subsequent cases in `push8` delivered in `4.9–8.9 s`), and hardened/instrumented BG-024 (`SupabaseBackend` 20 s write timeout + 30 s `ConnectionPool`, one bounded `400 ms` transport retry on idempotent photo `POST`, `ReportQueue` `MIN_BACKOFF_MILLIS` 10 s backoff, `ReportSyncWorker.recentTimings` + `PhoneAcceptanceTest` phase breakdown & clean WorkManager completion wait, plus P4 `created_to_dispatch_seconds` in `real_fcm_phone.py`). Verified: backend 81/81, Edge pass, JVM 23/23, lint 0/37, emulator 41/41 across `AuthQueueFailureTest` (15/15 incl. 3 new photo-drop tests), `ReportSchedulingTest` (5/5), `QueueClaimRaceTest` (2/2), `OriginalRecoveryTest` (13/13), `UiParityAndCameraDraftTest` (6/6), release DEX audit pass. Larger physical-phone submission and push re-runs await phone reconnection.
- CP-51 [UI visual-fidelity upgrade](backend/UI_PARITY_INVENTORY.md): re-evaluated every screen against `MOBILECOMPUI\PinPoint-Citizen-App (1).pdf` (10 pages) and upgraded all 14 Compose UI files (pure white canvas + borderless `#F5F5F8` cards, protruding glowing violet bottom-bar Camera FAB, swipeable dark charcoal Active Report card with dark translucent priority pills + pager dots, 4-column Quick Report grid, editorial High-Priority & Community hero cards, full-bleed Map stage with floating chips/legend, 5-segment Report progress bar + 2-column amber-selected cards + Board 3.8 confirmation, overlapping Report Details hero sheet + supporter card + vertical timeline, dark SMS hero + chat bubble preview, centered Profile avatar + 3 stat boxes). Verified: backend 81/81, Edge pass, JVM 23/23, lint 0/37, emulator 81/81 across 13 suites, SIGKILL S1–S7 incl. S5 picker, release DEX audit pass, 13 screen captures visually verified.
- CP-50 [phone acceptance cohorts](backend/CP50_PHONE_ACCEPTANCE_COHORTS.md): Phase 4 on the physical TECNO with the fixture citizen; owner session restored; BG-024 open observation.
- CP-49 [phone validation and polish](backend/CP49_PHONE_VALIDATION_AND_POLISH.md): TECNO regression and SIGKILL matrix pass, real-screen fixes (BG-021 to BG-023), Phase 4 cohorts not run.
- CP-48 [independent review of CP-47](backend/CP47_INDEPENDENT_REVIEW.md): build reproduced; BG-020 (stale worker snapshot) fixed; phone task not run (no device).
- CP-47 [abandoned-upload cleanup, demos & restore harness](backend/ABANDONED_UPLOAD_CLEANUP_AND_DEMOS.md), [real Wi-Fi TUS smoke](backend/ORIGINAL_TUS_RECOVERY_VALIDATION.md), [UI parity implementation](backend/UI_PARITY_INVENTORY.md), and [owner operations & defense guide](backend/OWNER_OPERATIONS_AND_DEMO_GUIDE.md): real Wi-Fi HTTPS TUS interrupt/resume/hash smoke on `emulator-5556` (cellular TX 0, baseline restored), live 1C synthetic cleanup proof, 6-report Alangilan demo driver, isolated 1B restore harness (Blocked on owner $0 quote), Batch 2A UI parity + camera FileProvider + account-isolated drafts + policy-safe queue management + password recovery/change + SMS guide + unsynced logout dialog. Backend 81/81, JVM 14/14, lint 0 errors/38 warnings, emulator 73/73, release APK static audit pass.
- CP-46 [device regression and release checks](backend/BATCH3_DEVICE_AND_RELEASE_VALIDATION.md): phone SIGKILL matrix and suites pass, release APK static checks pass; acceptance cohorts not run.
- CP-45 [UI parity inventory](backend/UI_PARITY_INVENTORY.md) and [owner actions and field protocols](backend/OWNER_ACTIONS_AND_FIELD_PROTOCOLS.md): 57-screen inventory, reconciliations pending owner confirmation, no parity claimed.
- CP-44 [original TUS recovery](backend/ORIGINAL_TUS_RECOVERY_VALIDATION.md): 13-case matrix behind the unchanged release checks; BG-017 and BG-018 fixed; real phone Wi-Fi run pending.
- CP-43 [fixture-pair real FCM](backend/FIXTURE_PAIR_FCM_VALIDATION.md): separate citizen reporter/recipient and REST official actor, strict failed-observation exits, exact tray isolation and actual activity-destruction proof. See the final matrix and cleanup evidence there; the physical phone was untouched.
- CP-31 [migration replay](backend/MIGRATION_REPLAY_VALIDATION.md): all nine migrations replay from an empty PostgreSQL in CI; the live project's catalog is identical to the replayed final state (read-only check); fresh-project steps are written but **no fresh project was created**.
- CP-32 [security and failure paths](backend/SECURITY_FAILURE_PATH_VALIDATION.md): local policy/grant tests plus a hosted ordinary-client probe (49/49); open decision BG-005.
- CP-33 [auth/queue failure matrix](backend/AUTH_QUEUE_FAILURE_VALIDATION.md): 12 emulator cases against a local fake server; BG-006 to BG-008 fixed in Kotlin.
- CP-42 [test identities](backend/TEST_IDENTITIES.md): permanent fixture citizen/official, push tests only through them; workflow driver refuses shared accounts.
- CP-41 [approved policy deployment](backend/APPROVED_POLICY_DEPLOYMENT.md): 010/011 are live, with 18 rollback-only hosted checks, exact source/security comparison and preserved original business rows. No committed test push, phone or emulator access. Local backend suite now 69/69 plus Edge checks; history mapping documented, general db push still unsafe.
- CP-40 [process-death recovery](backend/PROCESS_DEATH_RECOVERY_VALIDATION.md): real SIGKILL lifecycle matrix on the emulator; BG-015 fixed; TUS resume Blocked.
- CP-39 [hosted-tooling failure safety](backend/HOSTED_TOOLING_FAILURE_SAFETY.md): identity-proven guarded cleanup, honest session outcomes, mocked/PGlite failure-path suite; BG-014.
- CP-38 [phone: real push, picker, live backend](backend/PHONE_PUSH_PICKER_VALIDATION.md): physical-phone observations; stack #11 to #15 merged.
- CP-35 to CP-37 [real FCM, native picker, recovery](backend/PUSH_PICKER_RECOVERY_VALIDATION.md): real FCM matrix on the emulator, real system photo-picker tests, feed and map recovery; BG-011 to BG-013 fixed.
- CP-34 [whole-app regression](backend/WHOLE_APP_BACKEND_REGRESSION.md): workflow, maps and permission checks on the emulator with independent verification; BG-009 and BG-010 (Windows tooling) fixed; hosted baseline restored (36 reports, 15 notices).

**Phone:** not queried or operated in CP-43. The user expects it to be available for the next heavy completion batch; use it when connected, unlocked and released for PinPoint. If absent or occupied, continue independent emulator/backend work and report the missing device accurately. Preserve session/data/settings, keep it powered, and target every device explicitly.

**Next tasks:** the user requested a heavy whole-app completion batch for Claude after CP-43: (1) original-photo TUS crash/resume, real Free-project restore, safe abandoned-upload cleanup and labelled demos; (2) approved PDF UI/action parity, public SMTP/signup and the real spare-phone SMS integration; (3) all pending phone/field/whole-app acceptance and release/owner handover. The detailed private completion assignment includes ordered work, evidence gates and a required return handoff for Codex. Existing product decisions stand. Provider verification, gateway hardware, outdoor measurements and actual SUS participants remain external inputs; continue independent work around them.

## Latest private-Storage validation — November 23

Current branch backend/storage-access-validation builds on backend/official-map-validation (draft PR #8). Four existing test-labelled objects passed 16/16 API access checks: seven allowed reads, nine expected denials; authorized official bytes matched owner SHA-256 values. Compressed community access and restricted originals matched deployed RLS. Full fingerprints of 36 reports/45 object rows/bucket settings were preserved; both test sessions returned local logout 204. No phone/fixtures/uploads/deletes or runtime/backend policy change. See [private Storage evidence](backend/PRIVATE_STORAGE_VALIDATION.md).

That Storage follow-up and its dependent PR stack are now merged; follow the current delivery section above.

## Latest backend validation — November 23

Current branch backend/official-map-validation builds on backend/map-stack-pagination (draft PR #7). The official-account hosted map passed 1/1 in 12.58 s with independent SQL ID/role expectations: six first-page markers, 13 after load-more, one Garbage marker, assigned stack/details with edit controls and other-barangay details without them. No business mutation control was activated. All 36 reports/15 notices/15 history rows retained full-row fingerprints; session/owned emulator registration cleanup verified. Test APK build and lint passed (0 errors/34 warnings). Runtime remains the prior 40f96668 candidate. No phone operations. See [official map evidence](backend/OFFICIAL_MAP_VALIDATION.md).

The private-photo authorization/integrity follow-up is now complete for the bounded matrix above. Phone remains unavailable until the user says otherwise. Prior branch/build paragraphs below are historical.

## Latest backend integration follow-up

The user is using the phone for other work and asked us to proceed without it. Leave the phone alone until it is available again; use an isolated emulator and independent backend tasks. No phone commands/settings/build changes were made in the BG-004 batch.

Current branch backend/map-stack-pagination builds on backend/map-hosted-navigation (draft PR #6). BG-004 removes the stacked-marker chooser cap of 25 and uses a bounded lazy list. Native 26/75-report selection, Cancel/reopen and existing map/form/notification checks passed 6/6; ten JVM tests/builds pass, lint 0 errors/34 warnings. Candidate APK SHA-256 40f966685f549045b533e59dbbb637cdaba674131f62939f7038404a203c094b was installed only on the owned emulator, which was then stopped. The phone has the earlier recorded build below; no fresh phone verification is claimed. See [stacked report evidence](backend/MAP_STACK_VALIDATION.md).

## Current backend continuation

The user separately authorized Claude to continue Codex backend implementation/integration during the handover. Claude completed hosted map navigation and BG-003; Codex has resumed backend ownership and verified the ordinary phone. This backend continuation is separate from the UI/UX work package below. Latest branch backend/map-hosted-navigation; draft PR #6 stacks on #5, #4 and #3, all currently unmerged.

Latest phone build SHA-256: 659bebd4b9736529b14abc706cefddf46bd36b3be317fa56dd6b36c07b0e1752. On the TECNO, all 11 chooser rows matched SQL, selected details and Back passed, and the existing citizen session/hosted report and notice state were preserved. Phone is left on PinPoint Home, awake with USB stay-awake 2, airplane off/Wi-Fi on. See [hosted map and phone evidence](backend/MAP_HOSTED_NAVIGATION_VALIDATION.md). Historical branch/build notes below describe earlier batches.

## Current ownership — updated user assignment, November 23

| Owner | Responsibility |
| --- | --- |
| User with Codex | All backend implementation and fixes: Auth, PostgreSQL/schema/RLS/RPCs, private Storage, Edge Functions, FCM server delivery, SMS adapter, security and integration contracts. Preserve the existing session, queue and upload behavior when coordinating mobile changes. |
| Claude | Complete the assigned backend/recovery/operations work and UI/integration/whole-app checks. Claude may fix backend defects within this completion batch; the former backend/UI split does not block implementation. Deliver reviewable PRs, evidence and a return handoff to Codex. |
| User/group | Own provider accounts/sender verification, spare gateway phone/SIM, device availability, outdoor participation and actual evaluation responses. The approved PDF, Alangilan, voting thresholds, fixture identities and scoped test authorization are already decided. |

Before UI restructuring or whole-app verification, Claude must read [CLAUDE_HANDOFF.md](CLAUDE_HANDOFF.md) and the latest private heavy-completion assignment. The current assignment takes precedence over the older ownership wording below. Fix discovered backend defects in reviewable branches with exact redacted errors and regression evidence; include all changed integration contracts in the return handoff.

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
| PR review | PRs #1–#9 are merged; CP-30 is verified in PR #10. Inspect its current merge state. Whole-app acceptance remains separate. |
| UI/UX and whole-app acceptance | Claude must mirror the supplied design and verify every user flow against the backend; use CLAUDE_HANDOFF.md and retain failed/blocked cases |

## Rules that keep this safe

- Secrets (service-role key, FCM private key, SMS gateway secret, account passwords) live only in Supabase Edge secrets or private files, never in the app or Git.
- Authorization is enforced in PostgreSQL functions and policies; a hidden button is not security.
- Do not describe unmeasured acceptance targets as met.
- Whole-app acceptance requires UI evidence and backend/integration evidence. A successful build or a visually matching screen alone does not close the handoff.
