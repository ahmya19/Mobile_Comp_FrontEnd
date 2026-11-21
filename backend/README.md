# PinPoint backend and Android setup

The active backend is **Supabase Free**: Auth, PostgreSQL, private Storage and Edge Functions. Firebase supplies **FCM only**. The user selected this approach because the project has no budget. Do not attach billing, enable Blaze, or deploy Firebase Storage/Functions. The earlier Firebase implementation remains in Git commit 70d84e9 for reference.

Progress and acceptance conditions: [CHECKPOINTS.md](../CHECKPOINTS.md). The existing Kotlin/Compose frontend is preserved.

## How the app communicates

~~~mermaid
flowchart TD
  Screen[Compose screens] --> VM[ViewModel and repository]
  VM --> Queue[Private SQLite queue and photo files]
  Queue --> Worker[WorkManager]
  VM -->|HTTPS plus user JWT| Auth[Supabase Auth]
  Worker -->|HTTPS plus user JWT| Storage[Private Storage buckets]
  Worker -->|submit_report RPC| SQL[PostgreSQL functions and RLS]
  VM -->|PostgREST queries and RPCs| SQL
  SQL --> Outbox[Status history, notification and push outbox]
  VM -->|Assigned official JWT| Edge[deliver-push Edge Function]
  Edge --> Outbox
  Edge -->|Restricted server credential| FCM[Firebase Cloud Messaging]
  FCM --> Screen
~~~

A screen calls the ViewModel, which calls the repository. OkHttp sends HTTPS requests with a public project key and the signed-in user's short-lived access token. The app never receives a database password, service-role key or FCM private key. PostgreSQL functions validate business rules; row-level security controls reads and Storage access. Hiding a button is not authorization.

Submission first saves a stable UUID, account/project identity, immutable fields and private photo copies on the phone. The UI says "saved on this device" while pending. A worker uploads the compressed JPEG, then calls submit_report. Only a database acknowledgement marks it Synced. Retrying the same UUID and content creates no duplicate report.

Report feeds use cursor pages of 25; notifications use 50. Both order by created_at and id. Reports poll every 15 seconds; details and notifications every 10 seconds. Notification refresh walks all loaded pages again to keep a continuous window after new inserts; loading three pages means up to three reads per refresh. Failed requests retain loaded items and the retry cursor. Failed read acknowledgements restore the unread marker. This is polling, not a Realtime socket. Home counts summarize loaded/filtered rows, not the entire database.

## Projects and cost boundary

| Component | Project |
| --- | --- |
| Supabase project | PinPoint Citizen Group 2 |
| Organization and plan | DreiwanabeTexh's Org, Free |
| Project ref | jaysxjjdvaitgrbqjlhe |
| Region | Singapore, ap-southeast-1 |
| API URL | https://jaysxjjdvaitgrbqjlhe.supabase.co |
| Firebase project, FCM only | pinpoint-citizen-group2-2026 |
| Android package | com.example.pinpoint |

[Supabase dashboard](https://supabase.com/dashboard/project/jaysxjjdvaitgrbqjlhe) · [Firebase console](https://console.firebase.google.com/project/pinpoint-citizen-group2-2026/overview)

Free-plan quotas still apply. Supabase currently lists 500 MB database storage, 1 GB file storage, 5 GB egress and 500,000 Edge Function invocations; Free projects can pause after inactivity. Monitor the dashboard and resume a paused project before demonstrations. Originals consume the file quota faster than compressed reports. No billing account was attached by this work. See [Supabase pricing](https://supabase.com/pricing) and [Firebase pricing](https://firebase.google.com/pricing).

## Build the Android app

Use JDK 17, the Android SDK with platform 36, and the included Gradle 8.13 wrapper. Android Studio is optional if the SDK is already installed.

1. In the project root, create the ignored local.properties file. Copy the project's publishable key from Supabase Settings → API Keys. The project URL and publishable key are client configuration; secret/service-role keys must never go here.
2. Download this Android application's google-services.json from the existing Firebase project and place it in app/google-services.json. This identifies the FCM application; it is ignored by Git.
3. Build and install on an emulator with Google Play Services or an Android device.

~~~properties
sdk.dir=C:/Users/USER/AppData/Local/Android/Sdk
SUPABASE_URL=https://jaysxjjdvaitgrbqjlhe.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
~~~

~~~powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17.0.17+10'
$env:ANDROID_HOME='C:\Users\USER\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

The APK is app/build/outputs/apk/debug/app-debug.apk. A missing Supabase configuration opens a setup screen. A release build requires both cloud configurations. Grant notification permission to test push. Request location only when using the GPS button; map pin selection works without GPS permission.

Main dependencies: OkHttp 4.12, Gson 2.11, WorkManager 2.9.1, Lifecycle 2.8.7, coroutines Play Services 1.9, MapLibre Native 11.8, Play Services Location 21.3, and Firebase Messaging through BoM 34.7. Versions are pinned and build-verified with the existing Kotlin toolchain.

## Database and Edge Functions

The deployed SQL is versioned under supabase/migrations. On a new project, apply these files in order through the Supabase migration workflow. Do not reapply creation migrations to an already provisioned database.

| File | Responsibility |
| --- | --- |
| 202611210001_pinpoint.sql | Profiles, reports, roles, votes, history, notices, devices, compressed photo policies |
| 202611210002_delivery.sql | SMS ingestion and deduplication; leased push outbox |
| 202611210003_indexes.sql | Foreign-key indexes |
| 202611210004_originals.sql | Original photo bucket, private reads and owner attachment |
| 202611210005_push_retries.sql | Assigned officials can find pending deliveries |
| 202611210006_push_dispatch.sql | Hosted cron dispatcher, Vault configuration and provider retry backoff |
| 202611210007_dispatch_acl.sql | Initial platform ACL attempt and dispatcher credential rotation |
| 202611210008_dispatch_nonce.sql | Replaces reusable dispatch credentials with expiring one-use nonces |
| 202611210009_push_recipient.sql | Binds data-only push payloads to their recipient account |

Deploy sms-report and deliver-push from supabase/functions. Their gateway JWT switch is false because both functions authenticate requests in their own bodies: HMAC for SMS, Supabase user JWT plus assigned-official checks for push. Never remove those checks.

FCM_SERVICE_ACCOUNT is configured only in Supabase Edge secrets. Its Google service account has a custom role containing only cloudmessaging.messages.create. The provisioning key file was removed after upload. Never copy it into the APK, local.properties or Git. Device-token registration is retried with connected WorkManager jobs. Data-only push includes the intended user ID; the app checks the current account before posting a notification, preventing a delayed message for a previous account from appearing after logout/switching.

Official status changes atomically commit the report, history, notification and outbox. A WorkManager job calls deliver-push and retries failures. A hosted database cron job also checks due outbox items every ten seconds, calling Edge only when needed. It uses random one-use nonces, valid for 60 seconds, whose hashes stay in a private RLS-enabled table. The server validates and consumes each nonce. There is no reusable job secret in request headers. Provider failures back off and expired leases can be reclaimed. One week of this project's routine cron logs is retained to bound database use. Provider acceptance does not by itself prove phone receipt; both cloud acceptance and Android receipt/tap were tested separately.

For a new deployment, after migration and function deployment, put its API URL and publishable key in Supabase Vault as pinpoint_project_url and pinpoint_publishable_key. These are client configuration values, not server credentials. The existing project is configured. The cron mechanism follows the provider's [scheduled function workflow](https://supabase.com/docs/guides/functions/schedule-functions). Never expose the private, net or vault schemas through PostgREST. Hosted extension grants could not be narrowed by the project postgres role; one-use nonces keep reusable secrets out of pg_net's platform-managed queue.

## Accounts and roles

Android confirmation-code and resend screens are implemented and tested. The user chose to continue with demo accounts; provider setup and real email receipt remain pending. [CP-08 email setup and evidence](EMAIL_CONFIRMATION.md) includes the prepared signup template and free SMTP recommendation. Publish that template before real code-based registration testing.

Two disposable prototype accounts exist, one citizen and one official assigned to Alangilan. Credentials are kept outside Git in this machine's private test fixtures; they are not included in handoff files. These accounts were created with dashboard auto-confirm, so they do not prove public confirmation email delivery. Supabase's default email service sends only to organization-team addresses and currently allows two emails per hour. Registration emails for other addresses need custom SMTP; that setup remains CP-08. Existing confirmed accounts work for the emulator rehearsal without it. See [Supabase SMTP requirements](https://supabase.com/docs/guides/auth/auth-smtp).

Assign an official only through a trusted administrator using the authenticated user's UUID:

~~~sql
insert into private.officials(user_id, barangay)
values ('REPLACE_WITH_AUTH_USER_UUID', 'Approved barangay')
on conflict(user_id) do update set barangay=excluded.barangay;
~~~

The client cannot write this table or grant roles through profile metadata. Status transitions and barangay assignment are enforced in SQL. Voting uses one row per report/account. Current provisional urgency thresholds are 3 votes for Medium and 10 for High; voting cannot reduce an official assessment. Confirm these thresholds with the group before evaluation.

## Photos, offline reports and maps

Compressed JPEGs are capped at 256,000 bytes on every connection. An optional original, at most 10 MiB and JPEG/PNG/WebP, is saved privately and transferred only on **unmetered Wi-Fi**. DNS and sockets bind to that Wi-Fi network so a handoff cannot silently move original bytes to cellular. TUS upload URLs persist in SQLite; HEAD retrieves the server's offset after a restart. Chunks are 6 MiB. Local files are deleted only after cloud acknowledgement. "Retry uploads" retries failed queue entries.

Originals can be read by the report owner and its assigned officials. Community users see the compressed report image. "View original over Wi-Fi" also uses the Wi-Fi-only transport. Neither bucket is public; published evidence cannot be overwritten by the app.

MapLibre renders visible OpenStreetMap raster tiles with attribution, an identifying User-Agent and caching. No bulk/offline tile download is implemented. Follow the [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/) for demonstrations and any later release. Coordinate validation is server-side. Actual 20 m GPS accuracy requires an outdoor phone test.

More detail: [offline synchronization contract](contracts/offline-sync.md), [resumable upload protocol](https://supabase.com/docs/guides/storage/uploads/resumable-uploads), [MapLibre HTTP configuration](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.module.http/-http-request-util/index.html).

## SMS gateway contract

No working gateway phone/SIM is available. sms-report is deployed but fails closed until SMS_GATEWAY_ID and SMS_GATEWAY_SECRET are configured. The secret must be at least 32 characters and stay only on the gateway and server. Three rounded Test Barangay centroids remain as simulation placeholders; replace them with the group's approved list before a real SMS test.

Body: {"text":"PIN ROAD;Alangilan;Large pothole near school gate."}

Category codes: ROAD, LIGHT, FLOOD, GARBAGE, WATER, OTHER. Messages must fit one GSM-7 SMS of at most 160 septets; extension characters count twice. The gateway sends POST with x-gateway-id, x-message-id, x-timestamp (Unix seconds), and x-signature (64-character hex HMAC-SHA256). Sign the exact bytes described by supabase/functions/_shared/sms.mjs; do not reserialize between signing and sending. Timestamp tolerance is five minutes, message IDs are unique per gateway, and different content under an existing ID is rejected.

### Candidate gateway: SMS Gateway for Android (adapter deployed; real gateway pending)

sms-report also understands the webhook of the open-source SMS Gateway for Android (docs.sms-gate.app), documented as free in its Local, Cloud and Private modes. A spare Android phone with a SIM runs the app. Selecting it is still the group's decision.

- Set SMS_GATEWAY_FORMAT=sms-gate, SMS_GATEWAY_ID to the phone's deviceId (shown in the app) and SMS_GATEWAY_SECRET to the webhook Signing Key (at least 32 characters). Store these only as Supabase Edge secrets. Leave SMS_GATEWAY_FORMAT unset to keep the original signed format.
- In the app, register a webhook for the sms:received event pointing to the deployed sms-report URL.
- Verification follows the vendor's documented scheme: lowercase-hex HMAC-SHA256 over the raw body immediately followed by the X-Timestamp value, five-minute tolerance. Only the event type, deviceId and payload.messageId/message are used; the sender's phone number is not stored.
- A webhook retry reuses payload.messageId, and the idempotency hash covers only messageId and message text, so a retry creates no duplicate report.
- Install caveat observed November 21: the GitHub APK (v1.79.0, checksum verified) was blocked on a TECNO phone by Google Play Protect ("App blocked to protect your device"; no install-anyway option) because it requests SMS access outside the Play Store. Plan for a spare phone where this is permitted, or a gateway app available from the Play Store.
- Local payload tests and the recorded eight-case hosted simulation passed; temporary secrets and test report were removed afterwards (see VERIFICATION.md). The adapter is deployed, but has not been run against the real gateway app or a carrier. Its documented signing details still need confirmation with a real webhook.

Parser and server tests do not prove carrier delivery. Hardware integration and the 90% / two-minute proposal target remain pending.

## Verification and limitations

For the sequential citizen/official UI rehearsal on one emulator, see [emulator testing](EMULATOR_TESTING.md). Physical-phone testing can remain deferred while this workflow runs.

~~~powershell
cd backend
npm ci
npm test
npm run check:edge
~~~

Backend tests run the actual PostgreSQL migrations in PGlite with simulated Auth/Storage schemas. They test business constraints and access control; they do not replace testing the hosted PostgREST, Storage or Google services. Android checkpoint instrumentation is in BackendCheckpointTest.kt; its phases require explicit test credentials passed privately by the host, not committed passwords.

The latest evidence, exact failed checks and next actions live in CHECKPOINTS.md and backend/VERIFICATION.md. Do not equate successful compilation with completion of device, SMS or user-study targets.

For the citizen-form → official status UI → citizen notification workflow, see [emulator rehearsal](EMULATOR_TESTING.md). The runner defaults to emulators; phone use needs the user's approval and the explicit AllowPhysicalDevice switch.

Supabase advisories flag authenticated SECURITY DEFINER functions. These RPCs are intentional and use an empty search_path with explicit owner/role checks and restricted grants. Review every new RPC against these constraints. [Advisor reference](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable).

The table inventory also warns that four private-schema tables have RLS disabled. Live has_schema_privilege and has_table_privilege checks confirmed anon and authenticated cannot use the private schema or read officials; API probes rejected the private, net and vault schemas. SQL tests deny direct access. All exposed public tables have RLS. The advisor also flags disabled leaked-password protection, which requires Pro or above and remains outside this Free project's budget. See [password security](https://supabase.com/docs/guides/auth/password-security). Review these boundaries before changing schema exposure or grants.
