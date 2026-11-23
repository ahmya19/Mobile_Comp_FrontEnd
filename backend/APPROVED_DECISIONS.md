# Approved group decisions and backend status (recorded November 23, 2026)

The user is the group's final decision-maker. These answers are final; they are not to be asked again. Status words: **Done** (code + tests in the repo), **Done, not deployed** (in the repo and proven locally, not yet applied to the hosted project), **Prepared** (technical work ready, a manual step or authority remains), **Pending** (not started or blocked on an external input).

| # | Decision | Backend status | Where |
| --- | --- | --- | --- |
| 1 | All agreed features must really work; tentative defense window Nov 30 to Dec 4, 2026 (exact date unset; plan for Nov 30) | Ongoing. A full feature inventory against the final UI is still required before any "whole app finished" claim. | CHECKPOINTS.md |
| 2 | Initial jurisdiction: **Alangilan, Batangas City**, extendable; no nationwide claim; SMS point approximate and labelled | **Done, deployed and verified**: 011 replaces only the three simulation rows. Hosted rollback-only checks verify routing/retry and refusal elsewhere; reports preserved. | APPROVED_POLICY_DEPLOYMENT.md |
| 3 | Public signups are citizens; officials only through trusted backend administration | **Done**: roles come only from `private.officials`, which API roles cannot write; profile metadata cannot elevate (replay test). Real team assignments are inserted privately when names are supplied. | replay tests, README |
| 4 | Voting thresholds approved: 3 votes = Medium, 10 = High; votes never lower an official assessment | **Done**: already deployed, no migration; boundary and floor tests kept and extended; docs changed from provisional to approved. | `VOTING_VALIDATION.md`, `sql.test.mjs`, replay tests |
| 5 | BG-005: only a real status change writes history/notification/outbox; priority-only updates priority/updated_at | **Done, deployed and verified**: reviewed body matches exactly; owner/grants/search path preserved. Eighteen hosted rollback-only checks passed with no committed test push. | APPROVED_POLICY_DEPLOYMENT.md |
| 6 | Authenticated citizens browse community reports and compressed photos; originals only owner and assigned officials; buckets private | **Done** (no change). | existing policies and tests |
| 7 | Preserve reports and committed photos; no citizen overwrite/delete; narrow cleanup of abandoned unreferenced uploads only; keep a labelled synthetic demo dataset | **Done** for preservation (no delete path). Abandoned-upload cleanup job and the labelled demo dataset are **Pending**. | |
| 8 | Public registration with email confirmation on; demo accounts retained; secure session restore; no embedded credentials or bypass | Session restore **Done** (encrypted session store + refresh). Public email delivery **Pending** (needs the team SMTP account, below). | |
| 9 | Dedicated citizen/official test identities through trusted Auth administration | **Approved, provisioning pending**: trusted administration/private credentials required, not SQL inserts. Do not ask the product question again or route test pushes to phone-registered accounts. | |
| 10 | Spare Android gateway phone/SIM (owned by the user); adapter already implemented; receiver stays fail-closed | **Pending** hardware verification and gateway secrets. | `backend/README.md` |
| 11 | Disposable **Supabase Free** project for a real fresh restore, only if $0 and quota allow; isolate CLI links | **Pending**; see "Restore project" below. | `MIGRATION_REPLAY_VALIDATION.md` |
| 12 | Phone unavailable until released; owned emulator only | Respected. | |
| 13 | Final UI reference: `C:\Users\USER\Downloads\PinPoint-Citizen-App.pdf` (10 pages); inventory every screen/action/state; document differences (camera, draft, delete) | Reference recorded. The inventory/reconciliation is **Pending** and stays the separate UI assignment. | |
| 14 | User owns the team's free SMTP account and the spare gateway phone/SIM; explain steps in plain language, never ask for secrets in chat | Prepared; see "What the owner must do" below. | |

## Deployment state — migrations 010 and 011 are live

Applied only the reviewed forward changes through the migration tool. Hosted versions are **20261123043105** (priority-only policy) and **20261123043113** (Alangilan); the existing nine history records were untouched. See [APPROVED_POLICY_DEPLOYMENT.md](APPROVED_POLICY_DEPLOYMENT.md) for mapping, source/permission checks, 18 hosted rollback-only cases and data preservation.

**Do not run a general `supabase db push`, replay creation files or mark unknown migrations applied.** Hosted/local version differences require separately reviewed history reconciliation. Catalog signatures/grants match the snapshot; the function body was also compared in full. No catalog snapshot update or committed test push was needed.

## Restore project (conditional, not started)

Before creating anything: verify the organization, that the plan is Free and that a second Free project is within the available Free-project quota; if not, record the blocker and use a local disposable environment. Use an isolated working directory and `SUPABASE_*` environment so the main repository link is never retargeted; do not copy FCM or SMS secrets; leave outgoing delivery disabled. Claim a fresh restore only after the real migrations, setup and functional checks pass there.

## What the owner must do (plain language)

- **Email (SMTP):** create or sign in to the team's free email-sending account (Brevo Free is the candidate; the plan allowed 300 emails a day when checked), complete the provider's sender or domain verification yourself, and then paste the SMTP credentials only into the Supabase dashboard's email settings (never into chat or code). Tell me when done; I will test signup from an address outside the team, including invalid, expired and resend cases.
- **SMS gateway:** put a SIM with SMS in the spare Android phone, install the gateway app from its official source (no Play Protect bypass, not the main phone), keep it charged and connected, and tell me when it is ready; I will do the technical configuration and a real carrier test.
- **Test identities:** already approved. Backend work needs trusted Auth-administration access; no new product confirmation is required. Keep administrative credentials private.
- Free-plan reminders: email, database and photo capacity are limited, and the gateway phone needs power, signal and SIM load; none of these are unlimited.
