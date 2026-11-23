# Hardened authorization driver — hosted smoke, November 23, 2026

Codex verified the ordinary-client authorization driver after CP-39/BG-014 merged, using source candidate `eb20b70ee2f4c67da83a0b4acd86c1c80b91de6f`. PRs #16, #17 and #18 were independently confirmed merged with green checks. This follow-up changes documentation only; no driver, runtime, migration, policy, secret or billing change was made by this run.

## Result

`hosted_authz_probe.py` exited **0**, with **49/49** checks passing. The API URL, public publishable-key type and CLI linked project were checked against the expected project before execution. Ordinary citizen/official sessions used the existing private credential file; no privileged key was placed in the app or used for client authorization assertions.

The allowed orphan-image lifecycle uploaded one tiny generated JPEG, read/listed it through the expected client routes, rejected overwrite/cross-account access attempts, deleted it through the owner's ordinary Storage API (HTTP 200), and independently verified the exact object absent. All four forbidden upload paths were recorded before their requests and individually verified absent. Their recorded states were all `absent`; no cleanup-required path remained.

Final ledger outcomes:

| Field | Observed result |
| --- | --- |
| Fixture cleanup | `cleaned` |
| Citizen local logout | `confirmed`, HTTP 204 |
| Official local logout | `confirmed`, HTTP 204 |
| Session cleanup summary | `confirmed` |
| Ledger errors | 0 |
| Cleanup required | 0 |

The two HTTP 204 results confirm that this run's local-scope logout requests succeeded; this is not a claim that already issued access JWTs instantly become cryptographically invalid. No global sign-out or device-registration mutation was performed.

## Independent preservation check

Separate privileged read-only SQL captured counts and full-row fingerprints before and after the run. All 12 saved tables matched exactly:

| Table/group | Baseline and final count |
| --- | --- |
| Reports | 36 |
| Notifications | 15 |
| Report history | 15 |
| Push outbox | 15 |
| Votes / SMS receipts | 0 / 0 |
| Device registrations | 2 |
| Storage objects / buckets | 45 / 2 |
| Profiles / assigned officials / barangays | 2 / 1 / 3 |

Thus the original business rows, notice read states, outbox rows, device registrations and photo/bucket metadata were preserved, rather than merely restoring aggregate counts. No report, status change or push event was created by the run. No phone or emulator command was issued; the stopped emulator was left stopped.

## Reproduction and evidence

From the configured project root, with a fresh private work directory and an existing private confirmed-account JSON:

~~~powershell
python backend/scripts/hosted_authz_probe.py --credentials <private accounts.json> --work <fresh private directory outside Git>
~~~

Private evidence in the Codex task workspace: `work/hardened-authz-smoke-20261123-c01/` contains `baseline.json`, `console.log`, `authz-probe-ledger.json`, `authz-probe-result.json` and `independent-after.json`. The ledger contains identifiers/outcomes, not account passwords or tokens. Preserve this batch rather than overwriting it.

## Scope and next boundary

This closes the hosted happy-path smoke for the **authorization driver only**. Failure-path guarantees remain backed by the separate mocked-transport/PGlite tests in [HOSTED_TOOLING_FAILURE_SAFETY.md](HOSTED_TOOLING_FAILURE_SAFETY.md); the hosted service was not deliberately broken to reproduce a dangerous cleanup.

The hardened whole-app workflow and FCM drivers were **not run**. The workflow performs an actual status transition and creates a citizen notification. Existing citizen registrations may belong to the user's unavailable phone, so its smoke must wait for safely isolated dedicated test identities/registrations. The group has already approved creating those identities through trusted administration; do not ask the same product question again or remove existing registrations to isolate a test.

The separate BG-005 policy decision is also already approved: priority-only changes should update priority without duplicate status history/notifications/outbox. It is no longer an unanswered group decision. The active shared checkout is being used for that work; this evidence was prepared in an independent worktree.
