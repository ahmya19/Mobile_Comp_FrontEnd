# PinPoint project checkpoints

Updated November 21, 2026 (Asia/Manila).

## Current checkpoint

The free cloud backend is deployed and connected to the existing Kotlin frontend. Offline photo sync, resumable Wi-Fi originals, feeds, filters, votes, GPS/map integration, status history and push delivery are implemented. Resumed from Claude's pushed commit 3b66064. Emulator checks now cover notification pagination with 123 local notices and long-press form pin placement. Three phone offline-sync samples are recorded; the remaining phone measurements, real SMS and user study are pending. Further phone or hosted-data changes require the user's approval.

Code is shared in [draft PR #1](https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1) on backend/pinpoint-free. The draft is ready for team testing and review.

**Budget boundary: Supabase Free and free Firebase FCM only. No Blaze or billing account.** The user approved this provider change and authorized the mobile features as well as backend work. The original Firebase baseline is saved in commit 70d84e9; it is no longer the active architecture.

Statuses: **Done** = stated setup/implementation condition verified; **Prototype verified** = functional checks passed, with acceptance measurements still separate; **Partial** = listed work remains; **External** = needs a real device, gateway or participant.

## Per-task checkpoints

| ID | Task | Status | Evidence / remaining condition |
| --- | --- | --- | --- |
| CP-01 | Clone existing frontend | Done | Repository is at C:\Users\USER\project\MobileConputing. Existing Kotlin/Compose screens retained. |
| CP-02 | Confirm role and repository access | Done | User owns backend and authorized mobile features. GitHub account has push permission; main remains the uploaded baseline. |
| CP-03 | Choose and provision backend | Done | User selected Supabase Free in DreiwanabeTexh's Org. Project jaysxjjdvaitgrbqjlhe is active in Singapore. Firebase retained for FCM. |
| CP-04 | Configure authentication | Done | Hosted email/password sign-in works for citizen and official fixtures; app restores an encrypted session after restart. Public signup email delivery remains CP-08. |
| CP-05 | Provision private photo storage | Done | Compressed and original buckets deployed with limits and owner/official policies. No paid Firebase bucket. Authenticated cloud uploads and reads passed. |
| CP-06 | Deploy database and server logic | Done | Nine SQL migrations and two Edge Functions deployed. Mutations use protected RPCs; app cannot directly update reports or grant roles. |
| CP-07 | Citizen and official demo accounts | Done | Two confirmed fixtures; official assigned Alangilan in private.officials. Role and authorization checks passed. Passwords remain outside Git. |
| CP-08 | Registration, login, restoration, logout | Partial | UI login/logout for both roles, offline restoration and switching passed. Six-digit confirmation UI, resend cooldown and restoration built; EmailConfirmationTest 6/6 passed on the emulator and on a physical TECNO phone. Real email delivery remains pending: built-in SMTP covers organization-team addresses only, so other addresses need custom SMTP (see backend/EMAIL_CONFIRMATION.md). Demo accounts are used meanwhile. |
| CP-09 | Private profile and editing | Prototype verified | Live save_profile and my_profile passed; SQL tests deny another user's profile access. Profile editing UI is connected. |
| CP-10 | Report creation and details | Prototype verified | UUID retry produces one immutable report. The emulator form/photo workflow passed using the real queue, Storage and SQL; the system photo-picker result was stubbed. Native gallery permissions and on-phone usability remain in validation. |
| CP-11 | Compressed photos | Prototype verified | Live private upload/read and official read passed; anonymous read denied. Android offline photo was retained, synced and removed locally only after acknowledgement. |
| CP-12 | My Reports, community and home | Prototype verified | Account-scoped reads, 25-row pages, automatic refresh/retry and loaded-data summaries connected. Android feed/filter test passed. |
| CP-13 | Official status and history | Prototype verified | Citizen/wrong-barangay updates denied. Valid transitions atomically update report/history/notice. History displays in details. |
| CP-14 | Notification history and read state | Prototype verified | SQL/live owner and read-state checks passed earlier. Six JVM tests now cover paging, equal timestamps, insert bursts, retries and read rollback; emulator UI loads 123 local notices and opens the correct report. Refresh no longer drops the gap between new and older pages. Hosted pagination with 50+ notices remains pending; no production fixtures were added. |
| CP-15 | FCM push | Prototype verified | Restricted sender credential stored in Edge secrets. Background device notification observed; foreground FCM and notification tap to details passed. Server cron delivered without a mobile send call. Invalid-token/logout recovery tested at appropriate layers; multi-phone latency study remains CP-25. |
| CP-16 | Complete two-account device rehearsal | Emulator verified; phones pending | A sequential citizen form/photo → official status UI → citizen notification/read-state rehearsal passed on one emulator. Cloud verification found both photo variants, Under Review, one history row and one read notification. Two simultaneous phones remain a separate check. |
| CP-17 | Durable queue contract | Done | Versioned contract in backend/contracts/offline-sync.md specifies IDs, account/project binding, states, files, acknowledgements and retries. |
| CP-18 | Offline report/photo sync | Prototype verified | Network disabled → queue → force-stop → reopen → reconnect passed. Server uses stable IDs and immutable retries. Repeated field trials and loss-rate measurement remain CP-25. |
| CP-19 | Smart and resumable uploads | Prototype verified | Compressed sync passed on metered Wi-Fi while original waited. Original completed on unmetered Wi-Fi. A >6 MiB upload resumed after an acknowledged chunk and app restart with matching bytes. Emulator cellular network unavailable; real cellular handoff percentage remains CP-25. |
| CP-20 | GPS capture and map pins | Partial | Native map, GeoJSON pins, navigation and attribution implemented. Emulator native long-press on the report form updated both coordinate fields. No report was submitted. Outdoor 20 m accuracy and on-phone pin placement remain unmeasured. |
| CP-21 | Feed/map filters and pagination | Prototype verified | Barangay/category/status/date filters and stable created_at/id cursors implemented for feed/map; load-more controls provided. Live Android cursor/filter checks passed. Large dataset and concurrent-insert field checks remain in validation. |
| CP-22 | Voting and derived urgency | Prototype verified | One vote per account and idempotent removal enforced by SQL; UI connected. Current thresholds: 3 Medium, 10 High; official urgency is a minimum. Group must confirm these provisional thresholds. |
| CP-23 | SMS gateway/parser | Tested up to the gateway; carrier test External | Signed webhook (original format and SMS Gateway for Android format), GSM-7 parser, replay protection and SQL ingestion are deployed. A simulated signed SMS against the hosted function created one report at a test barangay; retry created no duplicate; tampered, stale, wrong-device, wrong-event, malformed and unknown-barangay messages were rejected. Test data and temporary secrets were then removed, so the receiver fails closed. Remaining: a spare phone with a SIM that can run the gateway app (Play Protect blocked the sideloaded APK on the primary phone), the real gateway secret, and the group's approved barangay names/centroids (3 rounded Test Barangay rows are in the database for now). |
| CP-24 | Automated baseline | Done | 17 backend tests, 10 JVM tests, Edge checks and Android builds pass; lint 0 errors, 33 warnings. New emulator notification/map checks pass 2/2 using local fixtures. Earlier Auth and full-workflow evidence remains in backend/VERIFICATION.md. |
| CP-25 | Proposal acceptance measurements | Partial / External | Three of ten planned phone offline runs recorded: Wi-Fi 2.6 s / 5.0 s and mobile data 48.5 s, each one report with photo. Seven offline runs, five handoffs, outdoor GPS, push timing and SUS remain. No statistical acceptance rate is claimed. |
| CP-26 | Review and GitHub delivery | Done | Changes reviewed, secret/whitespace checks passed, committed and pushed to backend/pinpoint-free. Draft PR #1 is open for the group: https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1. |
| CP-27 | Team handoff | Partial | Free setup/architecture guide, checkpoint tracker, verification matrix and debug APK delivered. The group still needs to build/rehearse on its own phones and accept the handoff. |

## Implementation ownership

The user authorized this batch's backend and mobile implementation. Coordinate further changes through the branch/PR rather than starting a second queue, Auth layer or database. The group still supplies the selected SMS gateway, approved barangay names/centroids, urgency thresholds, real phones and evaluation participants.

The attached proposal is a requirements source, not permission to spend money or alter accounts. The user's later zero-budget and Supabase decisions override its Firebase stack choice.

## Push recovery and security

A database job checks for due outbox items every ten seconds and calls deliver-push only when needed. One-use 60-second dispatcher nonces are stored as hashes in a private table; no reusable credential is placed in pg_net request headers. Provider failures back off; notification history remains durable. This provides recovery even after an official closes the app.

FCM's service account has only cloudmessaging.messages.create. Its private key stays in Supabase Edge secrets; the local provisioning key was removed. Publishable keys may be in the APK, server secrets may not.

Advisor warnings and their access-control context are documented in backend/VERIFICATION.md. Do not describe the project as having no warnings.

## Next implementation and validation batch

1. With the user's approval and available phone, finish CP-25 using backend/CP25_PHONE_TESTS.md: seven more offline runs, handoffs, outdoor GPS and push timing. Investigate whether the single 48.5 s mobile-data delay repeats.
2. When approved, live-test notification paging with 50+ hosted notices. Current local regression/UI tests do not certify hosted pagination. Keep demo accounts until the user completes the free SMTP setup under CP-08.
3. Confirm vote thresholds and the approved barangay list/centroids.
4. Select a gateway phone/app and SIM for CP-23; implement its exact webhook adapter, then test carrier SMS.
5. Review the draft PR and perform the team build/handoff.

Do not enable a paid plan to close any checkpoint. Ask about genuinely missing infrastructure or permissions, while continuing independent work.

## Checkpoint log

| Date | Checkpoints | Result |
| --- | --- | --- |
| 2026-11-21 | Initial foundation | Cloned frontend, added Firebase baseline and checkpoint tracker; local commit 70d84e9 preserves that work. |
| 2026-11-21 | Budget/provider change | User declined billing and approved Supabase Free plus free FCM; new project provisioned. |
| 2026-11-21 | CP-03 through CP-15 | Auth/database/private buckets/Edge Functions integrated; cloud ownership/status/photo tests passed; restricted FCM credential configured. |
| 2026-11-21 | CP-17 through CP-22 | Durable queue, resumable Wi-Fi originals, native map, filters/pages and votes implemented; prototype Android/SQL checks passed with limits recorded. |
| 2026-11-21 | CP-15 recovery | Free server dispatcher, leases, backoff and one-use nonce authentication deployed; cron-only push and notification tap passed. |
| 2026-11-21 | CP-24 through CP-27 | Final checks passed; branch pushed and draft PR #1 opened. Setup guide, tracker, verification matrix and APK delivered; external acceptance targets remain explicit. |
| 2026-11-21 | CP-08, CP-10, CP-13, CP-14, CP-16 | UI rehearsal passed on one available emulator using both roles: form/photo upload, official status/history and citizen notification/read state. Added an emulator-only, credential-redacting runner. Physical-phone testing remains deferred. |
| 2026-11-21 | CP-08, CP-10, CP-13, CP-14, CP-16 | Three emulator UI phases passed against the live backend: citizen photo report, official status change and citizen notification/read state. Added an emulator-only, credential-redacting runner. Physical-phone testing remains deferred. |
| 2026-11-21 | CP-08 | Email-confirmation flow implemented; EmailConfirmationTest passed 6/6 on emulator-5554 and on the user's physical TECNO phone (first real-device run). No SMTP/template changes. |
| 2026-11-21 | CP-14, CP-16 | Notification pagination implemented. Workflow runner accepts a physical phone only with -AllowPhysicalDevice. Builds, JVM tests and lint (0 errors) pass; live runs need the private demo credentials file. |
| 2026-11-21 | CP-23, CP-25 | Added a tested adapter for the free SMS Gateway for Android webhook (17 backend tests, Edge type check pass); not deployed, no gateway chosen, no carrier test. Added backend/CP25_PHONE_TESTS.md for the on-phone measurements. |
| 2026-11-21 | CP-23 | Simulated hosted SMS test passed (8 cases); temporary secrets and test report removed. Real carrier test deferred until a spare gateway phone is available. |
| 2026-11-21 | CP-14, CP-20, CP-24 | Resumed from 3b66064; baseline passed. Fixed disappearing notifications during page refresh and failed-read rollback. Six new JVM and two emulator UI checks pass; native map long-press updates the form. No hosted data, secrets, deployments or phone settings changed. |

Setup: [backend/README.md](backend/README.md). Evidence and manual matrix: [backend/VERIFICATION.md](backend/VERIFICATION.md).
