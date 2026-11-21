# PinPoint project checkpoints

Updated November 21, 2026 (Asia/Manila).

## Current checkpoint

The free cloud backend is deployed and connected to the existing Kotlin frontend. Offline photo sync, resumable Wi-Fi originals, feeds, filters, votes, GPS/map integration, status history and push delivery are implemented. Device checks use the existing Pixel_8 Google Play emulator, Android API 37. Physical-phone measurements, SMS hardware and the user study are still pending.

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
| CP-08 | Registration, login, restoration, logout | Partial | UI login/logout for both roles, offline restoration and switching passed. Public confirmation emails remain pending: built-in SMTP covers organization-team addresses only, so other addresses need custom SMTP. |
| CP-09 | Private profile and editing | Prototype verified | Live save_profile and my_profile passed; SQL tests deny another user's profile access. Profile editing UI is connected. |
| CP-10 | Report creation and details | Prototype verified | UUID retry produces one immutable report. The emulator form/photo workflow passed using the real queue, Storage and SQL; the system photo-picker result was stubbed. Native gallery permissions and on-phone usability remain in validation. |
| CP-11 | Compressed photos | Prototype verified | Live private upload/read and official read passed; anonymous read denied. Android offline photo was retained, synced and removed locally only after acknowledgement. |
| CP-12 | My Reports, community and home | Prototype verified | Account-scoped reads, 25-row pages, automatic refresh/retry and loaded-data summaries connected. Android feed/filter test passed. |
| CP-13 | Official status and history | Prototype verified | Citizen/wrong-barangay updates denied. Valid transitions atomically update report/history/notice. History displays in details. |
| CP-14 | Notification history and read state | Prototype verified | Exactly one notice for repeated status retry; read flag and owner-only access tested in SQL/live API checks. UI opens its report. |
| CP-15 | FCM push | Prototype verified | Restricted sender credential stored in Edge secrets. Background device notification observed; foreground FCM and notification tap to details passed. Server cron delivered without a mobile send call. Invalid-token/logout recovery tested at appropriate layers; multi-phone latency study remains CP-25. |
| CP-16 | Complete two-account device rehearsal | Emulator verified; phones pending | A sequential citizen form/photo → official status UI → citizen notification/read-state rehearsal passed on one emulator. Cloud verification found both photo variants, Under Review, one history row and one read notification. Two simultaneous phones remain a separate check. |
| CP-17 | Durable queue contract | Done | Versioned contract in backend/contracts/offline-sync.md specifies IDs, account/project binding, states, files, acknowledgements and retries. |
| CP-18 | Offline report/photo sync | Prototype verified | Network disabled → queue → force-stop → reopen → reconnect passed. Server uses stable IDs and immutable retries. Repeated field trials and loss-rate measurement remain CP-25. |
| CP-19 | Smart and resumable uploads | Prototype verified | Compressed sync passed on metered Wi-Fi while original waited. Original completed on unmetered Wi-Fi. A >6 MiB upload resumed after an acknowledged chunk and app restart with matching bytes. Emulator cellular network unavailable; real cellular handoff percentage remains CP-25. |
| CP-20 | GPS capture and map pins | Partial | GPS permission/request with timeout, editable pin, coordinate validation, native map, marker navigation and attribution implemented. Map smoke passed after fixing initialization order. Outdoor 20 m accuracy and on-phone pin placement remain. |
| CP-21 | Feed/map filters and pagination | Prototype verified | Barangay/category/status/date filters and stable created_at/id cursors implemented for feed/map; load-more controls provided. Live Android cursor/filter checks passed. Large dataset and concurrent-insert field checks remain in validation. |
| CP-22 | Voting and derived urgency | Prototype verified | One vote per account and idempotent removal enforced by SQL; UI connected. Current thresholds: 3 Medium, 10 High; official urgency is a minimum. Group must confirm these provisional thresholds. |
| CP-23 | SMS gateway/parser | Partial / External | Signed webhook, GSM-7 parser, replay protection and SQL ingestion deployed/tested. Receiver intentionally fails closed: no gateway selected, no shared gateway secret, and no approved barangay centroids configured. Need gateway phone/app + SIM and actual message format. |
| CP-24 | Automated baseline | Done | 16 backend tests, 4 JVM tests, Edge type checks, Android builds and lint pass (0 errors, 33 warnings). Backend and pinned Edge dependencies report no npm audit findings. Android checkpoint phases recorded in backend/VERIFICATION.md. |
| CP-25 | Proposal acceptance measurements | External | A test matrix records the targets and evidence needed. No statistical timing, cellular loss/resume rate, carrier delivery, outdoor GPS or SUS result is claimed. |
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

1. Use the debug APK on two Android phones for the continuous CP-16 rehearsal. Verify public registration/confirmation or prepare a free SMTP configuration under CP-08.
2. Run the CP-25 matrix: actual cellular/Wi-Fi switching, repeated offline sync, GPS and push timing. Record device/network/sample counts and failures.
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

Setup: [backend/README.md](backend/README.md). Evidence and manual matrix: [backend/VERIFICATION.md](backend/VERIFICATION.md).
