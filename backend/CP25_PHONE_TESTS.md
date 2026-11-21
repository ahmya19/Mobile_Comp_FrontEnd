# CP-25 phone test sheet

Run on the TECNO phone with the debug build from commit 225bfd5 or later. Record only what you measure. Leave a row blank rather than guess. Use a demo citizen account; never write passwords or tokens in this file.

Before starting: battery above 50%, Wi-Fi and mobile data both on, location on, notifications allowed for PinPoint. If the phone shows a "Save password?" popup, choose Not now or Never.

## A. Offline sync (target: 0 lost reports)

Repeat 10 times. Each run: turn on airplane mode, submit a report with a photo, confirm it shows as saved on the device, force-stop PinPoint, reopen it, turn airplane mode off.

| Run | Time | Appeared as Pending after reconnect (Y/N) | Seconds until synced | Photo shown (Y/N) | Notes |
| --- | --- | --- | --- | --- | --- |

Pass condition: all 10 synced once, no duplicate, photo present.

## B. Wi-Fi to mobile data switching (target: no lost or duplicated upload)

Repeat 5 times. Start a report upload on Wi-Fi, switch Wi-Fi off during the upload so it continues on mobile data (or waits), then check that exactly one report exists.

| Run | Switched at (s into upload) | Final state | Duplicate? | Original photo waited for Wi-Fi? | Notes |
| --- | --- | --- | --- | --- | --- |

## C. GPS accuracy (target: within 20 m)

Outdoors, clear sky. Stand at a landmark whose true coordinates you can read from a map app (long-press the exact spot). Use "use my location" in the report form 5 times at different places.

| Spot | True lat,lon | App lat,lon | Error in metres | Seconds to fix | Notes |
| --- | --- | --- | --- | --- | --- |

Error can be computed from the two coordinates; ask Claude to compute it from the table.

## D. Push timing (two phones)

Needs a second phone or the emulator for the official account. Citizen phone: submit a report. Official: change its status. Note the clock time of each step.

| Run | Status change (hh:mm:ss) | Notification on citizen phone (hh:mm:ss) | Seconds | App state (foreground/background/closed) |
| --- | --- | --- | --- | --- |

## E. SUS questionnaire

Needs real participants. Use the standard 10-item System Usability Scale; target average at least 70. Record participant count and scores separately.

## Environment record

Phone model and Android version, build commit, SIM carrier, date, who ran each section.
