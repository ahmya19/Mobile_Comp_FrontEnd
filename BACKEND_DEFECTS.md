# Backend and integration defects

## BG-001 — ordinary-app background report upload delayed

Owner: Codex. Status: reproduced on existing phone build; expedited scheduling candidate under validation. CP-25 remains open. Claude must include this case in whole-app regression.

On the TECNO CL8 / Android 15, save a synthetic report/photo offline in the ordinary app, press Home, reconnect to cellular with Wi-Fi off and leave PinPoint closed. In the approved November 22 test, the host observer saw validated Internet but no matching report for at least 256.324 seconds afterward. The report worker was still ENQUEUED with zero attempts in a snapshot during the observation. Expected: prompt automatic recovery; actual: no report by the five-minute observation limit. No application exception was captured for that interval; the exact diagnostic outcome was network-observed-report-missing, copies=0, timing_valid=false.

The same report later recovered during an unobserved gap and was verified once with its matching downloadable/decodable photo. The user reports only reconnecting USB. This preserves data-recovery evidence without establishing the recovery trigger or automatic latency. Historical run 8's No validated reconnect was observed remains a separate instrumentation failure; it is not silently reclassified as the same proven cause.

Candidate: expedite pending compressed report drains, retaining a non-expedited fallback and old-Android notification support. Local tests and a fresh physical recheck are required; do not mark Fixed until the phone case is verified. Evidence, boundaries and protocol: [backend/BACKGROUND_SYNC_INVESTIGATION.md](backend/BACKGROUND_SYNC_INVESTIGATION.md). Keep credentials, serials, raw logs and private database copies outside Git.
