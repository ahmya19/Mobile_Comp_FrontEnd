# Backend and integration defects

## BG-001 — ordinary-app background report upload delayed

Owner: Codex. Status: **Open**. CP-25's under-60-second background recovery target is unmet. Claude must retain these cases in whole-app regression, including backend results.

On the TECNO CL8 / Android 15, ordinary-form report/photo submissions survive offline saving but can remain unsent after validated connectivity while PinPoint is on Home. The baseline cellular observation had no report for at least 256.324 seconds after validation. After the separate BG-002 correction, the clean Wi-Fi observation still had no report for 263.945 seconds after validation. Workers had zero attempts; eventual recovery is not a timely automatic-delivery pass.

Read-only checks in the corrected Wi-Fi trial found the app UID's kernel cgroup frozen, inherited by the process, despite Android's isFrozen=false process flag. Normal foregrounding thawed the group and recovered the same report/photo. This establishes an execution blocker in that trial, but does not identify the freezer controller or retrospectively prove historical run 8's exact cause. Run 8's exact diagnostic, No validated reconnect was observed, remains recorded separately.

The owner separately approved Unrestricted battery policy and one extra comparison. That report delivered automatically without reopening PinPoint, 127.422 seconds after host-observed Wi-Fi validation (128.577 seconds after availability). The observation has valid polling endpoints but fails the 60-second target. Kernel freezing was still sampled, with a thaw near execution. One result does not prove Unrestricted alone caused recovery or establish usual latency.

All five separately approved diagnostic fixtures recovered once, with independently downloaded/decoded 512×512 JPEGs matching their saved compressed hashes (165,343 bytes each). Final SQL found five reports, five unique IDs, five photos, zero coordinates and zero originals. Keep these separate from the ten historical persistence/recovery samples. The baseline background report recovered during an unobserved gap; the user reports USB reconnection only. Its trigger and latency cannot be inferred.

Next investigation: distinguish the UID-freezer controller from system job dispatch; evaluate a supported user-initiated transfer path if needed. Preserve durable saving, account isolation, stable UUID/photo idempotency and originals' Wi-Fi-only policy. No speculative execution workaround has been shipped. More phone/APK/settings/hosted fixtures require a new scope; existing scopes are consumed. Fixtures remain hosted pending cleanup approval. Phone policy remains Unrestricted as approved, with Wi-Fi on and airplane mode on from the test setup.

## BG-002 — empty restoration job blocks an expedited report

Owner: Codex. Status: **Fixed in 2c94c80**, verified locally and at the corrected phone queue boundary. This does not close BG-001.

First candidate 8691cbd enqueued an ordinary empty drain on account restoration. A subsequent offline submission's expedited work became BLOCKED behind it. The new restoration-then-submit regression reproduced this with Restoration must not add an empty predecessor expected:<1> but was:<2> (the assertion's exact unmodified text is retained in the private test log).

The correction skips report work when the active account/project has no Queued or Sending rows and schedules waiting originals directly. Genuine report drains retain expedited priority, quota fallback, CONNECTED, APPEND_OR_REPLACE and idempotent retries. Five local scheduler tests pass, along with ten JVM tests, debug/test APK builds and lint (0 errors, 35 warnings). Tests use a probe worker rather than live Supabase uploads. The corrected phone showed no empty restoration request and its real submission was ENQUEUED with only a completed predecessor. The old-Android foreground-service lifecycle remains untested.

Detailed evidence, timestamps, endpoint limits and source links: [backend/BACKGROUND_SYNC_INVESTIGATION.md](backend/BACKGROUND_SYNC_INVESTIGATION.md). Keep raw device dumps, serials, credentials and private queue copies outside Git.
