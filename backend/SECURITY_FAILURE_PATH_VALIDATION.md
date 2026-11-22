# Backend security and failure-path validation, November 23, 2026

Checkpoint CP-32. Two layers, kept separate on purpose:

1. **Local PostgreSQL (PGlite) on the final schema** (`backend/tests/security-failure-paths.test.mjs`, 9 tests, runs in CI). RLS, grants and function logic are real; Auth token validity, PostgREST and the Storage API are simulated.
2. **Hosted ordinary-client probe** (`backend/scripts/hosted_authz_probe.py`, 49 probes). Real PostgREST, Auth and Storage using the public key and two demo sessions. Privileged SQL is used only to pick targets and to verify results; it is not evidence of what a client may do.

## Hosted result (November 23, 2026)

**49/49 probes passed** in the final run. Two earlier runs of the same script are kept privately: the first stopped on a data assumption (every existing report is owned by the demo citizen), the second scored 47/49 because my classifier wrongly treated the intended `PGRST106` ("schema not exposed") refusal as a failure. The classifier was corrected; no service change was made. Sessions were local-scope and signed out. Hosted state before and after: 36 reports, 45 storage objects, 15 notices, 0 votes. The one lifecycle object created in each run was deleted through the ordinary API and verified absent by SQL.

| Area | What was probed and refused or allowed |
| --- | --- |
| Anonymous or garbage token | read reports and submit_report: HTTP 401 `42501`; garbage bearer: 401; list report-images: empty |
| Service-only RPCs as a citizen | `claim_push_batch`, `finish_push`, `authorize_push_dispatch`, `remove_invalid_push_token`, `ingest_sms_report`: all HTTP 403 `42501` (arguments were harmless even if allowed) |
| Private schema | `private.officials` and `private.push_outbox` via the API: HTTP 406 `PGRST106` (schema not exposed) |
| Direct table writes as a citizen | insert on reports, notifications, devices, report_votes, profiles, report_history; update and delete on reports; update on notifications: all HTTP 403 `42501` |
| Roles and ownership | citizen `update_report_status`: 403; official `attach_original` on a report they do not own: 403; `my_profile` reports citizen as not official and official as official |
| Cross-account reads | notifications, devices, profiles and report_votes of the other account: empty, both directions |
| Storage forbidden uploads | another account's folder, wrong file name, extra path segment, original for a nonexistent report: all Storage `403`; SQL confirmed no object was created |
| Storage allowed lifecycle | citizen uploads an own orphan photo (200), reads it and lists the own folder; the official cannot read, list, sign a URL for, or delete it (object survived); anonymous cannot read or sign; overwrite refused; citizen deletes it (200); verified gone |

## Local risk and coverage table

| Risk | Executable test | Intended allowance proven | Denial proven |
| --- | --- | --- | --- |
| Request without a session reaches a mutating RPC | no-session matrix | passive helpers return nothing harmlessly | six RPCs refuse with 28000 and report, device, vote and unread counts are unchanged; anon gets 42501 |
| Client writes tables directly | direct-writes matrix (every table; insert, update, delete, truncate; two roles) | n/a | 42501 everywhere; four private tables unreadable |
| Device ownership and removal | devices test | token refresh updates one row; account switch moves the row; own removal works | empty or 4097-character token rejected (23514); other account cannot read or remove; one row per installation |
| Cross-account notification read or mark | recipient-bound test | owner marks read, idempotent | other citizens and the official cannot read or mark |
| Push work leaks across barangays | pending-push test | an official of the barangay sees pending reports | other-barangay official and citizen see none; delivered work disappears |
| Retry after partial completion | convergence test | lost acknowledgement, retry after a status change and repeated `attach_original` converge to one row, one notice, one history row | another account replaying the ID: 42501; changed content on retry: 23505 |
| Photo or upload path abuse | Storage matrix and submit-path test | owner uploads the exact `<uid>/<uuid>/photo.jpg`; submit binds to caller and report id | another folder, wrong name, extra segment, upper-case id, unknown bucket, anonymous, original before a report exists, another user's uploaded photo, a photo bound to a different report id |
| Overwrite, delete and visibility of photos and originals | Storage matrix | owner deletes an own unreferenced photo; committed photos community-visible; an official sees an original only once attached | no overwrite (by policy, not grant); referenced photo not deletable; strangers cannot delete orphans; nobody can delete originals; others cannot see originals |
| Dispatcher abuse (nonce reuse, expiry, caller) | replay tests (see MIGRATION_REPLAY_VALIDATION.md) | one use accepted | replay, expired, malformed and wrong role rejected |

## Mutation evidence

Ten deliberate breaks of security-relevant statements were replayed through this file. Seven were caught immediately. Two tests were weak and were corrected: the photo-binding test passed for the wrong reason (the uploaded object did not exist, so a different rule fired), and the cross-account retry assertion was missing. After the fixes both breaks are caught. One break survives by design: granting INSERT on `public.devices` is still refused by RLS (defense in depth), and the grant-matrix test in the replay file does catch that grant.

## Findings

No exploitable authorization defect was found. Observations, not fixed (owner or group decision):

- **BG-005 (open, decision needed):** an official's priority-only change keeps the status but writes a history row `Under Review -> Under Review` and sends the owner a second "report is under review" notification. Pinned by a characterization test. Fixing it needs a forward migration for `update_report_status`; proposed rule: when only the priority changes, update priority and `updated_at` without a history row or notification.
- `register_device` moves a device row to whichever account presents the installation id. This is needed for account switching on one phone and requires knowing that private id. Recorded in a test, not changed.
- Originals have no delete policy, so an abandoned original upload cannot be removed by its owner (storage growth). The photo bucket allows orphan cleanup; the originals bucket does not.
- Upload paths accept any 36 hex-or-dash characters as the id segment. Submit requires the exact report UUID, so a junk path is just an orphan.

## Not covered

Expired-JWT behavior (needs a validly signed expired token), rate limits, Storage behavior with very large or non-JPEG bodies, signed-URL fetching, and write scenarios beyond the single lifecycle object. Concurrent claimers need two real connections.

## Reproduce

~~~powershell
cd backend
npm test
python scripts/hosted_authz_probe.py --credentials <private accounts.json> --work <fresh private dir outside the repo>
~~~

The probe refuses a work directory inside the repository and an existing ledger, prints no tokens, and signs out with local scope.
