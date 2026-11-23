# Original-photo resumable upload (TUS) recovery: crash-boundary matrix (CP-44, Batch 1A)

November 23, 2026. **Logic tests with a server model behind the unchanged release checks. They are not a real pinned-network test.**

## Seam (release invariants preserved)

`WifiOriginals` now separates the *transport* from the *rules*:

- `OriginalLink` (interface): `ensure()` (throws unless the pinned network is still an unmetered Wi-Fi network) and `execute(request)`. Production uses `WifiLink`, which is the previous implementation moved unchanged: sockets and DNS bound to one real unmetered Wi-Fi `Network`, redirects off, same timeouts, and an `IOException("Waiting for unmetered Wi-Fi ...")` when none exists.
- In front of **either** link, on every request: `ensure()`, then `require(url.isHttps && host in {project, project-storage host} && port == 443)`, then the account-bound access token. These rules did not change and run for test doubles too (a saved session URL pointing at another host, plain http, port 8443 or a look-alike host never reaches the link: tested).
- `WifiOriginals.installForTesting` exists only in debug builds and still requires an `https://*.supabase.co` project; release builds always use the real Wi-Fi transport. The worker and the original viewer call `WifiOriginals.create`.

## Results (13 cases, emulator, all pass; model server, not Supabase)

| Crash boundary | Result |
| --- | --- |
| Death after an acknowledged chunk | Resumed from the saved session; offsets sent `[0, 6 MiB]`, no acknowledged byte resent; hash matches |
| Server accepted the chunk but the acknowledgement was lost | HEAD reported 6 MiB the client never saw; retry sent only the remainder; hash matches |
| Interruption mid-chunk | Retry started exactly at the server's non-aligned offset (1 000 000); hash matches |
| Upload complete, attach acknowledgement lost (worker) | Retry sent no bytes (session gone, existing object byte-compared), attach committed exactly once, local original removed after the acknowledged attach |
| Server rejects a still-valid-looking token | **Defect BG-017**: one refresh then the same request repeated; without it the transfer ended in permanent `UPLOAD_401` |
| Account B while A's original is queued | Worker for A does nothing; a direct attempt is refused before any request; no attach |
| Wi-Fi loss mid-run | Retry state `Waiting for Wi-Fi`, zero requests while lost, resume from HEAD when Wi-Fi returns, one attach |
| No unmetered Wi-Fi at start | Deferred (`Waiting for Wi-Fi`, retry), zero requests |
| Expired session (HEAD 410) | New session created, upload completes, hash matches |
| Unauthorized session (HEAD 403) | Precise `Failed` state, no bytes sent, no attach |
| Conflicting bytes at an existing object path | Never accepted: `Failed`, no session created, no PATCH, existing object untouched |
| Untrusted saved session URLs (other host, http, port 8443, look-alike host) | Rejected before the transport; zero requests |
| Kill after the attach was acknowledged, before the local original is deleted | **Defect BG-018** (private original left forever); fixed |

**Failing controls:** removing the 401 refresh turns `aTokenTheServerRejectsIsRefreshedOnce...` red (`BackendException: UPLOAD_401`); before the purge fix the BG-018 case failed (`a private original must not outlive its acknowledged attach`). Earlier real-SIGKILL evidence for the persisted row and saved session is CP-40 case S6.

## Defects

- **BG-017** — `WifiOriginals` never refreshed a token the server had just rejected (the main backend path got that rule as BG-007), so an upload whose local token looked unexpired could fail permanently. It now refreshes once and repeats the request (safe: a 401 is rejected before execution).
- **BG-018** — a kill between the acknowledged attach and deleting the local original left the private file forever. `ReportQueue.purgeSyncedPhotos` now also removes the original of Synced rows whose `original_state` is `Uploaded`.

## Real Wi-Fi-bound HTTPS TUS smoke against Supabase (CP-47, November 23, 2026)

Driver: `backend/scripts/hosted_original_tus_smoke.py` + opt-in instrumentation `HostedOriginalTusSmokeTest` (`-e allowHostedOriginalTusSmoke true`, skips by default). Uses the **real** `WifiOriginals(context, backend)` and **real** `WifiLink` (unmetered Wi-Fi `Network` binding, HTTPS port 443, redirects disabled, allowed project/storage hosts) against production Supabase `jaysxjjdvaitgrbqjlhe`, with **only** the dedicated fixture citizen (`supabase-fixture-identities.json`). Private ledger: `work\ag-tus-smoke-20261123-*`.

1. **Negative cellular-only control (`TUS1A-56638121`, `emulator-5556` on metered `MOBILE[NR]` with Wi-Fi disabled):**
   - `submit_report` with compressed photo succeeded over cellular; `WifiOriginals.upload` immediately refused with `IOException: Waiting for unmetered Wi-Fi to transfer the original photo.`
   - **Zero** original bytes crossed cellular; aborted synthetic report (`31d60753-d284-41dd-94c9-6c2e4e845381`) and `photo.jpg` were cleaned and baseline fingerprints restored. (This run also motivated writing the on-device `tus-smoke-receipt.json` before `submit_report` so even an aborted smoke leaves an exact identity receipt.)
2. **Positive real Wi-Fi HTTPS interrupt/resume/download smoke (`TUS1A-d603f5d1`, `emulator-5556` on unmetered `AndroidWifi`):**
   - Uploaded a bounded **6,500,815-byte** synthetic JPEG original (`SHA-256: 5a0b09169ff07f400556e81dc9964f957f58f83f8b248d04ac762c5458ddefef`) on report `06aa2298-a8c7-470c-9514-563726c397a3` (compressed `photo.jpg`: `815` bytes).
   - Deliberately interrupted immediately after Supabase acknowledged the first real 6 MiB TUS chunk (`interrupted_offset: 6,291,456` bytes), dropped the uploader instance, and resumed with a fresh `WifiOriginals` instance reading the persisted `upload_url` via real TUS `HEAD` → `PATCH` of the exact `209,359`-byte remainder (`resumed_final_offset: 6,500,815`, elapsed `6,665` ms).
   - Verified double idempotent `attach_original` returned the same object path, local private original was purged, and private download over Wi-Fi on device **and** independently on host both matched exact SHA-256 `5a0b09169ff07f400556e81dc9964f957f58f83f8b248d04ac762c5458ddefef`.
   - Interface counters (`dumpsys netstats` + `/proc/net/dev` delta): `wlan0` TX `6,981,466` B / RX `7,100,566` B; **`cellular_tx_bytes: 0`**, `cellular_rx_bytes: 0`.
   - Cleanup: original removed via trusted Storage API `DELETE` (secret key in memory only) with `GET` HTTP 400/404 byte-absence verification; compressed photo + report removed via single-line guarded SQL; `session_cleanup: confirmed` (HTTP 204); `baseline_preserved: true` (exact 36 reports, 45 objects, 15 notices/history/outbox, 2 shared-demo devices).

## Not covered (precise)

- **Physical-phone multi-handoff cohort (Batch 3B):** 20 deliberate real Wi-Fi ↔ cellular handoffs on a physical phone remain **Not run** (the user's TECNO phone was disconnected during CP-47, and fixture sign-in requires owner approval to swap the phone's signed-in demo session). The real Wi-Fi HTTPS smoke above is explicitly labelled **emulator transport evidence**, not a physical-phone cellular-handoff acceptance cohort.
- **Supabase's real long-window TUS session expiry (>24 h):** approximated by the CP-44 model server (`HEAD 410`).

Regression after CP-47: backend 81/81 + Edge check pass, JVM 14/14, lint 0 errors / 38 warnings, emulator 73/73 across 12 suites (`OriginalRecoveryTest` 13, `UiParityAndCameraDraftTest` 6, `AuthQueueFailureTest` 12, `EmailConfirmationTest` 6, `ReportSchedulingTest` 5, `PhotoCompressorTest` 11, `FeedRecoveryTest` 5, `MapIntegrationTest` 4, `MapRecoveryTest` 3, `NotificationAndMapUiTest` 2, `PushPayloadSimulationTest` 3, `NativePickerTest` 3). Final CP-47 APK SHA-256: debug `10a4ec68771fdaa7aa660f45e74f298e149e9d278a0ea13af7dd9aec9f340764`, androidTest `d7f4c1cc9c7395e1ac48114337fc5977f30f58ef72f626c1ea046179724aa4bf`, unsigned release `00e99317aa03d03dcae2e5d8a47915b657d547f884d0917b49bbf5213de6fe7e`.
