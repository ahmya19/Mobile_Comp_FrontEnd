# CP-16 emulator rehearsal

Use one Google Play emulator to exercise the citizen and official accounts sequentially. This avoids using a connected physical phone and keeps memory requirements below a two-emulator setup. The runner requires an explicit emulator serial and rejects physical-device serials.

## Prerequisites

- The team's Supabase/Firebase client configuration is present locally, as described in [setup](README.md).
- Both demo accounts are confirmed; the official is assigned to Alangilan through the trusted backend.
- The emulator has Internet access and Google Play Services. Verify that another project is not using it before installing or running the test app.
- JDK 17, Android SDK platform 36 and the existing Gradle wrapper are available.

Build the debug app and instrumentation APK:

~~~powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1
~~~

Keep the account fixture outside Git. The runner accepts this JSON shape; substitute existing test-account credentials locally:

~~~json
{
  "citizen": { "email": "CITIZEN_TEST_EMAIL", "password": "CITIZEN_TEST_PASSWORD" },
  "official": { "email": "OFFICIAL_TEST_EMAIL", "password": "OFFICIAL_TEST_PASSWORD" }
}
~~~

Run from the repository root, choosing the emulator serial explicitly:

~~~powershell
.\backend\scripts\run-emulator-workflow.ps1 `
  -EmulatorSerial emulator-5554 `
  -CredentialFile 'C:\private\pinpoint-demo-accounts.json' `
  -Install
~~~

The default full run uses three instrumentation sessions, retaining the saved report ID and authenticated session between steps. This keeps each UI test short and checks restoration across app restarts. Credentials are quoted for the Android shell; output and failure diagnostics redact demo emails and passwords. Malformed credential JSON produces a generic error without echoing its contents. The script never changes Wi-Fi/cellular settings, uninstalls an app, or targets a physical phone.

## What the rehearsal checks

1. Citizen signs out/in through the UI, fills the report form, selects Water Interruption, supplies test coordinates and chooses a JPEG. The system picker result is stubbed with a generated test image; the form, Auth, queue, private uploads and SQL are real. The test waits for server sync, opens that report, checks the displayed photo and verifies that citizen controls cannot update its status.
2. Official signs out/in through the UI, finds that same report in the community feed, opens the assigned-barangay controls and changes Pending to Under Review. The test checks the persisted report status and its history.
3. Citizen signs out/in again, opens the new notification and verifies the correct report/description/status. A hosted read confirms that opening it persisted the read flag and that the status update created one notification.

Use -Phase citizen, -Phase official or -Phase review to diagnose a particular step. Later steps require the earlier step's saved report fixture. Re-running full creates a new clearly labeled prototype report; it does not delete earlier reports.

## Scope of the result

This is a sequential two-account emulator rehearsal, not two simultaneously signed-in physical phones. Existing separate push checks cover foreground/background FCM and notification taps. The emulator rehearsal checks notification history after the citizen signs back in. It does not establish actual GPS accuracy, cellular handoff/loss rates, carrier SMS delivery, a native gallery provider's permissions, or user-study scores.

Results and limitations are recorded in [verification](VERIFICATION.md) and [checkpoints](../CHECKPOINTS.md). Physical-phone testing remains deferred until the phones are available.
