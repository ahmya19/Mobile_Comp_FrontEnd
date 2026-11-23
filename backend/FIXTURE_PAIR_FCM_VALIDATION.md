# Distinct citizen/official real-FCM validation (CP-43)

November 23, 2026. Source baseline `11821a1` (PR #22). This follow-up changes test tooling and Android instrumentation only; the application APK remains SHA-256 `9b583ffbf086ac74f061169fac4cfea496ed30f5b7ba3544711e58e4dc79eba1`. No runtime behavior, schema, Edge deployment, project-secret or billing change. The physical phone was not queried or operated.

## Isolation and assertions

The permanent, confirmed, admin-labelled fixture citizen owns the report and receives the notification on the owned emulator. The distinct fixture official, assigned to Alangilan, changes status through ordinary REST. Both begin with zero device registrations. Before every mutation, the probe checks fixture labels/roles, exact report owner/run marker, the citizen's one expected emulator registration (zero in the signed-out control), no actor registration and full-row fingerprints of all non-fixture device rows. Shared demo/phone accounts are never recipients.

The run records its intent and all twelve protected table fingerprints before hosted writes. Cleanup locks and verifies the exact citizen-owned report, history actors and all dependent references in one transaction; pending pushes or unexpected references refuse deletion. Device cleanup is restricted to the hashed, ledger-proven emulator installation. Each cleanup step and REST-session teardown runs independently. Failed observations and unresolved sign-out/cleanup now produce a nonzero exit even when other cleanup succeeds.

The probe verifies the app's current account/project, notification permission/channel and registered-token digest without exporting the token. Before each case it clears only this test installation's notifications. A positive tap requires the exact expected notification ID to be the only PinPoint tray entry, then the fixture description in Report Details. Activity destruction is explicitly performed and observed through Android's lifecycle monitor; pressing Back was insufficient proof.

## Investigation and failing attempts

Private cohorts `fixture-pair-fcm-20261123-a01` through `a03` missed the foreground tray deadline despite outbox completion. Readiness passed; deployed payload and recipient binding were checked. Debug-only, identifier-free handler traces were temporarily installed to distinguish receipt from an app gate. Those traces were removed before the final build.

In diagnostic cohort `a04`, refreshing the signed-out emulator's cached FCM token did not produce an immediate receipt. An explicitly recorded emulator-only Wi-Fi/data reconnect at 13:45:32 (UTC+8) produced four receipt/posted trace pairs at 13:45:35. This supports a stalled emulator delivery connection; it does not establish its underlying network cause or prove four distinct deliveries from those identifier-free traces alone. The 54.2-second tray observation is intervention-assisted and is not acceptance evidence. Identical notification bodies also made the old UI-text tap ambiguous among queued fixture notices; this run correctly failed destination verification. All four attempts ended with exact fixture/device cleanup, confirmed REST-session teardown, UI sign-out and restored baseline fingerprints.

The old script could log a failed case yet return success solely because cleanup passed, used a shared self-official recipient, treated Back as activity destruction and selected identical-body notifications without excluding stale entries. BG-016 records these tooling defects. Historical observations remain historical; they do not prove this stricter matrix.

`a05` passed foreground, destroyed-activity/tap/rotation, denied/granted permission, force-stop, sign-out and registration recovery, but stopped before the final recovery send because its immediate post-clear tray check was nonempty. No final recovery push was sent, and the run remained failed despite successful cleanup. The final harness waits for Android cancellation completion and reads `cmd notification list` active keys rather than parsing the broader notification diagnostic dump.

`a06` passed foreground and destroyed-activity/tap/rotation, then correctly suppressed the denied-permission notification while showing its in-app history. Its extra assertion requiring a new system dialog failed after previous denials had suppressed further prompts. Cleanup passed. The permission oracle now independently requires exactly one denied runtime-permission state plus in-app visibility and no tray entry; it records whether a dialog was shown separately. The real denial dialog was exercised successfully in `a05`. A missing or ambiguous OS permission state fails closed.

## Final build and matrix

Final cohort `fixture-pair-fcm-20261123-a07` passed all six scenario groups, with no network reconnect or token-refresh intervention during that cohort. It used the unchanged application APK and final instrumentation APK identified below.

| Scenario | Result | Observation |
| --- | --- | --- |
| Foreground | Pass | Exact notification ID, sole active entry, tap opens the citizen-owned fixture; tray observed at 12.0 s |
| Destroyed activity and rotation | Pass | Lifecycle monitor observes destruction; real push/tap opens the fixture; details survive rotation; 10.8 s |
| Permission denied | Pass | OS reports denied, no tray entry during the 45 s negative window, update visible in-app. Repeated-denial state showed no new prompt; the real denial dialog was covered in a05. |
| Permission re-granted | Pass | Exact ID/tap/destination; 7.9 s |
| Force-stop control | Pass | Package stays stopped and no tray entry during the bounded negative window. Delivery while force-stopped is not promised. |
| Sign-out and signed-out status update | Pass | Tray empty, recipient registrations zero, protected registrations unchanged. The no-device update completes its outbox with no tray entry. |
| Sign-in recovery | Pass | Exactly one row for the original installation; a new real push has the exact ID/tap/destination; 4.5 s |

These elapsed times are host-observed functional samples, not a phone latency study or CP-25 acceptance. The driver field `fcm_accepted_by_outbox` describes an outbox flag; the positive device ID/tap observations provide receipt evidence. A zero-device outbox completion is not a Google send.

Cleanup passed: the one report and its seven status notifications/history/outbox events were removed, fixture registrations are zero, both host REST logouts returned HTTP 204 and the app UI is signed out. An independent MCP read matched all twelve full-row table fingerprints to the original pre-a01 baseline: reports 36, notifications/history/outbox 15 each, votes 0, devices 2, objects 45 (32 compressed and 13 originals), buckets 2, SMS receipts 0, profiles 2, officials 2 and barangays 1. Both permanent confirmed fixture identities remain.

An extra Auth check found one older fixture-citizen session predating a01. After verifying the dedicated fixture label, citizen role and zero registrations, ordinary Auth global sign-out closed only that fixture citizen's sessions (HTTP 204); the cleanup token was then already-invalid, as expected. Independent Auth read: zero sessions for either fixture identity. Shared demo accounts were unaffected. Emulator diagnostic properties/files were cleared and only the owned emulator was stopped. No phone command was issued.

Private evidence lives outside Git under the Codex workspace's `work/fixture-pair-fcm-20261123-*` folders (ledger, matrix, logs and the explicit network-intervention receipt). Account IDs, passwords, tokens and installation identifiers are omitted from this report.

## Local verification

- Backend suite: **76/76**, including eighteen mocked Python fixture-pair cases and six Node/PGlite wrapper/transaction cases. Generated cleanup SQL is executed against the full eleven-migration replay, including pending-delivery, changed-label and unexpected-actor refusal cases.
- Three failing controls turn the mocked suite red: suppress failed-observation exceptions, route the reporter through the official session, or remove the fixture-label check.
- Edge type check passes. Android debug/test builds pass; JVM **11/11**; lint **0 errors, 37 warnings**.
- Application APK: `9b583ffbf086ac74f061169fac4cfea496ed30f5b7ba3544711e58e4dc79eba1`; final test APK: `c04ece4d23331dd96fe8c8fe3281009a45a4e4993c3dec313de1522f6a53e41f`.

## Reproduce

Build/install both APKs on an explicitly owned emulator, then use a fresh private work directory:

```powershell
python backend/scripts/real_fcm_probe.py --credentials <private fixture JSON> --work <fresh private directory> --serial <owned emulator serial>
```

The driver refuses physical serials, shared/unlabelled accounts, reused evidence directories and credentials/work paths inside Git. Keep a healthy emulator FCM connection; a reconnect performed during a timed case must be recorded as an intervention, not a normal delivery pass. A failed matrix exits nonzero and still attempts scoped cleanup. Cleanup-only is for the exact existing ledger, not a fresh run or broad reset.

Limits: emulator functional observations are not CP-25 phone/network statistical acceptance. Duplicate delivery and late payload after an account switch remain simulated-only evidence from earlier suites. SMTP, spare SMS gateway, actual Free-project restore, TUS interrupted transport, abandoned-upload cleanup, labelled demos, approved UI parity, field measurements and SUS remain distinct completion gates.
