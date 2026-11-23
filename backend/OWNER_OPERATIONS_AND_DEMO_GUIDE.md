# PinPoint Citizen App — Owner Operations, Free-Tier Governance & Defense Guide

Prepared November 23, 2026 for the Group 2 defense (tentative window: November 30–December 4, 2026). All instructions keep the project strictly on **$0 budget (Supabase Free + free Firebase FCM)**. Never attach a billing card or enable Firebase Blaze.

---

## 1. Accounts, roles, and official assignment

- **Public signups are always citizens.** No screen or API call lets a user promote themselves to official (`private.officials` is unreadable and unwritable by `anon` and `authenticated`).
- **Assigning a team member as an Alangilan official (Supabase SQL Editor):**
  1. Have the teammate register normally (or create them in *Authentication > Users*).
  2. Copy their `id` (UUID) from *Authentication > Users*.
  3. Run in the Supabase SQL Editor:
     ```sql
     insert into private.officials (user_id, barangay)
     values ('<USER-UUID>', 'Alangilan')
     on conflict (user_id) do update set barangay = excluded.barangay;
     ```
- **Permanent test identities vs demo accounts:**
  - Dedicated fixture identities (`pinpoint-test-citizen@pinpoint-fixtures.example.com` and `pinpoint-test-official@pinpoint-fixtures.example.com`) are stored only in private `work\supabase-fixture-identities.json` (never in Git or the APK). Use them for automated tests and push trials.
  - Shared demo accounts (`work\supabase-test-accounts.json`) remain usable for live defense rehearsals. Never commit passwords or paste them into chat.

## 2. Public email confirmation & SMTP (Batch 2B)

Built-in Supabase SMTP only delivers to organization-team addresses (2 emails/hour). To allow any non-team user to register or reset their password:
1. Create/sign in to a free **Brevo** account (check current Free daily cap on Brevo's pricing page; 300/day when checked).
2. Verify your sender email or domain in Brevo.
3. Generate a Brevo **SMTP key** (not an API key or account password).
4. In Supabase Dashboard → *Authentication > Emails > SMTP Settings*: enable Custom SMTP with `smtp-relay.brevo.com`, port `587`, Brevo SMTP Login, SMTP key, sender name `PinPoint Citizen`, and your verified sender address.
5. In *Authentication > Email Templates > Confirm signup*: paste `supabase/templates/confirmation.html` (uses the 6-digit `{{ .Token }}` code; keep email confirmation enabled and OTP length = 6).
6. Keep password recovery template aligned with 6-digit OTP (`{{ .Token }}`) so the in-app **Forgot password?** flow works end-to-end.

**Defense fallback if SMTP verification is still pending:** Use the pre-confirmed private demo accounts for the live demo and honestly state that public email delivery awaits Brevo sender verification while demonstrating the implemented 6-digit OTP verification, cooldown, and recovery UI.

## 3. SMS gateway phone, SIM, power & secret rotation (Batch 2C)

The `sms-report` Edge Function is deployed and **fail-closed** until all three Edge secrets exist (`SMS_GATEWAY_FORMAT=sms-gate`, `SMS_GATEWAY_ID`, `SMS_GATEWAY_SECRET`).
1. Use a **spare** Android phone (not your primary personal phone) with an active SMS SIM and load/allowance. Keep it plugged into a reliable charger on Wi-Fi or mobile data.
2. Install **SMS Gateway for Android** from its official source without bypassing Play Protect.
3. Generate a random 32+ character hex webhook secret locally, set it in the gateway app, and upload secrets via CLI (never Git/chat):
   ```powershell
   npx supabase secrets set SMS_GATEWAY_FORMAT=sms-gate SMS_GATEWAY_ID=<deviceId> SMS_GATEWAY_SECRET=<secret>
   ```
4. Configure the webhook URL in the gateway app to `https://jaysxjjdvaitgrbqjlhe.supabase.co/functions/v1/sms-report` for `sms:received` events.
5. **Supported single-segment GSM-7 format (<= 160 septets):**
   ```
   PIN <ROAD|LIGHT|FLOOD|GARBAGE|WATER|OTHER>;Alangilan;<Description at least 10 chars>
   ```
   Reports created via SMS are honestly pinned to Alangilan's approximate centroid (`13.7819, 121.0671`) and labelled `Barangay center (SMS location)`.
6. **Secret rotation / emergency shutoff:** Rotate by generating a new 32+ char secret in both the gateway app and `npx supabase secrets set`. To immediately fail-close the endpoint, run `npx supabase secrets unset SMS_GATEWAY_SECRET`.

**Defense fallback if no spare gateway phone is available:** Demonstrate the in-app **SMS Reporting Guide** (live GSM-7 160-character counter, format builder, Messages handoff) and show the automated HMAC signature, replay protection, and Alangilan routing tests passing (`npm test`). Do not claim live carrier delivery without a real carrier test.

## 4. Free-tier limits, project pause, backups & restore

- **Free-tier limits (verify current limits in Supabase Dashboard → Organization → Usage):**
  - Database storage (~500 MB), File Storage (~1 GB), egress bandwidth, and Edge Function invocations are bounded.
  - **Inactivity pause:** Supabase Free projects pause after ~7 days of inactivity. Visit the dashboard and run a small read or sign-in at least 24 hours before the defense to confirm status `ACTIVE_HEALTHY`.
- **Evidence preservation & abandoned-upload cleanup:**
  - Committed reports (`public.reports`) and attached photos (`report-images`, `report-originals`) are immutable. Citizens cannot overwrite or delete submitted reports.
  - Run a read-only storage audit periodically:
    ```powershell
    python backend\scripts\cleanup_abandoned_uploads.py --expected-project-ref jaysxjjdvaitgrbqjlhe
    ```
  - Apply cleanup (24-hour grace period enforced by default) only with an isolated ledger directory outside the repository.
- **Database backups & fresh restore:**
  - Free-tier projects do not include automated point-in-time recovery. Before major changes or the defense, export critical tables with `pg_dump` or dashboard CSV exports into a private encrypted folder.
  - **NEVER run bare `npx supabase db push` against production `jaysxjjdvaitgrbqjlhe`** (hosted migration timestamps differ from repo filenames; see `backend/APPROVED_POLICY_DEPLOYMENT.md`).
  - For a clean fresh restore on a confirmed-$0 disposable Free project, use `backend/scripts/fresh_restore_harness.py` (see `backend/ABANDONED_UPLOAD_CLEANUP_AND_DEMOS.md`).

## 5. Android OEM background-sync setup (TECNO / aggressive battery managers)

On TECNO HiOS (Android 15) and similar OEM skins, standard Android "Unrestricted battery" is not enough because **Battery Lab → Intelligent Management** still freezes the app UID in the kernel (BG-001).
- Apply **only to PinPoint** (never disable OEM battery management globally):
  1. Android Settings → Apps → PinPoint → Battery → **Unrestricted**.
  2. Phone Master / Battery Lab → Power Saving Management for Apps → PinPoint → **No Restrictions**.
  3. Keep App Launch / Auto-start **Allowed**.
- See `backend/ANDROID_BACKGROUND_SETUP.md` for verified awake/screen-off measurements and the reverse-control evidence.

## 6. Signing, installing & updating the APK

- Debug builds (`.\gradlew.bat :app:assembleDebug`) are signed with the machine's local Android debug keystore and are suitable for team testing and classroom defense.
- Release builds (`.\gradlew.bat :app:assembleRelease`) verify `app/google-services.json`, `SUPABASE_URL`, and `SUPABASE_PUBLISHABLE_KEY`, and disable all debug test seams (`SupabaseBackend.installForTesting`, `WifiOriginals.installForTesting`, `MapTiles.overrideForTesting`).
- **Signing a distributable release APK:** keep a private `.jks` keystore **outside Git** (never commit keystores or keystore passwords), sign via `apksigner` or Gradle `signingConfigs` reading paths from private `local.properties`. Note that switching from a debug-signed APK to a release-signed APK requires uninstalling first (which resets local app data); during defense updates, stick consistently to one signing key so installed sessions and offline queues are preserved!

## 7. Defense demonstration script (5–7 minutes, honest & resilient)

1. **Citizen Login & Home Dashboard (1 min):** Sign in as Citizen (note encrypted session restoration on restart). Point out the charcoal Active Report progress timeline, Quick Report categories, High-Priority carousel, and Recent Community Reports.
2. **Issue Submission with Camera / Picker + Offline Resilience (1.5 min):**
   - Demonstrate **Save as draft** (account-isolated local persistence) and either **Take Photo** (real system camera via `FileProvider` with EXIF rotation + GPS stripping) or **Add Photo** (system picker).
   - Toggle Airplane mode briefly (optional) → submit → show **Report Saved on Device** → view **Saved reports** queue (show Edit/Delete allowed ONLY before upload; submitted reports remain immutable) → reconnect Wi-Fi → show automatic sync with compressed photo (<250 KB) and Wi-Fi-only resumable original!
3. **Community Map, Stacked Markers & Urgency Voting (1 min):** Open **Explore Map** (priority-colored pins + legend), tap a stacked marker to show the lazy chooser (BG-003/004), open details, tap **Vote for urgency** (explain 3 votes = Medium, 10 votes = High, votes never lower an official assessment).
4. **Official Role Separation & Real Push Notification (1.5 min):** On a second device or account switch, sign in as the **Alangilan Official**, open the report, change status (`Pending → Under Review`) + priority (`Save status`). Explain BG-005 (priority-only updates don't spam notifications) and show the **real FCM push notification** arriving on the citizen's device and tapping straight into the report's updated **Status history**!
5. **SMS Fallback & Honest Scope Boundaries (1 min):** Open **SMS Reporting Guide** from Notifications/Profile to show the exact 160-character GSM-7 format and honest fail-closed / Alangilan-centroid status. Present the acceptance matrix honestly: state which targets are measured on-device vs which external gates (Brevo SMTP, spare SMS gateway hardware, 10-point outdoor GPS field study, 10–15 participant SUS study) remain pending human/hardware inputs.
