# Abandoned-upload cleanup, maintained demo dataset, and isolated Free restore tooling (Batches 1B & 1C)

Updated November 23, 2026. Covers:
- **Batch 1C (abandoned-upload cleanup):** reference inventory, concurrency-safe Storage API cleanup path, mocked + PGlite verification, and read-only live dry-run inventory.
- **Batch 1C.4 (maintained Alangilan demo dataset):** deterministic, idempotent, clearly labelled `[DEMO]` Alangilan dataset covering all 6 categories and statuses, with exact guarded cleanup.
- **Batch 1B (isolated fresh Supabase Free restore):** production-refusal isolated restore harness, quota verification, and reproducible restore/rollback steps.

---

## 1. Storage reference inventory & abandonment policy (Batch 1C.1–1C.3)

### Object reference model

| Bucket | Path pattern (enforced by RLS & RPCs) | Committed reference | Active upload protection | Client delete policy |
| --- | --- | --- | --- | --- |
| `report-images` | `<owner_uuid>/<report_uuid>/photo.jpg` | `public.reports.photo_path = name` | Age grace period (default 24 h) | Owner may delete only unreferenced own object (`pinpoint_photo_cleanup`) |
| `report-originals` | `<owner_uuid>/<report_uuid>/original` | `public.reports.original_path = name` | `storage.s3_multipart_uploads (bucket_id, key)` + age grace period (24 h) | None (requires trusted Storage API path with secret key in memory) |

### Abandonment criteria (fail-closed)

`backend/scripts/cleanup_abandoned_uploads.py` considers an object eligible for removal **only when all conditions hold**:
1. `bucket_id` is in `('report-images', 'report-originals')`.
2. `name` matches the exact UUID path regex for that bucket (`skipped-unexpected-path` otherwise).
3. `storage.objects.owner_id` (or `owner`) equals the UUID prefix in `name` (`skipped-unexpected-owner` otherwise).
4. If `--owner-id <uuid>` is passed, the path owner matches it (`skipped-foreign-owner` otherwise). Shortening `--min-age-seconds` below 86,400 s (24 hours) is refused unless `--owner-id` is explicitly supplied.
5. `greatest(created_at, coalesce(updated_at, created_at))` is older than `--min-age-seconds` (default 86,400 s).
6. No row in `public.reports` references the object (`photo_path` or `original_path`).
7. No active resumable session exists in `storage.s3_multipart_uploads` for `(bucket_id, name)`.

### Concurrency-safe Storage API execution

- Default mode is read-only `--dry-run` (fetches no secret key, performs no Storage DELETE).
- Traversal uses deterministic keyset pagination `(created_at ASC, id ASC)` in batches of `--batch-size` (default 50, max 200).
- Immediately before deleting any eligible object, the driver runs `final_recheck_sql` to detect concurrent `submit_report` / `attach_original` commits or newly started `s3_multipart_uploads` sessions. Any concurrently attached object is preserved (`preserved-concurrent-attach`) with zero DELETE calls.
- Deletion uses the **Supabase Storage API** (`DELETE /storage/v1/object/<bucket>/<name>`) with the project secret key obtained in memory via `supabase projects api-keys --reveal` (never printed, logged, or persisted). Direct SQL deletion of `storage.objects` is never used.
- Immediately after DELETE, the driver verifies **byte absence** via `GET /storage/v1/object/authenticated/<bucket>/<name>` (requiring 400/404) and verifies metadata absence. Already-absent objects on reruns are recorded idempotently as `already-absent`. Partial network errors are logged in the exclusive atomic `RunLedger` without losing progress.

### Read-only live dry-run observation (November 23, 2026)

Target: `jaysxjjdvaitgrbqjlhe` (read-only `--dry-run`, zero secret fetched, zero mutations):

```json
{
  "mode": "dry-run",
  "summary": {
    "scanned": 45,
    "pages": 1,
    "eligible": 0,
    "deleted": 0,
    "preserved_referenced": 45,
    "preserved_concurrent_attach": 0,
    "preserved_active_session": 0,
    "preserved_within_grace_period": 0,
    "skipped_foreign_owner": 0,
    "skipped_unexpected_owner": 0,
    "skipped_unexpected_path": 0,
    "failed": 0
  }
}
```

Every existing hosted object (32 compressed photos in `report-images` + 13 originals in `report-originals`) is referenced by a committed report and preserved untouched.

### Live hosted synthetic cleanup proof (November 23, 2026)

Executed `backend/scripts/verify_abandoned_cleanup_hosted.py` using **only** the dedicated fixture citizen (`supabase-fixture-identities.json`) against `jaysxjjdvaitgrbqjlhe`:
- Created 4 scoped synthetic objects owned by the fixture citizen: (1) an unreferenced compressed upload in `report-images` (>8 s old), (2) an unattached original in `report-originals` (>8 s old) on a temporary committed report, (3) that same report's committed referenced compressed photo in `report-images`, and (4) a newly created unreferenced compressed photo inside the grace period (<8 s old).
- **Dry-run (`apply=False`):** `scanned: 4, eligible: 2, deleted: 0, preserved_referenced: 1, preserved_within_grace_period: 1, failed: 0`.
- **Live apply (`apply=True`):** `scanned: 4, eligible: 2, deleted: 2, byte_absence_verified: 2, preserved_referenced: 1, preserved_within_grace_period: 1, failed: 0`. Both unreferenced objects (1 compressed + 1 unattached original) were removed via Storage API `DELETE` and confirmed absent via `GET` (HTTP 400/404) and zero `storage.objects` rows; the committed report's photo returned HTTP 200 with exact matching bytes, and the recent object survived intact.
- **Idempotent rerun (`apply=True`):** `scanned: 2, eligible: 0, deleted: 0, failed: 0`.
- **Teardown & preservation:** temporary report and remaining synthetic objects removed with exact guards; `baseline_preserved: true` (exact 36 reports, 45 objects, 15 notices/history/outbox, 2 shared-demo devices, identical MD5 fingerprints); `session_cleanup: confirmed` (HTTP 204). Private ledger: `work\ag-cleanup-live-20261123-*`.

### Automated verification

- `backend/tests/abandoned_upload_cleanup_test.py`: proves project-ref mismatch refusal, short-grace refusal without owner scope, dry-run classification of all 8 safety categories (referenced, within grace, active multipart, foreign owner, mismatched owner, malformed path, eligible compressed, eligible original), concurrent attach preservation, active session preservation, idempotent missing-object rerun, partial network-failure ledgering, and keyset pagination.
- `backend/tests/abandoned-upload-cleanup.test.mjs`: runs the real `inventory_page_sql` and `final_recheck_sql` against all 11 replayed migrations in PGlite, proving concurrent report attachment between scan and final re-check flips `is_referenced = true`.

### Scheduling / disabling

Cleanup is **operator-invoked only** (no automatic cron schedule is enabled in production), so there is no hidden background deletion job to disable. Run a dry run any time with:

```powershell
python backend\scripts\cleanup_abandoned_uploads.py --expected-project-ref jaysxjjdvaitgrbqjlhe
```

To execute a live cleanup (with a private ledger directory outside the repo):

```powershell
python backend\scripts\cleanup_abandoned_uploads.py --expected-project-ref jaysxjjdvaitgrbqjlhe --work C:\private\cleanup-run --apply
```

---

## 2. Maintained Alangilan synthetic demonstration dataset (Batch 1C.4)

`backend/scripts/manage_demo_dataset.py` manages **6 clearly labelled `[DEMO]` reports** in **Alangilan, Batangas City** covering every supported category and lifecycle status without touching existing hosted reports:

| Deterministic UUIDv5 | Category | Target status | Priority | Location (Alangilan) |
| --- | --- | --- | --- | --- |
| `f6f185d4-2a77-5408-8036-b2c85d23b0a4` | Road Damage | In Progress | High | Golden Country Homes main gate |
| `3871f921-eab2-502e-a451-be366303e6b9` | Flooding | Under Review | High | Alangilan National Highway canal |
| `6b889068-f51c-54ba-876f-1c6628e4d6f9` | Streetlight | Pending | Medium | BatStateU Alangilan pedestrian lane |
| `dbfbc825-54ac-5e6c-967c-3095ef8c9919` | Garbage | Pending | Low | Purok 3 basketball court corner |
| `c58b48f1-1fa9-583d-a93a-c0c37bd33d9a` | Water Interruption | Under Review | Medium | Sitio Silangan access road |
| `b3a37cce-8f87-50b9-be13-f744a3b296bf` | Other | Resolved | Low | Alangilan Barangay Hall plaza |

- **Synthetic images:** generated deterministically in memory (valid baseline JPEG, zero EXIF/GPS, zero personal photos, zero credentials in the APK).
- **Idempotent seeding:** re-running `seed --apply` reuses the exact UUIDv5 IDs, verifies existing JPEG bytes match, and skips already-reached statuses.
- **Exact guarded cleanup:** `cleanup --apply` executes one single-line guarded transaction verifying `owner_id`, `barangay = 'Alangilan'`, `[DEMO]` prefix, and absence of `sms_receipts` before deleting ONLY those 6 UUIDs and their Storage photos. Tested in `backend/tests/demo_dataset_test.py` and PGlite (`abandoned-upload-cleanup.test.mjs`).

---

## 3. Isolated fresh Supabase Free restore harness (Batch 1B)

`backend/scripts/fresh_restore_harness.py` prepares and verifies a disposable Free restore without risking production:
- Unconditionally refuses `jaysxjjdvaitgrbqjlhe` and `https://jaysxjjdvaitgrbqjlhe.supabase.co`.
- Stages `supabase/migrations`, `supabase/functions`, `supabase/templates`, and `supabase/config.toml` into an isolated `--workdir` outside the repository and strips any `.temp` production link.
- Verifies extensions (`pg_cron`, `pg_net`, `pgcrypto`, `supabase_vault`), `net.http_request_queue` / `net._http_response`, and catalog parity against `backend/contracts/final-catalog.json`.
- Keeps `pinpoint-push-outbox` cron paused on teardown and refuses deleting anything except a positively identified disposable ref.

### Quota status (November 23, 2026)

Live read-only check (`python backend/scripts/fresh_restore_harness.py check-quota`):
- `organizations_count: 1`, `projects_count: 1`, `cli_can_quote_price: false`.
- **Gate status: Blocked on owner $0 quote confirmation** (the Supabase CLI cannot display a project creation price line). Local 11-migration PGlite replay remains a separate passing gate (`backend/tests/migration-replay.test.mjs`). Once the owner confirms $0/month in the dashboard and provides the disposable project ref, run:

```powershell
python backend\scripts\fresh_restore_harness.py stage --expected-project-ref <disposable-ref> --expected-url https://<disposable-ref>.supabase.co --workdir C:\private\pinpoint-restore
cd C:\private\pinpoint-restore
npx supabase link --project-ref <disposable-ref>
npx supabase db push
# Create Vault secrets pinpoint_project_url and pinpoint_publishable_key in SQL Editor
# Deploy sms-report and deliver-push with --no-verify-jwt
python C:\Users\USER\project\MobileConputing\backend\scripts\fresh_restore_harness.py verify --expected-project-ref <disposable-ref> --expected-url https://<disposable-ref>.supabase.co --workdir C:\private\pinpoint-restore
```
