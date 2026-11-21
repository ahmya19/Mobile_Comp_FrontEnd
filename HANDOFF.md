# PinPoint team handoff

One-page start for teammates. Details: [backend/README.md](backend/README.md). Task status: [CHECKPOINTS.md](CHECKPOINTS.md). Evidence: [backend/VERIFICATION.md](backend/VERIFICATION.md).

**Budget rule: Supabase Free and free Firebase FCM only. Never enable Blaze or attach billing.**

## What exists

Android app (Kotlin/Compose) on a Supabase Free backend: sign-in with email confirmation code, reports with photos and an offline queue, community feed and map pins, status updates by assigned officials, notifications with push, votes, filters and pagination. An SMS receiver is deployed but locked until a gateway is configured.

## Build and run

1. Use JDK 17 and the Android SDK (platform 36). Android Studio is optional.
2. Create `local.properties` in the project root (it is git-ignored): `sdk.dir=...`, `SUPABASE_URL=https://jaysxjjdvaitgrbqjlhe.supabase.co`, `SUPABASE_PUBLISHABLE_KEY=` (Supabase dashboard, Settings, API Keys). Never put secret or service-role keys here.
3. Put the app's `google-services.json` (from the existing Firebase project) in `app/`. It is git-ignored.
4. Build: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. The APK is `app/build/outputs/apk/debug/app-debug.apk`.
5. Backend tests: `cd backend; npm install; npm test` (17 tests) and `npm run check:edge`.

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
| PR review | Review and merge [draft PR #1](https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1) |

## Rules that keep this safe

- Secrets (service-role key, FCM private key, SMS gateway secret, account passwords) live only in Supabase Edge secrets or private files, never in the app or Git.
- Authorization is enforced in PostgreSQL functions and policies; a hidden button is not security.
- Do not describe unmeasured acceptance targets as met.
