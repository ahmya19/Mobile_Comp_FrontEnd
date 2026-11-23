# Approved policy and Alangilan — deployed November 23, 2026

The two forward changes merged in PR #19 at afd64a5 are **deployed and hosted-verified**. No phone/emulator access, Auth session creation, Edge deployment, secret or billing change was needed.

## Exact scope and history

Only the two reviewed forward files were applied through the Supabase migration tool. Its atomic transaction replaced their outer BEGIN/COMMIT wrappers; their statements/function body were otherwise unchanged. The tool recorded these versions, preserving the existing nine history entries exactly:

| Repository file | Hosted version | Hosted name |
| --- | --- | --- |
| 202611230010_priority_only_status_policy.sql | 20261123043105 | pinpoint_priority_only_status_policy |
| 202611230011_alangilan_jurisdiction.sql | 20261123043113 | pinpoint_alangilan_jurisdiction |

There are now 11 hosted migrations. Their timestamps differ from repository prefixes. **General `supabase db push` remains unsafe until a separately reviewed reconciliation accounts for all mappings.** Do not replay creation files, mark unknown changes applied or rewrite the older nine records to silence a CLI mismatch.

The entire deployed update_report_status body matches the reviewed source. Its owner, SECURITY DEFINER setting, search path and ACL were preserved. Only this function's definition hash changed; the other 16 PinPoint functions retained their definitions/security metadata. Catalog comparison reports zero drift (11 tables, 21 indexes, 11 policies, two cron jobs, 17 functions).

The intentional routing change replaced exactly Test Barangay A/B/C with Alangilan at 13.7819, 121.0671: [PhilAtlas's approximate barangay coordinates](https://www.philatlas.com/luzon/r04a/batangas/batangas-city/alangilan.html). This is neither device GPS nor a surveyed geometric centroid. SMS location text still labels the barangay-level approximation. No report/history was renamed or rewritten. This configures SMS jurisdiction, not nationwide service coverage.

## Hosted functional verification, no test push committed

[verify-approved-policy.sql](contracts/verify-approved-policy.sql) passed **18/18 hosted checks** after its own local verification. A local negative control restores the old function, proves the verifier rejects its duplicate-event behavior, and rolls back without changing an unrelated report.

The hosted cases covered prerequisites/exact jurisdiction; priority fields/updated_at with zero status events; exact/null-priority repeats; 3/10 vote and official floors; citizen/anonymous/other-barangay/invalid-priority/illegal-transition denials; one history/notice/outbox event for a real transition; no additional events for a later priority change; and service-role SMS routing/retry/unapproved-area refusal.

These are **database-role checks with controlled JWT claims and privileged fixture setup**, not HTTP sign-ins, a hosted ten-account voting study, carrier SMS or real FCM. Vote counts were fixture inputs. All fixture rows/events were created in one transaction and **rolled back**. There are no custom triggers on reports/notifications/outbox; this function only writes SQL events. The cron dispatcher cannot see uncommitted rows, so no test outbox event was committed or sent to the phone.

## Preservation and limits

After deployment/verification, counts and full-row fingerprints of all 11 protected business/Storage tables matched the baseline: 36 reports, 15 notices/history/outbox rows, zero votes/SMS receipts, two devices, 45 objects, two buckets, two profiles and one official. The sole intended data difference is private.barangays: three simulation rows became one Alangilan row. No test report, receipt, notice/history/outbox row or temporary table persisted; no fixture/session cleanup remains.

Security-advisor results were the same three existing findings before/after, with no new finding. Existing documented limitations were not represented as solved. No catalog snapshot update was necessary because it omits function bodies; the body was separately compared in full.

The full local suite now passes **69/69**, including two verifier regressions, and both Edge type checks pass. No Android runtime or dependency changed in this follow-up.

## Repeat safely

Privately substitute the confirmed citizen/assigned-Alangilan-official UUIDs into the template. Execute the complete file in one trusted connection to the intended project, retain its final ROLLBACK, assert the returned check count, then independently compare baseline fingerprints. Keep rendered SQL/oracles outside Git. This is not a deployed endpoint. Do not apply the migrations again merely to repeat validation.

Private evidence: `work/approved-policy-deploy-20261123-a01/` in the Codex task workspace holds source hashes/text, the old function/routing state, baseline/function metadata, both migration responses, rendered verifier, 18-case response, history mapping, advisor snapshots and final verification. The ledger is finalized as deployed-and-verified; no rollback of the approved changes was needed.

Remaining work: dedicated test-identity provisioning, public SMTP/provider verification, spare SMS gateway setup, fresh-project restore, TUS crash/resume edge cases and field/UI acceptance. The product choices and test identities are already approved. The user's phone stays unavailable.
