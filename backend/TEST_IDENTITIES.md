# Project-owned test identities (CP-42)

November 23, 2026. Created through trusted Auth administration as approved by the group's decision-maker. They are **permanent test identities**, distinct from the **disposable run fixtures** (reports, photos, notifications, device rows) that each test run creates and removes.

| Identity | Email (label, not a mailbox) | Role |
| --- | --- | --- |
| PinPoint TEST citizen (fixture) | `pinpoint-test-citizen@pinpoint-fixtures.example.com` | citizen, no row in `private.officials` |
| PinPoint TEST official Alangilan (fixture) | `pinpoint-test-official@pinpoint-fixtures.example.com` | assigned to Alangilan through `private.officials` |

Both are marked `app_metadata.pinpoint_fixture = true` (admin-only, users cannot set it), were confirmed by the admin (no email is sent, public signup and email confirmation policy are unchanged) and have random passwords stored only in the private file `work\supabase-fixture-identities.json` (outside Git, `"fixture": true`, same shape as the demo-accounts file). The mailbox domain is the reserved `example.com`; it receives nothing.

## How they were made (and how to repeat or undo)

`backend/scripts/provision_test_identities.py` uses the Auth Admin API. The project's secret key is obtained **in memory** from the logged-in Supabase CLI (`supabase projects api-keys --reveal`), sent only in a header, and never printed, logged, stored or placed in an error message; nothing is inserted into `auth.*` with SQL. Order: exclusive ledger → exclusive credentials file written before any creation → refuse if an email exists → create (ids ledgered) → official role row (`insert into private.officials ... 'Alangilan'`) → ordinary-sign-in verification (citizen is not an official, official is Alangilan, no device registrations, the two demo accounts' device rows unchanged) → local logout of the verification sessions. A failure rolls back only the ids that run created. `--verify-only` re-checks existing identities. 15 mocked tests cover this (ledger first, no secret in output, rollback, refusal of existing emails and files). Run `python backend\scripts\provision_test_identities.py --demo-credentials <demo json> --work <private dir> --out <new private json> [--apply | --verify-only]`.

To remove them: delete the two users in the Supabase dashboard (Authentication > Users); the officials row cascades.

## Rules for using them

- Use them for **every** test that changes a report status or otherwise triggers a push. Their device registrations belong only to owned emulators; the shared demo citizen's two registrations (possibly the user's phone) are never a recipient.
- `workflow_hosted_regression.py` now **refuses any credentials file without `"fixture": true`**, refuses to start if either fixture identity already has a device registration (so no other device can receive the push), waits for the fixture's push outbox rows to be delivered before deleting anything (it never races the dispatcher), and afterwards removes only the two fixture identities' registrations (guarded SQL that re-checks the fixture label).
- Never embed these credentials in the APK or Git; hand them over privately.

## Verified run (hosted, emulator `emulator-5556`)

`workflow-fixture-20261123-a01`: citizen creates a report with a photo, the official changes its status, the citizen opens the notification (three UI phases passed: 13.0 s, 17.7 s, 6.9 s; observations only). The real push went through the deployed route to the fixture citizen's emulator registration (outbox delivered, attempt 1). Run identity came from the on-device receipt; cleanup was one guarded transaction plus the object delete, then the fixture registrations; fixture cleanup `cleaned`, session cleanup `confirmed` (both HTTP 204), no leftovers against the 8-table baseline. Independent read afterwards: 36 reports, 15 notifications/history/outbox, 2 device rows (both the demo citizen's, untouched), 32 photo objects, 2 fixture identities. This does not prove physical timing, field acceptance or the public-signup email path.
