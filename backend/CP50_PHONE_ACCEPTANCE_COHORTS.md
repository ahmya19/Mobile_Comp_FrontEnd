# CP-50: physical-phone acceptance cohorts (Phase 4), fixture citizen only

November 23, 2026. TECNO CL8, Android 15, released by its owner over USB, awake, 83% charging, real home Wi-Fi plus real mobile data (LTE). The phone's private serial is not recorded. Everything ran against the real hosted project with the dedicated fixture citizen and official only. These are measurements of one phone, one network and one afternoon at the declared sample sizes; they are not statistical guarantees.

## Safety envelope (how the owner's phone was protected)

- Before the swap the owner's secure-session file and installation-id file were copied (inside the app's private storage and, privately, on the host), hashed, and the copies verified. The app was given a **temporary installation id**, so the owner's two shared-demo device registrations were never touched; the fixture citizen's only registration was that temporary id (checked: exactly 1 row).
- The fixture was signed in **without signing anyone out** (the owner's server session was not revoked). Afterwards the app signed the fixture out, the fixture registration and every trial report, compressed object and original object were removed (trusted Storage API, then GET 400/404 byte-absence checks), the queue rows were deleted, and the owner's saved files were copied back with **SHA-256 equal to the pre-run hashes** (asserted). The app then showed the owner's signed-in Home.
- Final hosted state equals the start: 36 reports, 32 + 13 objects, 15 notifications/history/outbox, 2 shared-demo device rows with identical fingerprints and timestamps, 0 fixture registrations; `catalog_drift.py` 0 differences. Wi-Fi and mobile data are on again; `stay_on_while_plugged_in` restored to 0, screen timeout 600000 ms, font scale 1.0.
- Not independently confirmed: an HTTP 204 for the phone-side fixture sign-out (the app performs it; the host drivers' own REST sessions were confirmed).

## A. Wi-Fi to cellular handoffs during a real TUS original upload

Declared up front: 5 trials x 2 deliberate Wi-Fi-off/on handoffs = 10, 9 MB synthetic original each (more than one TUS chunk), real app queue and workers, real HTTPS storage endpoint.
Result: **5/5 trials completed with exactly 1 handoff each** (the upload finished in about 37 s, before a second handoff window opened), so the declared 10 was **not reached; 5 handoffs were performed**. 5/5 originals downloaded by the fixture citizen with matching SHA-256, attach recorded. Cellular interfaces (`ccmni*`) carried 52 to 130 KB total (rx+tx) per trial against 9 MB originals (idle noise about 2 KB per 20 s), so no full original crossed cellular. The single-shot `hosted_original_tus_smoke.py --serial` physical run was not separately executed (this cohort exercises the same path through the real queue).

## B. Submission latency and payload (compressed report: 10 on Wi-Fi, 10 on cellular only)

Client-observed time from enqueue to Synced (real workers): Wi-Fi mean **3.9 s** (median 1.3 s), cellular mean **4.8 s** (median 2.3 s); both under the 10 s mean target. App payload (compressed photo + RPC body) max **134.9 KB**, under 300 KB; originals were not part of these trials. **Two of 20 submissions took about 27.5 s** (one per condition); the cause is **not investigated** (see BG-024). Per-submission 10 s pass count: 18/20.

## C. Offline then reconnect, 60 s limit (12 trials: foreground, Home, screen-off x Wi-Fi/cellular x 2)

Both radios off (confirmed unreachable) while the report was queued; the report was not on the server while offline. After the first successful network ping, time until the report was visible to an ordinary REST read: **12/12, max 5.2 s**, Retry never pressed, host clock. Screen-off used a sleep key event with USB power. Battery Lab "No Restrictions" for PinPoint was **not verified or changed** (default state).

## D. Fixture-pair FCM push timing (real FCM; fixture official by REST to the phone-registered fixture citizen)

Eight runs. Runs 3, 5, 6, 7, 8 completed all four cases (foreground, Home, activity destroyed, screen-off) = **20 cases, all delivered with exact notification-ID correlation and outbox acceptance; the 15 tap cases opened Report Details for the fixture report** (screen-off is observation only). Tray-observed time (polling, so an upper bound): median 8.6 s, range 3.3 to 18.5 s except **one 42.5 s foreground case (over the 30 s target)**. Runs 1, 2 and 4 stopped at the first case because the tap step could not find the notification in the shade (delivery itself had arrived at 13.6 s and 3.1 s); that is a harness shade-timing issue, and a retry step (recorded as `tap_first_attempt_missed`) was added; it was not needed in runs 6 to 8. Counted honestly: 3 aborted runs, 5 clean runs.

## Harness changes

`phone_acceptance.py` (+ `PhoneAcceptanceTest`) drives A to C and the safe swap/restore; `real_fcm_phone.py` was updated to the fixture-pair guards (`cuid` alias, protected shared-demo fingerprint check, official registration check); `real_fcm_probe.py` got the tap retry and a shade-text diagnostic.

## Not run

Brevo email, SMS gateway, outdoor GPS, SUS, restore project, real camera shot, signed-release install.
