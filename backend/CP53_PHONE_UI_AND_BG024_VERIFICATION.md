# CP-53: physical-phone verification of CP-51 UI and CP-52 hardening, design-skill pass, BG-024 and push re-run

November 24, 2026. TECNO CL8, Android 15, released by its owner over USB, 72% to 71%, real home Wi-Fi + LTE. The phone's serial is not recorded. The phone started **signed in as the owner's demo account**, so the standard backup/swap/restore path was used (never uninstall or clear data; in-place `adb install -r`).

## Phase 1: CP-51 UI on the real screen, design-skill pass, polish

- **Skills used:** `impeccable` (context loader, native-Android `critique` + `audit` method: design review alone, the web detector does not apply to Compose) and the pinned reference brief. The reference PDF's 10 boards were rendered (PyMuPDF, installed locally for this) and compared with real-phone screenshots of Home (1.0x and 1.3x), Report form (top and bottom, 1.0x and 1.3x), My Reports, Map, Report Details, Notifications, SMS Guide and Profile. `frontend-design` was not separately run (the PDF wins over generic style, and no new visual world was wanted). No extra skill was installed.
- **Not captured on the phone:** Login, Register, Forgot/Reset password, permission screens and the logout dialog. The owner is signed in; viewing them would mean signing the owner out (revoking their session). They were verified on the emulator in CP-51; the phone shows no regression for the signed-in screens.
- **Critique summary:** hierarchy, borderless `#F5F5F8` cards, dark status-bar icons (BG-021 fix holds on the new Home), floating bottom bar with the protruding violet Camera FAB clearing scroll content at 1.0x and 1.3x, charcoal Active Report card with pager dots, Quick Report grid, SMS dark hero, overlapping Report Details hero and clean timestamps all match the reference. Category tiles follow the reference palette (gray road tile, etc.).
- **Real-screen defects found and fixed:**
  1. **Map pins hidden by the floating chips and legend (CP-51 regression).** The camera framed the loaded reports with 80 px padding, so the two edge pins landed under the filter chips and the legend and the map looked empty ("6 loaded pins" with none visible). `ReportMap.kt` now frames with asymmetric padding (100/260/100/240 px). Verified on the phone: both pins visible.
  2. **Latitude/Longitude fields unequal height** (the longitude label wrapped at 1.0x). Labels use `bodyMedium`; the text is unchanged so tests keep working. Verified on the phone.
- **Observation, not changed:** the Report form is a single scrolling page with a 1/5 progress header; the PDF shows five separate screens (accepted scope in CP-51).
- **Regression on the polished build:** 13 suites, **84/84 pass** (run on the CP-52 build and again on the polished build; includes CP-52's three photo-drop tests), and the real-SIGKILL matrix **S1-S7 pass** on both (no harness errors). The owner session file changed hash during the run (token refresh; same account stayed signed in) and the device-prefs file did not.

## Phase 2: BG-024 and FCM re-verification (fixture citizen only)

Prepared with a temporary installation id; the owner's session was not signed out and the owner's two device rows were not touched.

**Submission cohort: 40 labelled submissions (20 Wi-Fi, 20 cellular only), real workers, client-observed enqueue to Synced.**

| Condition | N | Mean | Median | p95 | Max | <= 10 s | Max app payload |
|---|---|---|---|---|---|---|---|
| Wi-Fi | 20 | 0.85 s | 0.89 s | 1.11 s | 1.13 s | 20/20 | 134.4 KB |
| Cellular only | 20 | 1.30 s | 1.23 s | 2.08 s | 2.10 s | 20/20 | 134.7 KB |

No trial exceeded 8 s, so no slow trial needed the phase breakdown (`worker_start_delay_ms`, `submit_exec_ms`, `worker_attempts`, `worker_last_error` were recorded for all 40). The two ~27 s outliers of CP-50 did **not** recur in 40 trials; whether CP-52's hardening fixed them or they were transient cannot be separated by this data. BG-024 is therefore recorded as **not reproduced in 40 physical trials (hardened)**, not as proven fixed.

**Push cohort: 5 clean `real_fcm_phone.py` runs = 20 cases (all passed, outbox attempts 1, no error).** Seconds; `dispatch` = notification created to FCM accepted (server), `tray` = created to visible in the phone's tray (polled); `transit` = tray - dispatch (FCM to phone, incl. polling granularity).

| Run | P1 foreground tray/dispatch | P2 Home | P3 destroyed | P4 screen off |
|---|---|---|---|---|
| 1 | 14.1 / 11.5 | 3.4 / 1.2 | 9.8 / 9.4 | 8.8 / 8.1 |
| 2 | 9.0 / 6.8 | 3.5 / 1.5 | 10.8 / 9.3 | 6.6 / 5.9 |
| 3 | 8.0 / 6.5 | 4.6 / 2.6 | 7.6 / 6.6 | 6.8 / 5.6 |
| 4 | 4.8 / 3.1 | 5.5 / 3.2 | 10.8 / 9.5 | 6.7 / 6.2 |
| 5 | 6.8 / 5.1 | 3.6 / 1.4 | 10.8 / 9.4 | 5.7 / 5.2 |

Max 14.1 s; all 20 under 30 s; no 42.5 s case recurred. Transit from FCM acceptance to the tray is about 0.5 to 3.3 s; most of the latency is server-side dispatch (the dispatcher runs on a schedule, up to about 11.5 s). All 15 tap cases opened Report Details; screen-off is observation only. No tap retry was needed.

**Cleanup:** every trial report, photo and original removed (byte-absence checks), queue rows removed, fixture registration removed (0 left), phone signed out of the fixture and the owner's saved session and installation id restored (the app shows the owner's signed-in Home). Hosted baseline identical to the start: 36 reports, 32 + 13 objects, 15 notifications/history/outbox, 2 owner device rows unchanged, `catalog_drift.py` 0 differences. The phone-side fixture sign-out's HTTP 204 was not independently confirmed (the host sessions' were).

## Gates

Backend `npm test` and Edge check pass; JVM unit tests pass; lint 0 errors; release APK (unsigned) DEX audit: 0 hits for secret, service-role, JWT, fixture or loopback strings (positive control found the project host). Signed release install: **Not run** (no keystore).

## Phone state

`stay_on_while_plugged_in` restored to 0, font scale 1.0, screen timeout 600000, Wi-Fi and mobile data on, owner signed in (no sign-out).

## Not run / owner-only

Real camera photo, 10-point outdoor GPS walk, 10-15 SUS forms, Brevo SMTP, spare SMS gateway phone, $0 restore quote, signed release keystore, auth screens on the physical phone (would need a signed-out phone).
