# PinPoint backend setup and integration

PinPoint uses Firebase Authentication, Cloud Firestore, Cloud Storage, Cloud Functions and Firebase Cloud Messaging. The existing Kotlin and Compose app stays in place. This first backend batch connects its screens to real repositories and adds the backend authorization rules.

For per-task progress, completion criteria and the next work batch, see [project checkpoints](../CHECKPOINTS.md).

## Why this backend

| Approach | Advantages for this project | Tradeoffs |
| --- | --- | --- |
| Firebase with Firestore | Native Android SDKs, offline document cache, live listeners, authentication, resumable Storage SDK uploads and FCM; matches the project proposal | No SQL joins; queries need indexes; reads and storage have usage costs; Storage and deployed Functions require Blaze billing |
| Supabase with PostgreSQL | SQL relationships, constraints, reporting queries, authentication and Storage; no local server required | Durable Android offline sync must be designed separately; mobile push requires an additional delivery service; changes the proposal's chosen stack |
| Custom Kotlin Ktor or Node API with PostgreSQL | Full control over APIs, SQL schema, hosting and business logic | More deployment, authentication, upload, monitoring and synchronization work for the team |
| Custom API with MySQL | Familiar relational database and broad hosting support | Still requires an API server and separate authentication, photo storage, offline sync and push delivery |

Firebase is the best fit here because offline reporting, network switching and push delivery are part of the proposal, and the laptop should not need to run a database and application server during normal development. PostgreSQL would be the stronger choice if complex SQL reporting became the main requirement.

## How the frontend communicates

```text
Compose screen -> PinPointViewModel -> PinPointRepository -> Firebase Android SDK
                                                              |
                              HTTPS + signed-in user's ID token
                                                              |
                  Authentication / Firestore Rules / Storage Rules
                                      |               |
                                report documents    photo bytes
                                      |
                             live snapshot listener
                                      |
                         repository -> state -> Compose screen

Official's status change -> callable Cloud Function -> role + barangay check
                           -> Firestore transaction:
                              report + history + notification
                           -> notification trigger -> FCM -> citizen's phone
```

The SDK is an HTTP client with Firebase-specific features. It handles authentication tokens and document synchronization. The phone does not connect using a database password. Rules validate each document request on the server. Functions perform privileged operations using the server-only Admin SDK.

Example: Submit Report validates the form, compresses the chosen photo, uploads it to Storage, then saves the report document to Firestore. The UI navigates to success only after the server acknowledges the document. A live listener updates My Reports without requiring a manual refresh. If a save acknowledgement is uncertain, retry uses the same report ID while that draft remains open.

## What has been implemented

- Email/password registration, sign-in, Firebase session restoration and sign-out.
- Editable display name and private profile documents.
- Report categories, description, text location, barangay and optional coordinate pair.
- JPEG compression to at most 256,000 bytes; authenticated photo upload and retrieval without public download-token URLs.
- Report submission, current user's latest 100 reports, community's latest 100 reports and report details by ID.
- Home cards backed by the loaded report lists; these are recent-report summaries, not full-database analytics.
- Notification history with read/unread state.
- Server-assigned official custom claims; status controls in the same app.
- Official status function with barangay authorization, transition validation, atomic history and notification records, and idempotent no-op retries.
- FCM token registration and notification handling. Actual delivery still needs a configured cloud project and a physical device or Google Play emulator.
- Firestore and Storage rules, report indexes, local emulator configuration, backend authorization tests and input validation tests.
- Gradle wrapper scripts and JAR, which were missing from the uploaded repository.

## Cloud project created for this team

- Display name: PinPoint Citizen Group 2.
- Project ID: `pinpoint-citizen-group2-2026`.
- Android package: `com.example.pinpoint`.
- Firestore: default Standard database in `asia-southeast1` (Singapore).
- Functions region in code: `asia-southeast1`.
- Android configuration: `app/google-services.json`, downloaded locally and ignored by Git.
- Firestore rules and indexes have been deployed.
- Authentication initialization, Storage bucket provisioning, deployed Functions and live push still need cloud setup. An API attempt to initialize Identity Platform returned `BILLING_NOT_ENABLED`; this does not mean that ordinary Firebase email/password authentication always requires a paid plan. Initialize Firebase Authentication through the console and enable Email/Password.

Console: <https://console.firebase.google.com/project/pinpoint-citizen-group2-2026/overview>

## Tools and dependencies

- IntelliJ IDEA and the existing Android SDK; Android Studio is not required to edit or run Gradle commands.
- JDK 17 for the Android build. On this machine: `C:\Program Files\Java\jdk-17.0.17+10`.
- Gradle 8.13 through the included wrapper.
- Firebase Android BoM 34.7.0: Auth, Firestore, Storage, Functions and Messaging main modules, not discontinued KTX artifacts. This version was selected to work with the existing Kotlin 2.0.21 toolchain and was build-verified.
- Kotlin coroutines Play Services 1.9.0 for suspending Firebase Tasks; Lifecycle Compose/ViewModel 2.8.7 for lifecycle-aware screen state.
- Node.js 22 for deployed Cloud Functions; Firebase CLI for rules, indexes, emulators and deployment.
- Java 21 or newer for the current Firebase emulators. This machine also has JDK 24, which was used for emulator tests.
- Server packages: firebase-admin 14.5.1 and firebase-functions 7.4.0, with lockfiles.
- Test packages: Firebase JS 13.4.0 and rules-unit-testing 6.0.0. A grpc-js override keeps the test dependencies on a patched version. Both package audits passed after installation.

## Initial setup and cloud activation

Run commands from PowerShell in `C:\Users\USER\project\MobileConputing`.

1. Open the Firebase console in the account used by the Firebase CLI. Initialize Authentication with Get started, then enable Email/Password under sign-in providers. Use test accounts for this prototype.
2. For photos and deployed Functions, upgrade this project to Blaze yourself and choose budget alerts. This work has not attached a billing account or accepted a billing agreement for you.
3. Create the default Storage bucket in the console. Prefer the same region as Firestore when available. Start with restricted access; deploy the supplied Storage rules.
4. Download the Android configuration again after Storage setup if needed, keeping its name exactly `google-services.json` in `app/`. This config contains project identifiers, not an Admin SDK private key.
5. Install the backend dependencies and deploy from the project directory:

```powershell
npm ci --prefix backend/functions
npm ci --prefix backend
firebase deploy --only "firestore:rules,firestore:indexes,storage,functions" --project pinpoint-citizen-group2-2026
```

6. Set IntelliJ's Gradle JVM to JDK 17. For terminal builds on this machine:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.17+10'
$env:ANDROID_HOME = 'C:\Users\USER\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The APK is generated in `app\build\outputs\apk\debug\app-debug.apk`. Install it on the team's Android device using IntelliJ or `adb install -r` with that APK path. No phone was connected during this batch, so on-device behavior and live FCM delivery have not been verified.

7. Register a citizen account in the app, create a report without a photo first, confirm it in Firestore and My Reports, then test a photo report after the Storage rules are deployed.

8. To grant a test official role, use trusted backend Application Default Credentials outside the Android app. The helper accepts an explicit project, UID and barangay:

```powershell
npm --prefix backend/functions run grant-official -- pinpoint-citizen-group2-2026 USER_UID "Alangilan"
```

The helper uses `applicationDefault()` and does not read Firebase CLI login tokens. Configure ADC with your trusted Google Cloud credentials before running it. Never put service account keys in `app/`, source control, or an APK. The official must sign out and sign in again after a claim change. The claim's barangay must exactly match the report's barangay.

If the Google Cloud CLI is installed, `gcloud auth application-default login` opens the account sign-in you complete yourself. Use a project owner account for this prototype. Firebase CLI login and Application Default Credentials are separate logins.

## Local emulator workflow without billing

Backend tests use the isolated `demo-pinpoint` project and do not create cloud reports or real user accounts.

```powershell
npm ci --prefix backend/functions
npm ci --prefix backend
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:FUNCTIONS_DISCOVERY_TIMEOUT = '60'
firebase emulators:exec --project demo-pinpoint --only "auth,firestore,storage,functions" "npm --prefix backend test"
```

For interactive Android testing, start the same emulators with `emulators:start` instead of `emulators:exec`, then build an emulator APK in another terminal:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.17+10'
$env:ANDROID_HOME = 'C:\Users\USER\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug -PfirebaseEmulators=true
```

The Android emulator uses `10.0.2.2` to reach the computer. For a physical phone, use `adb reverse` for ports 9099, 8080, 9199 and 5001, then build with `-PfirebaseEmulatorHost=127.0.0.1`. Emulator mode uses a separately named Firebase app with the demo project, even when the real `google-services.json` is present. Emulators are forbidden in release builds. FCM push is skipped in emulator mode; notification history still works.

To use USB port forwarding on a physical Android phone with USB debugging already enabled:

```powershell
adb reverse tcp:9099 tcp:9099
adb reverse tcp:8080 tcp:8080
adb reverse tcp:9199 tcp:9199
adb reverse tcp:5001 tcp:5001
```

## Database collections and access

| Path | Main fields | Who can access |
| --- | --- | --- |
| `users/{uid}` | displayName, updatedAt | Only that account; role fields are rejected |
| `users/{uid}/devices/{installationId}` | token, updatedAt | Only that account; backend uses tokens for FCM |
| `users/{uid}/notifications/{eventId}` | reportId, title, message, read, createdAt | Owner reads and marks read; backend creates |
| `reports/{reportId}` | ownerId, category, description, location, barangay, coordinates, photoPath, status, priority, source, createdAt, updatedAt | Signed-in prototype accounts can read; citizen creates own report with Pending/Low defaults; direct updates are denied |
| `reports/{reportId}/history/{version}` | previousStatus, status, priority, changedBy, createdAt | Signed-in accounts read; backend writes |
| Storage `report-images/{uid}/{reportId}/photo.jpg` | JPEG bytes | Owner uploads before report creation; authenticated users read published report evidence; published evidence cannot be overwritten |

No report document contains an email, phone number, password or role. Coordinates are a Firestore GeoPoint when supplied, otherwise null. Do not use real residents' personal data during prototype testing. A Firebase client API key identifies the project; Rules and user tokens enforce access. Admin credentials stay on the backend.

Status path: Pending -> Under Review -> In Progress -> Resolved. Rejection and reopening are supported through allowed transitions in `backend/functions/src/index.js`. Citizens cannot set status or priority, including by bypassing the screen and calling Firebase directly.

## Responsibilities and remaining batches

| Backend and integration owner | Mobile frontend owner | Shared testing and documentation |
| --- | --- | --- |
| Rules, indexes, document contracts, official claims, server functions, notification pipeline, SMS validation and gateway endpoint | UI layout, map SDK and pin picker, GPS permissions, durable local report/photo queue, network-aware upload scheduling | Connect UI actions to repositories; confirm barangay names and categories; run acceptance tests; record proposal metrics |

The minimal UI bindings in this batch make the backend reviewable. Coordinate further map and offline queue changes with the mobile developer before duplicating that work.

Still to implement: a durable offline report/photo queue, process-death recovery, persisted upload session URIs, Wi-Fi versus cellular photo variants, automatic GPS/map pin selection, map filters, community voting and vote-derived urgency, SMS gateway/parser with authenticated and replay-protected ingress, and complete report pagination. The current photo path uses bounded compressed uploads on every connection, not full-resolution Wi-Fi variants. The Storage SDK may resume an active task, but this batch does not claim the proposal's cross-network or process-restart resume target.

Firestore caches documents on Android by default. That helps cached reads and document synchronization; it does not provide a complete offline queue for this app's multi-step photo submission. New report submission currently verifies server availability before uploading and does not show offline submissions as completed.

Next integration batch after cloud activation: run the citizen/official/photo/notification flow on two test accounts, then agree the durable queue contract with the mobile developer before implementing offline sync and SMS.

## Troubleshooting with exact errors

Keep the full exception class, error message, screen/action and whether you used cloud or emulators. The app surfaces the received exception message rather than replacing it with a generic success message. Never paste passwords, service account JSON, ID tokens or FCM registration tokens.

- `CONFIGURATION_NOT_FOUND` or `OPERATION_NOT_ALLOWED`: initialize Firebase Authentication and enable Email/Password for this project.
- `BILLING_NOT_ENABLED`: the attempted cloud service or initialization path requires billing. Complete the console setup or use local emulators; do not weaken Rules to work around it.
- `PERMISSION_DENIED`: confirm sign-in, deployed Rules, ownership, required fields and official claims. Do not switch the database to test mode.
- `FAILED_PRECONDITION` with an index link: deploy the checked-in Firestore indexes and wait until the index is ready.
- `FirebaseFunctionsException: NOT_FOUND`: deploy `updateReportStatus` in `asia-southeast1` and confirm the app points at the same project.
- A callable error about another barangay: check the official's server-assigned barangay and the report's barangay spelling.
- A Storage bucket-not-found error: create the bucket, refresh the Android config if necessary and deploy Storage rules.
- Missing Java/SDK build errors: use JDK 17 for Android and configure the Android SDK path; JDK 24 is used separately for the emulators here.

## Verification completed in this batch

- Android cloud-mode and emulator-mode debug APKs build successfully.
- All 4 Android JVM tests pass, including 3 report validation tests.
- Android lint completes with no errors; version-update, unused-resource and existing icon warnings remain.
- All 10 backend tests pass against Auth, Firestore, Storage and Functions emulators. Expected permission-denied messages in the test log are assertions that the rules reject malicious requests.
- Both npm package audits report zero known vulnerabilities with the checked-in lockfiles.
- Firestore rules and indexes deployed successfully to the new cloud project.
- Live sign-in, photo submission and FCM delivery on a phone remain unverified; no Android device was connected, and cloud authentication/Storage/Functions setup is incomplete.

## Primary documentation

- [Firebase Android setup and current main-module guidance](https://firebase.google.com/docs/android/setup)
- [Firestore offline behavior](https://firebase.google.com/docs/firestore/manage-data/enable-offline)
- [Storage Android setup and Blaze requirement](https://firebase.google.com/docs/storage/android/start)
- [Android upload tasks and resumable uploads](https://firebase.google.com/docs/storage/android/upload-files)
- [Custom claims and trusted role assignment](https://firebase.google.com/docs/auth/admin/custom-claims)
- [Cloud Functions runtimes](https://firebase.google.com/docs/functions/manage-functions)
- [Security Rules emulator tests](https://firebase.google.com/docs/rules/unit-tests)
- [Supabase Kotlin alternative](https://supabase.com/docs/guides/getting-started/quickstarts/kotlin)
