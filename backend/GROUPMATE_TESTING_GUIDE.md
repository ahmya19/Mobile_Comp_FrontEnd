# PinPoint Citizen App — Groupmate Testing, Field Validation & Feedback Kit

Use this guide to share PinPoint with your **Group 2 teammates and usability testers (10–15 participants)** so everyone can install the app, complete real-world validation tasks, contribute genuine field data (real camera photos, 10-point outdoor Alangilan GPS walk, and SUS usability scores), and send back structured feedback before the **November 30 – December 4, 2026** defense.

> **Security rule:** Never paste passwords, API keys, or private credentials into public Git repos or screenshots. Share the APK file and demo account password only through your private group chat.

---

## 1. How Groupmates Get Access & Install PinPoint (5 minutes)

### Step A — Get the APK from the project lead
1. Project lead shares **`app-debug.apk`** (located at `app/build/outputs/apk/debug/app-debug.apk` on latest `main`) via Google Drive, Messenger/Discord file transfer, or direct USB copy.
2. On your **Android phone (Android 9+ / API 28+ recommended)**:
   - Download `app-debug.apk` and tap it to install.
   - If Android prompts *"For your security, your phone isn't allowed to install unknown apps"*, tap **Settings → Allow from this source**, then go back and tap **Install**.
   - If Google Play Protect asks *"Install anyway?"*, tap **More details → Install anyway** (normal for student-built debug APKs not yet on Play Store).

### Step B — First Launch (Onboarding, Permissions & Sign In)
1. Open **PinPoint** — observe the animated violet **Splash Screen** → swipe or tap **Next** through all **3 Onboarding slides** (**Get started** on the last one; **Skip** jumps straight to the sign-in screen) → on the **Permission Primer** tap **Allow while using the app** (Location), the Notifications and **Allow camera** buttons, then **Allow & continue** (or **Maybe later**). You can replay both anytime from **Profile → App tour & onboarding / App permissions**.
2. On the **Welcome back / Sign in** screen, enter the **Citizen Demo Account** email and password shared privately by the project lead.
   - *(Note: Public self-signup email confirmation requires a custom SMTP sender; during testing, everyone uses the confirmed demo citizen account—or the official account only for Task 5.)*
3. **Important for TECNO / Xiaomi / Vivo / Oppo / Realme phones (background sync):**
   - Go to **Android Settings → Apps → PinPoint → Battery → Unrestricted**.
   - On TECNO/Infinix phones, also open **Phone Master / Battery Lab → App Settings → PinPoint → No Restrictions** so offline reports auto-sync when internet returns.

---

## 2. Assigned Tasks for Groupmates (Complete All 6 Core Tasks + Field Contributions)

Mark every task **Pass** or **Fail** in the feedback sheet below, and write `[TEST - YourFirstName]` at the start of any test report description so the project lead can identify and clean up test posts afterward.

### Task 1 — First-Run Experience, Navigation & Profile Exploration (~3 min)
- [ ] Watch the animated Splash screen and swipe through all 3 Onboarding slides (`Next` → `Get started`).
- [ ] Check the Permission Primer screen (Location, Notifications, Camera).
- [ ] Sign in and verify the **Home** dashboard loads (Active report card, Quick Report category grid, High-priority issues, Reports from others).
- [ ] Tap all 4 bottom-bar tabs (**Home**, **Reports**, **Map**, **Profile**) and notice transitions and the protruding center violet Camera button.
- [ ] Open **Profile** → try **App tour & onboarding**, **App permissions**, **Help and support** (expand FAQ items), and **SMS reporting guide** (type a sample message and verify the 160-character GSM-7 counter works; do not need to actually send an SMS).

### Task 2 — Real Camera Photo Report Submission (Online) (~5 min)
- [ ] Tap the center **violet Camera button** in the bottom bar (or the **Quick Report** button / a category tile such as **Road** on Home).
- [ ] Pick a category tile (e.g., **Road** or **Trash**).
- [ ] Tap **Take Photo** (real camera sensor — *do not only use gallery*), take a photo of a harmless street/desk/campus object, and confirm the preview shows **"EXIF & GPS stripped on upload"**.
- [ ] Enter description: `[TEST - YourName] Real camera upload check`, Location: `Alangilan test spot`, Barangay: `Alangilan`.
- [ ] Tap **Use my current location** (or **Choose or adjust map pin** and long-press the spot on the map).
- [ ] Tap **Submit Report** → confirm the **Report submitted!** animated confirmation screen appears with a `#PP-` issue number.
- [ ] Tap **View My Reports** → open your report → confirm your photo loads clearly and status is **Pending**.

### Task 3 — Offline Report Queue, Local Edit/Delete & Auto-Reconnect Sync (~5 min)
- [ ] Turn **Airplane Mode ON** (both Wi-Fi and mobile data OFF).
- [ ] Open the Report form, pick a category, type `[TEST - YourName] Offline sync check`, fill Location + Barangay (`Alangilan`), and submit.
- [ ] Verify PinPoint shows **"Report Saved on Device"** instead of crashing or losing your report.
- [ ] Go to **Profile → Saved offline reports (N)** (the row shows how many unsent reports there are):
  - Tap **Edit** on the unsent report (change one word, tap **Save changes**) and verify the edit saves.
  - Create a second dummy offline report and tap **Delete** (then **Delete saved report**) to confirm unsent local reports can be removed (while already-submitted reports cannot be deleted).
- [ ] Turn **Airplane Mode OFF** (turn Wi-Fi or mobile data back ON). Wait up to 60 seconds **without pressing Retry** and confirm the first offline report automatically transitions to **Synced** and appears in **My Reports**.

### Task 4 — Community Feed, Upvoting, Filters & Map Pin Exploration (~4 min)
- [ ] Open **Map**: verify pins are visible (not hidden under the filter chips or legend), test the chips (**All · N**, **Road**, **Lights**, **Flood**, **Trash**, ...), and tap a pin (or pick from the stacked-pin list) to open **Report Details**.
- [ ] In **Report Details** (not your own report, if possible), tap **Vote for urgency** → confirm **Community votes: N** goes up by 1; tap **Remove my urgency vote** to verify it toggles back. (3 votes raise urgency to Medium; 10 to High.)
- [ ] Open the Community feed (Home → **View community reports**) → test **Search loaded reports...**, the category chips, and switching **Newest | Most upvoted**.

### Task 5 — Official Status Update & Real-Time Push Notification (Paired Test, 2 Groupmates) (~5 min)
- [ ] **Groupmate A** stays signed in as the **Citizen** (locks screen or goes to Home screen).
- [ ] **Groupmate B** signs in as the **Alangilan Official**, opens Groupmate A's `[TEST]` report (Community feed or Map), sets **Status** to `Under Review` (or `In Progress`) and taps **Save status**.
- [ ] **Groupmate A** times how many seconds until the PinPoint push notification appears in the Android tray (target **≤ 30 seconds**), then taps the notification and confirms it opens the exact **Report Details** screen with the updated **Status History** timeline.

### Task 6 — Field Contribution A: 10-Point Outdoor Alangilan GPS Walk (~20 min, 1–2 Groupmates)
Walk to **10 distinct outdoor spots** around Barangay Alangilan (clear sky view, e.g., highway corner, school gate, barangay hall frontage, subdivision entrance, drainage culvert). At each spot:
1. Open **Google Maps** on the same phone, drop a pin on your exact standing spot, and write down the **Reference Lat / Lon**.
2. Open **PinPoint → Create a Report → Use my current location** and write down **PinPoint Lat / Lon** (and time to lock).
3. Record all 10 points in the table below (target horizontal error **≤ 20 meters**):

| # | Landmark / Spot in Alangilan | Reference Lat, Lon (Google Maps) | PinPoint Lat, Lon | Error (m) | Notes (clear sky / trees / overcast) |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 | | | | | |
| 10 | | | | | |

---

## 3. Field Contribution B: System Usability Scale (SUS) Questionnaire (All Testers, Target ≥ 70)

After completing Tasks 1–4, each tester rates each statement from **1 (Strongly Disagree)** to **5 (Strongly Agree)**:

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

**How to compute one tester's SUS score (0–100):**
- Odd questions (`Q1, Q3, Q5, Q7, Q9`): subtract `1` from each score (`score - 1`).
- Even questions (`Q2, Q4, Q6, Q8, Q10`): subtract each score from `5` (`5 - score`).
- Add all 10 adjusted values together and multiply by **`2.5`**.
- **Goal:** Average across **10–15 participants ≥ 70.0** (above industry benchmark).

---

## 4. Copy-Paste Feedback Report Template (Send Back to Project Lead)

Every groupmate copies, fills out, and sends this template back after testing:

```text
=== PINPOINT TESTER FEEDBACK REPORT ===
Tester Name:
Phone Brand & Model (e.g., Samsung A54, Xiaomi Redmi Note 12, TECNO):
Android Version:
Network Used (Wi-Fi / Globe / Smart / DITO):

TASK RESULTS (Pass / Fail / Partial + notes):
- Task 1 (Splash, Onboarding, Permissions, Navigation, Profile):
- Task 2 (Real Camera Photo Report Online):
- Task 3 (Offline Save, Edit/Delete, Auto-Reconnect Sync — how many seconds?):
- Task 4 (Map Pins, Filters, Community Feed, Upvoting):
- Task 5 (Official Status Update & Push Notification — how many seconds?):
- Task 6 (Outdoor GPS Points contributed, if assigned):

SUS SCORES (1 to 5):
Q1: _  Q2: _  Q3: _  Q4: _  Q5: _  Q6: _  Q7: _  Q8: _  Q9: _  Q10: _
Computed SUS Score: ___ / 100

BUGS / GLITCHES FOUND (Screen name, what you tapped, what happened, screenshot attached?):
1.
2.

UI / UX SUGGESTIONS (What felt confusing, slow, clipped, or could look/feel better?):
1.
2.
```
