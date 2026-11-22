# Private-photo authorization and integrity — November 23, 2026

Verified against the existing Supabase Free project using ordinary confirmed owner/official sessions and anonymous requests. Two existing test-labelled reports supplied four JPEG objects: compressed/original variants in the official's assigned barangay and in another barangay. No fixtures, uploads, deletes, new Auth accounts, phones or emulators were needed.

## Deployed contract and observed access

Both buckets are private. The deployed pinpoint_photo_read policy permits authenticated reads of compressed objects attached to community reports; it also permits the uploader's own objects. Originals use pinpoint_original_read/can_read_original and require the owner or an official assigned to the report's barangay. These expectations were read from pg_policies and the deployed helper definition, not inferred from the client UI. Private-bucket downloads use RLS: [Supabase bucket access model](https://supabase.com/docs/guides/storage/buckets/fundamentals).

| Existing variant | Owner | Assigned official | Unassigned official | Anonymous authenticated route | Anonymous public route |
| --- | --- | --- | --- | --- | --- |
| Attached compressed JPEG | 200 | 200 | 200 | 400 / NoSuchKey | 400 / NoSuchBucket |
| Attached original JPEG | 200 | 200 | 400 / NoSuchKey | 400 / NoSuchKey | 400 / NoSuchBucket |

The final run passed **16/16 Storage checks in 11.063 seconds**: seven allowed reads and nine denials. Owner reads positively established object existence before denial checks. Both actors passed fresh Auth identity and server-profile controls, including the official assignment, and identity controls passed again afterward. The ordinary report reads matched the independent manifest's owner/barangay/object bindings.

Allowed bodies had the expected JPEG MIME/header/footer and exact SQL metadata sizes. Every permitted official response matched the corresponding owner's SHA-256. Allowed bodies totalled 162,906 bytes across the run; each selected object was at most 83,384 bytes. The owner-only unassigned original has one permitted actor in this matrix, so no second-reader comparison is claimed for it. Raw image bytes were handled in memory and never saved or displayed. Object paths, descriptions, hashes and credentials remain in private evidence outside Git.

The HTTP 400 responses are measured Storage API denials, not missing-asset or expired-token assumptions: the same authenticated object paths succeeded for the valid owner, valid official identity was checked, and SQL independently proved the objects/buckets existed. NoSuchBucket on the public route is expected for these private buckets.

## Preservation and cleanup

Before/after full-row fingerprints matched all **36 reports and 45 Storage object rows**, plus both bucket records/settings. Both test sessions returned HTTP 204 from local-scope logout; other sessions were not targeted. No files/reports or registrations needed cleanup. The initial run completed the access matrix but its new reporter unpacked a three-field logout response incorrectly and marked cleanup false. That harness bug was fixed; an independent Auth-session count found zero sessions remaining in the initial run window. The archived initial run remains failed evidence; the clean final run above is acceptance.

No app runtime, RLS/schema, dependencies, server deployment, secrets or billing configuration changed. The existing Node/JVM baseline was not rerun because this batch adds only a standalone API runner and documentation. Python syntax and opt-in, repository-path, archive-preservation and missing-negative-control guards passed without network calls. Existing evidence was not overwritten.

## Repeat safely

Use Python 3.9+ and private credentials plus a fresh independent SQL manifest. Each of exactly two distinct reports must belong to the owner account; one must be in the official's assigned barangay and one outside it. Each supplies one existing compressed object and one original, with exact owner/report paths, bucket names, JPEG MIME and metadata sizes. Selected objects must be at most 1 MiB; this scenario deliberately avoids downloading the large resumable-upload fixture.

The schema-1 manifest contains project_url, owner_uid, official_uid, official_barangay and samples. Each sample has label, report_id, owner_id, barangay and two assets; each asset has kind, bucket, path, size and mime. Capture baseline report/object/bucket fingerprints and deployed policies separately. Never commit these private inputs or use a service key for the client assertions.

~~~powershell
python backend/scripts/hosted-storage-checks.py --allow-hosted `
  --credentials C:/private/demo-accounts.json `
  --manifest C:/private/storage-access-manifest.json `
  --output C:/private/storage-access-result.json
~~~

The runner uses only GET requests plus ordinary Auth sign-in/local logout and the read-only my_profile RPC. It rejects other mutation operations, requires explicit opt-in and the expected project/public key/account identities, validates the sample matrix and refuses repository-local inputs/output or an existing archive. Downloads have a 1 MiB bound and 30-second request timeouts. Partial results and per-session cleanup status are archived on failures. Independent SQL preservation verification remains the host's responsibility.

Private evidence: work/storage-access-before.json, storage-access-manifest.json, storage-access-live.json (initial failed reporter), storage-access-final.json (passed), storage-access-after.json and storage-access-usage.json.

## Scope limits and delivery

This covers four existing JPEG objects through authenticated/public object-download routes. It does not establish every object/actor combination, listing/signed-URL behavior, upload/update/delete authorization, mobile rendering or transfer-resume measurements. Earlier upload/immutability/resume evidence remains separate. Backend follow-up delivery now needs review of the stacked draft PRs; external SMTP/SMS/GPS/group-decision inputs remain pending.
