# Physical phone: real push, real picker, live backend (TECNO CL8, Android 15)

November 23. The user released the phone for this run and approved both the stack merge and these tests. Phone: USB, awake, signed in as the demo citizen with notification permission granted, left signed in and with settings as found (screen sleep/wake and one BACK key press only; `stay_on_while_plugged_in` read back as 2 = USB, the value already in use). Debug app and test APKs were updated in place (data kept). Observations only: this is **not** CP-25 latency or OEM acceptance. Private evidence: `work\push-phone-20261123-p1`, `work\picker-phone-20261123-a1` (outside Git).

## Real FCM to the phone (`backend/scripts/real_fcm_phone.py`)

The demo citizen created a labelled fixture report; the demo official changed its status through the ordinary RPC (a genuine reporter/official pair). Guards before each send: the phone's installation was among the citizen's two device rows and no row was added; the official had no registrations.

| Case | FCM accepted by outbox | Exact notification ID in tray | Tap opens the fixture |
| --- | --- | --- | --- |
| P1 foreground | yes, attempt 1 (dispatched 6.3 s after creation) | yes (7.5 s) | yes |
| P2 background (Home) | yes (8.8 s) | yes (9.5 s) | yes |
| P3 activity destroyed (BACK) | yes (7.3 s) | yes (8.4 s) | yes |
| P4 screen off | yes | yes (10.7 s) | not attempted (no unlock) |

Cleanup: the fixture's 4 notifications, history rows and the report were removed by exact ID after every outbox row was delivered; counts returned to 36 reports, 15 notifications, 15 outbox rows and 15 history rows. The citizen still has exactly 2 device rows and the phone's row is kept (no sign-out, no registration change; the dispatcher deleted nothing).

## Real system photo picker on the phone

- `PhotoCompressorTest` 8/8 and `NativePickerTest` 3/3 (twice) on the phone, using generated images and the loopback server with an isolated test session. The TECNO picker keeps its sheet open after a tap and needs a confirm button; the test helper now clicks it when present (emulator still 3/3).
- Live backend (`backend/scripts/phone_picker_hosted.py`, `PhonePickerHostedTest`): the existing real session, the real picker and one generated 3000x2000 image produced exactly one report owned by the citizen with `photo_path = <uid>/<report id>/photo.jpg`, no original, and a private object of 3 017 bytes that decodes to 750x500 (so the generated image, not a user photo, was chosen). The fixture report and object were then removed through guarded SQL and the ordinary Storage API; the report count and the exact object-name list matched the baseline.
- Picker grant lifetime (`PickerGrantProcessDeathTest`, observation): once the process and task are gone, the picker's returned URI throws `SecurityException`, while the app's own MediaStore file stays readable. The form's selection state is also gone with the task, so users should not reach this state; if it were reached, submit shows an error and leaves no queue row (BG-012).

Not covered: camera capture (the app only uses the system picker), reading a user's own photo, process death with the task still alive.

## Outbox retry coverage

Already complete in the nine-migration replay suite (lease reclaim with a new token, wrong-lease finish ignored, 10 s·2^n backoff capped at 30 minutes, recipient-only devices); no new test was needed.
