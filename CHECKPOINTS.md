# PinPoint project checkpoints

Updated November 22, 2026 (Asia/Manila).

## Current checkpoint

The free cloud backend is deployed and connected to the existing Kotlin frontend. Emulator notification/map checks pass. The user approved seven further phone trials; all seven saved reports/photos were recovered once, giving ten recovery samples including the earlier three. Run 7 used Wi-Fi instead of planned cellular; run 8's background observer stalled and the report recovered after reopening, with no valid latency measurement. Two foreground cellular trials then completed. CP-25 timing/background validation and the other field measurements remain open. Further phone or hosted-data changes require approval.

Code is shared in [draft PR #1](https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1) on backend/pinpoint-free. The draft is ready for team testing and review.

Latest work assignment: Codex owns all backend implementation/fixes. Claude owns UI structuring to mirror the provided UI/UX and whole-app testing, including the backend. Authoritative ownership is in HANDOFF.md; Claude's work package is CLAUDE_HANDOFF.md.

**Budget boundary: Supabase Free and free Firebase FCM only. No Blaze or billing account.** The user approved this provider change and authorized the mobile features as well as backend work. The original Firebase baseline is saved in commit 70d84e9; it is no longer the active architecture.

Statuses: **Done** = stated setup/implementation condition verified; **Prototype verified** = functional checks passed, with acceptance measurements still separate; **Partial** = listed work remains; **External** = needs a real device, gateway or participant; **Assigned** = owner/scope agreed, implementation or acceptance pending.

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
| CP-18 | Offline report/photo sync | Prototype verified | Ten phone reports have recovery evidence including seven new UUID/photo-hash restart checks. All new fixtures exist once with matching photos. One background observation stalled and required reopening; automatic recovery latency remains unproven. |
| CP-19 | Smart and resumable uploads | Prototype verified | Compressed sync passed on metered Wi-Fi while original waited. Original completed on unmetered Wi-Fi. A >6 MiB upload resumed after an acknowledged chunk and app restart with matching bytes. Emulator cellular network unavailable; real cellular handoff percentage remains CP-25. |
| CP-20 | GPS capture and map pins | Partial | Native map, GeoJSON pins, navigation and attribution implemented. Emulator native long-press on the report form updated both coordinate fields. No report was submitted. Outdoor 20 m accuracy and on-phone pin placement remain unmeasured. |
| CP-21 | Feed/map filters and pagination | Prototype verified | Barangay/category/status/date filters and stable created_at/id cursors implemented for feed/map; load-more controls provided. Live Android cursor/filter checks passed. Large dataset and concurrent-insert field checks remain in validation. |
| CP-22 | Voting and derived urgency | Prototype verified | One vote per account and idempotent removal enforced by SQL; UI connected. Current thresholds: 3 Medium, 10 High; official urgency is a minimum. Group must confirm these provisional thresholds. |
| CP-23 | SMS gateway/parser | Tested up to the gateway; carrier test External | Signed webhook (original format and SMS Gateway for Android format), GSM-7 parser, replay protection and SQL ingestion are deployed. A simulated signed SMS against the hosted function created one report at a test barangay; retry created no duplicate; tampered, stale, wrong-device, wrong-event, malformed and unknown-barangay messages were rejected. Test data and temporary secrets were then removed, so the receiver fails closed. Remaining: a spare phone with a SIM that can run the gateway app (Play Protect blocked the sideloaded APK on the primary phone), the real gateway secret, and the group's approved barangay names/centroids (3 rounded Test Barangay rows are in the database for now). |
| CP-24 | Automated baseline | Done | 17 backend tests, 10 JVM tests, Edge checks and Android builds pass; lint 0 errors, 33 warnings. New emulator notification/map checks pass 2/2 using local fixtures. Earlier Auth and full-workflow evidence remains in backend/VERIFICATION.md. |
| CP-25 | Proposal acceptance measurements | Partial / External | Ten offline recovery samples recorded. New runs 4–7 recovered over Wi-Fi; 7 has a transport deviation. Run 8 recovered after reopening with invalid timing. Foreground cellular runs 9–10: 12.579 / 12.333 s from availability (4.387 / 11.171 s from validation). No automatic-latency pass or pooled average is claimed. Background diagnosis, five handoffs, outdoor GPS, push timing and SUS remain. |
| CP-26 | Review and GitHub delivery | Done | Changes reviewed, secret/whitespace checks passed, committed and pushed to backend/pinpoint-free. Draft PR #1 is open for the group: https://github.com/ahmya19/Mobile_Comp_FrontEnd/pull/1. |
| CP-27 | Team handoff | Partial | Setup/evidence/APK delivered; ownership and Claude's UI/whole-app test package documented. Design comparison, complete regression evidence and team acceptance remain pending. |
| CP-28 | Mirror supplied UI/UX | Assigned to Claude | Inventory the supplied reference and existing screens, restructure Compose UI/navigation and compare screenshots for every screen/state. Reference location must be established; visual parity is not yet verified. |
| CP-29 | Whole-app regression including backend | Assigned to Claude | Execute the complete matrix in CLAUDE_HANDOFF.md, including API/security and full citizen/official flows. Record Pass/Fail/Blocked with evidence and backend defects for Codex. Historical prototype checks do not close this checkpoint. |

## Implementation ownership

Follow the current ownership table in HANDOFF.md: Codex implements/fixes the backend; Claude structures the UI to match the supplied reference and validates the whole app, including backend behavior. Reuse the existing Auth, queue and data contracts, and coordinate shared-file changes through the branch/PR. The group supplies design references, gateway/hardware, approved barangays/thresholds and evaluation participants.

The attached proposal is a requirements source, not permission to spend money or alter accounts. The user's later zero-budget and Supabase decisions override its Firebase stack choice.

## Push recovery and security

A database job checks for due outbox items every ten seconds and calls deliver-push only when needed. One-use 60-second dispatcher nonces are stored as hashes in a private table; no reusable credential is placed in pg_net request headers. Provider failures back off; notification history remains durable. This provides recovery even after an official closes the app.

FCM's service account has only cloudmessaging.messages.create. Its private key stays in Supabase Edge secrets; the local provisioning key was removed. Publishable keys may be in the APK, server secrets may not.

Advisor warnings and their access-control context are documented in backend/VERIFICATION.md. Do not describe the project as having no warnings.

## Next implementation and validation batch

1. Investigate background recovery using the ordinary app and an external observer. The run-8 instrumentation stall prevents a timing verdict; two foreground cellular runs do not explain the earlier 48.5 s sample. Phone/settings changes need approval.
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
| 2026-11-22 | CP-18, CP-25 | Recorded the approved seven additional phone trials from November 21: all reports/photos recovered once, one wrong-transport deviation and one invalid background observation retained. Added a guarded/redacting runner, private evidence files and a tested host deadline. Main app build unchanged; no billing/schema/secret/deployment changes. |
| 2026-11-22 | CP-27 through CP-29 | User assigned backend implementation/fixes to Codex and UI/UX mirroring plus whole-app/backend testing to Claude. Added the actionable Claude handoff and pending acceptance checkpoints; no new implementation/test pass is claimed. |

Setup: [backend/README.md](backend/README.md). Evidence and manual matrix: [backend/VERIFICATION.md](backend/VERIFICATION.md).
