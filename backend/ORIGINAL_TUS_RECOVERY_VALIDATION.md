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

## Not covered (precise)

- The real Wi-Fi-bound HTTPS transport against Supabase's TUS server, cellular exclusion with byte counters, and a download/hash of the stored private object: **Not run**. Needs a connected, charged, unlocked phone on Wi-Fi (the phone was connected but at 4 to 5% battery charging at 500 mA, so no phone run was started) and a guarded production-only smoke with the fixture citizen.
- Supabase's real handling of an expired or stale upload session (the model approximates it).

Regression after the change: 76 backend tests, JVM 11/11, lint 0 errors / 37 warnings, emulator AuthQueueFailureTest 12, ReportSchedulingTest 5, NativePickerTest 3, PhotoCompressorTest 8, FeedRecoveryTest 5, PushPayloadSimulationTest 3, OriginalRecoveryTest 13. Debug APK SHA-256 `f837e6b6dcbb163c5d785d25904938c92702e98c214be2f61e47d6f7b9238861` (the emulator run build).
