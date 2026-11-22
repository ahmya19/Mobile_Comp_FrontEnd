# CP-22 hosted voting and urgency validation

Verified November 22, 2026 on Supabase Free. Branch backend/voting-validation builds on backend/report-feed-validation (draft PR #3, a43bcac). Runtime, schema, thresholds and dependencies are unchanged.

## Hosted API checks

The opt-in Python standard-library runner used the public publishable key and ordinary citizen/official sign-ins. **32 checks passed**, including eight concurrent same-account add requests, each returning a single vote. Repeating add/remove requests was idempotent; adding the second account produced two votes. Removing the citizen vote left the official vote intact. Each account could read only its own vote row.

Anonymous read/RPC attempts returned HTTP 401 / PostgreSQL 42501. Direct vote insert/update/delete and report priority/count changes returned 403 / 42501. Citizen priority updates and official updates in a different barangay returned 403 / 42501. Null choice returned 400 / 22023, message "Choose vote or remove vote". Both adding/removing votes on Resolved/Rejected reports returned 400 / 22023, "Voting is closed for this report". A nonexistent report returned 404 / P0002, "Report not found".

The assigned official set Medium and High floors through the ordinary RPC on two ownerless synthetic reports. Repeating each assessment produced no duplicate history. Adding and removing a citizen vote retained each floor at zero votes. Ownerless setup avoided notification/push delivery; it does not test owner notification behavior. Voting itself created no history/notification.

Independent privileged SQL confirmed six expected report states, zero remaining fixture votes, exactly two history rows and no notifications. Privileged setup/inspection is separate from ordinary API authorization assertions. This hosted run used two accounts; the 3/10 threshold crossings were tested locally below, not with ten real hosted sign-ins.

## Local threshold and closed-report regression

The full backend suite passed **30/30**. Two new SQL tests exercise ten distinct local users voting from zero through ten and removing back to zero: Low at 0-2, Medium at 3-9 and High at 10. They verify the downward boundaries, the official Medium floor, closed add/remove rejection, null choice and missing-report errors. The existing High-floor and idempotency tests remain.

Thresholds 3/10 remain provisional pending the group decision. No server policy was changed. These checks do not establish mobile vote UI behavior after Claude's restructuring, multi-phone contention or notification/push timing.

## Fixtures, cleanup and repeat

Six labeled reports covered active, Medium/High official assessments, Resolved, Rejected and a foreign barangay. Fixture IDs and the baseline were recorded outside Git before setup. No photos, GPS, new Auth accounts, notification or push-outbox fixtures were created. Test Auth sessions used local-scope logout to preserve the user's phone session.

Guarded cleanup removed six reports and exactly two expected history rows. The original 36 report rows and zero pre-existing votes retained their full-row fingerprints. Remaining scoped reports/votes: zero. The physical phone was not operated during this batch; its user-requested USB stay-awake 2 was verified.

To repeat, capture the baseline and seed six fresh, labeled fixtures matching the runner's preflight. Active/Resolved/Rejected belong to the demo citizen; the Medium/High/foreign reports are ownerless. All begin with zero votes and Low official/derived priority. Active/Medium/High/foreign are Pending; foreign uses CP22 synthetic foreign barangay, the others Alangilan. All have location CP22 synthetic test location, no photos, and descriptions beginning with the fresh prefix plus " synthetic voting fixture ".

Run with Python 3.9+, private credentials and a ledger containing prefix plus reports keyed active, medium, high, resolved, rejected, foreign:

~~~powershell
python backend/scripts/hosted-voting-checks.py `
  --credentials C:/private/demo-accounts.json `
  --fixtures C:/private/cp22-fixtures.json `
  --output C:/private/cp22-result.json
~~~

The runner refuses repository-local evidence/credentials, existing output archives, optimized Python and non-publishable keys. It verifies every fixture before mutations. It does not seed or clean hosted data. Requests have 30-second timeouts; preserve failures and independently inspect/clean scoped data afterward. Do not automatically rerun against already-used fixtures. Current provider background: [database RPC documentation](https://supabase.com/docs/guides/database/functions).
