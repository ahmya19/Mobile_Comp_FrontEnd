# PinPoint Citizen App — 2-Groupmate Testing, Field Validation & Feedback Kit

This guide divides the full pre-defense testing, real-world field validation, and usability study across **2 groupmates (Groupmate 1 & Groupmate 2)** so both teammates contribute balanced, defense-ready evidence before the **November 30 – December 4, 2026** defense.

Together, both groupmates will cover:
- Full multi-device QA on real Android phones (UI, animations, camera, offline sync, map, community voting, official workflow, accessibility)
- Real-time paired **Official → Citizen FCM Push Notification** timing
- Full **10-Point Outdoor Alangilan GPS Accuracy Walk** (5 distinct spots each)
- Full **10–15 Participant SUS Usability Study** (5–8 respondents each)
- Defense slide screenshot pack & structured bug/UX feedback report

> **Security rule:** Never paste passwords, API keys, or private credentials into public Git repos or screenshots. Share the APK file and demo account passwords only through your private group chat.

---

## 1. Setup for BOTH Groupmates (5 minutes)

### Step A — Install PinPoint on your Android phone
1. Download **`app-debug.apk`** shared by the project lead (`C:\Users\USER\Documents\PinPoint-Share\app-debug.apk`, SHA-256 `6ed0d666282956ee733c0241073b3d8438329a55301bd011da3d5dceff2a0b0a`).
2. On your **Android phone (Android 9+ / API 28+ recommended)**:
   - Tap `app-debug.apk` to install.
   - If prompted *"not allowed to install unknown apps"*, tap **Settings → Allow from this source**, then return and tap **Install**.
   - If Google Play Protect asks *"Install anyway?"*, tap **More details → Install anyway** (normal for student-built debug APKs not yet on Play Store).

### Step B — First Launch & Battery Setup
1. Open **PinPoint** and go through the full first-run flow:
   - Watch the animated violet **Splash Screen** → swipe or tap **Next** through all **3 Onboarding slides** (**Get started** on slide 3; **Skip** jumps straight to Sign in).
   - On the **Permission Primer**, tap **Allow while using the app** (Location), **Allow notifications**, and **Allow camera**, then tap **Allow & continue**.
   - *(You can replay both anytime from **Profile → App tour & onboarding** and **App permissions**.)*
2. Sign in using the **Citizen Demo Account** (or **Official Demo Account** when assigned below) shared privately by the project lead.
   - *(Note: Public self-signup email delivery requires custom Brevo SMTP; all testers use the confirmed demo accounts.)*
3. **Important for TECNO / Xiaomi / Vivo / Oppo / Realme phones (background sync):**
   - Go to **Android Settings → Apps → PinPoint → Battery → Unrestricted**.
   - On TECNO/Infinix phones, also open **Phone Master / Battery Lab → App Settings → PinPoint → No Restrictions** so offline reports auto-sync when internet returns.
4. **Tag every test report:** Start every test report description with **`[TEST - YourName]`** so the project lead can cleanly identify and remove test posts afterward.

---

## 2. Shared Quick Exploration (BOTH Groupmates — ~5 minutes each)

- [ ] **Task S1 (Navigation & Animations):** Tap all 4 bottom-bar tabs (**Home**, **Reports**, **Map**, **Profile**) and the protruding center violet **Camera button**; confirm smooth transitions, readable status-bar icons, and zero overlapping text.
- [ ] **Task S2 (Profile & SMS Guide):** Open **Profile** → check **App tour & onboarding**, **App permissions**, **Help and support** (expand FAQ items), **About PinPoint**, and **SMS reporting guide** (type a sample Alangilan report and verify the 160-character GSM-7 counter and Copy button work; no need to send a real paid SMS).
- [ ] **Task S3 (Your Own SUS Score):** Fill out the 10-question SUS table in Section 5 based on your own hands-on experience.

---

## 3. Groupmate 1 Assignment — Citizen Field, Camera, Offline & GPS (Points 1–5)

### Task A1 — Real Camera Capture, Gallery & Wi-Fi Original Photo (~8 min)
- [ ] Tap the center **violet Camera button** (or **Quick Report** / a category tile such as **Road** on Home).
- [ ] Pick a category tile (e.g., **Road** or **Trash**) and tap **Take Photo** (real camera sensor — *do not only use gallery*). Take a clear photo of a harmless street/desk/campus object and verify the preview shows **"EXIF & GPS stripped on upload"**.
- [ ] Enter description: `[TEST - Name] Real camera upload check`, Location: `Alangilan test spot`, Barangay: `Alangilan`. Tap **Use my current location**, then tap **Submit Report**.
- [ ] Verify the **Report submitted!** animated confirmation screen appears with a `#PP-` issue number → tap **View My Reports** → open the report and confirm your photo loads sharply and status is **Pending**.
- [ ] **Wi-Fi Original Test:** Turn Wi-Fi OFF (use **mobile data only**). Create a second report with a photo and keep **"Also upload original on unmetered Wi-Fi (up to 10 MB)"** checked → submit → confirm the compressed report syncs quickly over mobile data while the full original waits for Wi-Fi. Then turn **Wi-Fi ON** and verify the original completes without re-submitting the report.
- [ ] **Camera Cancel Check:** Open **Create a Report → Take Photo** and press Back/Cancel without taking a photo; confirm the app does not crash and lets you retry or pick from gallery.

### Task A2 — Account-Isolated Drafts & Form Validation (~5 min)
- [ ] Open **Create a Report**, choose a category, type half a description, attach a photo, and tap **Save as draft**.
- [ ] Leave the screen (tap Back to Home), then re-open **Create a Report** → confirm **"Saved draft restored"** appears with your saved inputs → tap **Discard draft** and verify the form resets cleanly.
- [ ] Try submitting with missing required fields or a 2-letter description → confirm friendly plain-English messages appear (no raw crash or exception class name).

### Task A3 — Offline Queue, Local Edit/Delete & Auto-Reconnect Sync (~8 min)
- [ ] Turn **Airplane Mode ON** (both Wi-Fi and mobile data OFF).
- [ ] Submit **2 offline reports**:
  - Report 1: `[TEST - Name] Offline edit and sync check`
  - Report 2: `[TEST - Name] Offline delete check`
- [ ] Confirm PinPoint shows **"Report Saved on Device"** for each.
- [ ] Go to **Profile → Saved offline reports (N)**:
  - Tap **Edit** on Report 1, change one word, tap **Save changes**, and verify the updated text displays.
  - Tap **Delete** (then **Delete saved report**) on Report 2 and verify it disappears.
- [ ] Turn **Airplane Mode OFF** (turn mobile data or Wi-Fi back ON). Do **NOT** press Retry — start a timer and record how many seconds until Report 1 automatically syncs and appears in **My Reports** (target **≤ 60 seconds**).
- [ ] Open the now-**Synced** Report 1 in **My Reports** and verify there is **no Edit or Delete button** on already-submitted reports (evidence preservation rule).

### Task A4 — Paired Push Notification Receiver (Citizen Side, with Groupmate 2) (~8 min)
- [ ] Stay signed in as the **Citizen Demo Account**. Coordinate live with **Groupmate 2** (who acts as Official in Task B3) across **3 device states**:
  1. **App open in foreground** (Groupmate 2 sets your `[TEST]` report to `Under Review`)
  2. **Home screen / app in background** (Groupmate 2 sets it to `In Progress`)
  3. **Phone screen locked** (Groupmate 2 sets it to `Resolved`)
- [ ] For all 3 updates, time how many seconds until the PinPoint push notification appears in the Android tray (target **≤ 30 seconds**).
- [ ] Tap the notification for cases 1 & 2 and confirm it opens the exact **Report Details** screen showing the updated **Status History** timeline.
- [ ] Open **Notifications** (bell icon on Home) → check unread dot/tint, filter tabs (`All` / `Unread` / `Resolved`), and tap **Mark all read**.

### Task A5 — Outdoor Alangilan GPS Walk: Points 1–5 (~15 min)
- [ ] Walk to **5 distinct outdoor spots** in Barangay Alangilan (clear sky view: e.g., main road corner, school gate, barangay hall frontage, junction, drainage area).
- [ ] At each spot, record **Google Maps exact pin Lat/Lon** vs **PinPoint `Use my current location` Lat/Lon** and time-to-lock in the GPS table (Section 5).

### Task A6 — SUS Usability Recruitment: 5–8 Participants (~30 min)
- [ ] Let **5 to 8 classmates, friends, or Barangay Alangilan residents** spend 3–5 minutes exploring PinPoint (Home, Map, Community, Create a Report form) and record their **10 SUS scores (1–5)** + 1-sentence comment in Section 6.

---

## 4. Groupmate 2 Assignment — Community, Map, Official Workflow, Accessibility & GPS (Points 6–10)

### Task B1 — Community Map, Stacked Pins & Filter Chips QA (~6 min)
- [ ] Open **Map** → verify markers load clearly and **no pins are hidden** under the top filter chips or bottom legend.
- [ ] Test every category chip (**All · N**, **Road**, **Lights**, **Flood**, **Trash**, etc.) and verify counts and pins update accurately; scroll/tap **Load more** if shown.
- [ ] Tap a single marker **and** a stacked/overlapping marker cluster (confirm the stacked-pin chooser list opens and tapping a row opens the exact **Report Details**).
- [ ] Open **Create a Report → Choose or adjust map pin** → long-press a location on the mini-map and confirm the **Latitude (optional)** and **Longitude (optional)** fields update cleanly and stay equal in height.

### Task B2 — Community Feed, Search, Sorting & Urgency Voting (~6 min)
- [ ] Open **Home → View community reports**.
- [ ] Test **Search loaded reports...** with a keyword (e.g., `Road` or `Alangilan`), test category chips, and switch between **Newest | Most upvoted** to confirm ordering changes correctly.
- [ ] Open another user's open report → tap **Vote for urgency** → verify **Community votes: N** increments by 1 → press Back and reopen the same report to verify your vote persisted → tap **Remove my urgency vote** and verify it decrements cleanly. *(Rule: 3 votes raise urgency to Medium; 10 to High.)*
- [ ] Open a **Resolved** report and confirm voting is disabled/refused on closed reports.

### Task B3 — Official Role Workflow & Paired Push Sender (with Groupmate 1) (~8 min)
- [ ] Sign out of Citizen (**Profile → Sign out**) and sign in using the **Alangilan Official Demo Account**.
- [ ] Verify your badge on **Profile** shows **`Official · Alangilan`**.
- [ ] Open **Groupmate 1's** `[TEST]` report (from **Community** or **Map**) and confirm the official review controls appear (`Status`, `Priority`, **Save status**).
- [ ] **Priority-only check:** Change **only Priority** (keep Status unchanged) and tap **Save status** → confirm priority updates cleanly without creating a redundant status-change entry in **Status History**.
- [ ] **Paired Push Test (coordinate with Groupmate 1's Task A4):** Update Groupmate 1's `[TEST]` report through 3 status transitions (`Under Review` → `In Progress` → `Resolved`), tapping **Save status** each time and telling Groupmate 1 the exact second you tap so they can time delivery.
- [ ] **Private Original Photo Check:** On Groupmate 1's Wi-Fi-original report (from Task A1), tap **View original over Wi-Fi** (when attached) and verify the authorized Official can load the private original photo.

### Task B4 — Auth Flows, Logout Warning, Profile Edit & 1.3× Font-Scale Audit (~8 min)
- [ ] **Unsynced Logout Warning:** Turn Airplane Mode ON, save 1 dummy offline report, go to **Profile → Sign out** → verify the **"Log out of PinPoint?"** modal appears warning about unsaved offline report(s) → tap **Stay logged in**, delete the dummy offline report, turn Airplane Mode OFF, then sign out normally.
- [ ] **Auth & Recovery Screens:** On the signed-out screen:
  - Tap **New to PinPoint? Create account** → verify password rules show `Required: 8 characters` separately from `Recommended`, then press **Android System Back** and confirm it returns to **Sign in** (does not close the app).
  - Tap **Forgot password?** → verify back navigation and tap the 6 OTP boxes on the reset screen to confirm keyboard focus works cleanly (no need to send live email).
  - Verify **"Confirm an existing account"** stays dimmed while the email box is empty and becomes active once an email is typed.
- [ ] **Profile Edit:** Sign back in as Citizen → **Profile → Edit Profile** → update display name, tap **Save Profile**, confirm greeting on Home updates, then restore the original demo name.
- [ ] **1.3× Large Font Check:** Temporarily change Android **Settings → Display → Font size** to **Large (~1.3×)** → inspect **Home, Create a Report, Map, Report Details, Notifications, Profile, App tour, and App permissions** for any clipped text or cut-off buttons, then reset Font size back to Normal.

### Task B5 — Outdoor Alangilan GPS Walk: Points 6–10 (~15 min)
- [ ] Walk to **5 different outdoor spots** in Barangay Alangilan (distinct from Groupmate 1's 5 spots so both combine into a full 10-point Alangilan GPS walk).
- [ ] At each spot, record **Google Maps exact pin Lat/Lon** vs **PinPoint `Use my current location` Lat/Lon** and time-to-lock in Section 5.

### Task B6 — SUS Usability Recruitment (5–7 Participants) & Defense Screenshots (~30 min)
- [ ] Collect **5 to 7 real SUS responses** from classmates/friends/residents (combined with Groupmate 1's 5–8 = **10–15 total participants**).
- [ ] Capture **6–8 crisp real-phone screenshots** (Splash, Onboarding, Home, Report Form with real camera photo, Map with pins, Report Details + Status History, Push Notification in tray) and send them to the project lead for the defense slides.

---

## 5. Shared Field Data Tables

### A. 10-Point Outdoor Alangilan GPS Accuracy Table (Target ≤ 20 m)
*Groupmate 1 fills Points 1–5 · Groupmate 2 fills Points 6–10 (leave `Error (m)` blank if you want the project lead to calculate exact Haversine meters automatically).*

| # | Owner | Landmark / Spot in Alangilan | Google Maps Lat, Lon | PinPoint Lat, Lon | Lock Time (s) | Error (m) | Sky / Notes |
|---|---|---|---|---|---|---|---|
| 1 | GM 1 | | | | | | |
| 2 | GM 1 | | | | | | |
| 3 | GM 1 | | | | | | |
| 4 | GM 1 | | | | | | |
| 5 | GM 1 | | | | | | |
| 6 | GM 2 | | | | | | |
| 7 | GM 2 | | | | | | |
| 8 | GM 2 | | | | | | |
| 9 | GM 2 | | | | | | |
| 10 | GM 2 | | | | | | |

---

### B. System Usability Scale (SUS) Questionnaire (Target Average ≥ 70 across 10–15 Testers)
Rate each statement from **1 (Strongly Disagree)** to **5 (Strongly Agree)**:

| # | Statement | Score (1–5) |
|---|---|---|
| Q1 | I think that I would like to use PinPoint frequently to report barangay issues. | |
| Q2 | I found PinPoint unnecessarily complex. | |
| Q3 | I thought PinPoint was easy to use. | |
| Q4 | I think that I would need the support of a technical person to be able to use PinPoint. | |
| Q5 | I found the various functions in PinPoint were well integrated. | |
| Q6 | I thought there was too much inconsistency in PinPoint. | |
| Q7 | I would imagine that most people in our barangay would learn to use PinPoint very quickly. | |
| Q8 | I found PinPoint very cumbersome to use. | |
| Q9 | I felt very confident using PinPoint. | |
| Q10 | I needed to learn a lot of things before I could get going with PinPoint. | |

**SUS Score Formula (0–100):**
- Odd items (`Q1, Q3, Q5, Q7, Q9`): `score - 1`
- Even items (`Q2, Q4, Q6, Q8, Q10`): `5 - score`
- Sum all 10 adjusted values and multiply by **`2.5`**.

---

## 6. Copy-Paste Feedback Report Templates (Send Back to Project Lead)

### Groupmate 1 Feedback Report
```text
=== PINPOINT FEEDBACK REPORT — GROUPMATE 1 (CITIZEN / CAMERA / OFFLINE / GPS 1-5) ===
Name:
Phone Brand & Model:
Android Version:
Network(s) Tested (Wi-Fi / Globe / Smart / DITO):

TASK RESULTS (Pass / Fail / Partial + notes):
- S1-S2 (Install, Splash, Onboarding, Permissions, Tabs, SMS Guide):
- A1 (Real Camera Photo + Wi-Fi Original + Camera Cancel):
- A2 (Draft Save/Restore/Discard + Form Validation):
- A3 (Offline 2 Reports, Edit/Delete, Auto-Sync time in seconds: ___ s, Synced immutable):
- A4 (Paired Push Receiver — times: Foreground ___ s, Background ___ s, Locked ___ s; Notifications Mark all read):
- A5 (GPS Points 1-5 attached in table? Yes/No):
- A6 (External SUS participants collected: ___ people):

MY SUS SCORES (1-5): Q1:_ Q2:_ Q3:_ Q4:_ Q5:_ Q6:_ Q7:_ Q8:_ Q9:_ Q10:_
EXTERNAL PARTICIPANT SUS SCORES (list Name/Initials + Q1..Q10 + 1-line comment):
1.
2.
3.
4.
5.

BUGS / GLITCHES FOUND (Screen, steps, screenshot attached?):
1.
2.

UX / POLISH SUGGESTIONS:
1.
2.
```

### Groupmate 2 Feedback Report
```text
=== PINPOINT FEEDBACK REPORT — GROUPMATE 2 (MAP / COMMUNITY / OFFICIAL / QA / GPS 6-10) ===
Name:
Phone Brand & Model:
Android Version:
Network(s) Tested (Wi-Fi / Globe / Smart / DITO):

TASK RESULTS (Pass / Fail / Partial + notes):
- S1-S2 (Install, Splash, Onboarding, Permissions, Tabs, SMS Guide):
- B1 (Map Pins Visible, Filter Chips, Stacked Chooser, Mini-Map Long-Press):
- B2 (Community Search, Sort Newest/Most Upvoted, Vote for urgency persist & toggle, Closed report refused):
- B3 (Official Role Badge, Priority-only update, 3 Paired Push Sends, View original over Wi-Fi):
- B4 (Unsynced Logout Warning, Register Back/OTP/Disabled button, Profile Edit, 1.3x Font Check):
- B5 (GPS Points 6-10 attached in table? Yes/No):
- B6 (External SUS participants collected: ___ people; 6-8 Defense Screenshots attached? Yes/No):

MY SUS SCORES (1-5): Q1:_ Q2:_ Q3:_ Q4:_ Q5:_ Q6:_ Q7:_ Q8:_ Q9:_ Q10:_
EXTERNAL PARTICIPANT SUS SCORES (list Name/Initials + Q1..Q10 + 1-line comment):
1.
2.
3.
4.
5.

BUGS / GLITCHES FOUND (Screen, steps, screenshot attached?):
1.
2.

UX / POLISH SUGGESTIONS:
1.
2.
```
