# Hosted whole-app map validation — November 22, 2026

Closes the gap left by [MAP_INTEGRATION_VALIDATION.md](MAP_INTEGRATION_VALIDATION.md): real authenticated hosted data → pagination and filter → rendered native marker → correct full report details through the actual AppNavigation route. **Emulator only; no physical phone, outdoor GPS or carrier measurement.**

## Result

`HostedMapNavigationTest` passed **1/1** (7.6 to 9.6 s per run, three passing runs) on the isolated PinPoint_Backend_API36 emulator (API 36.1) against project jaysxjjdvaitgrbqjlhe, using the ordinary citizen session and public key. Expected IDs came from a separate privileged read-only SQL query, not from the app's own repository. The test compared rendered MapLibre feature IDs to those expected sets exactly:

| Step | Expected (SQL oracle) | Rendered | Navigation check |
| --- | --- | --- | --- |
| First feed page (25 newest of 38 reports) | 6 markers with coordinates | exact match | Tap on the unique Road fixture opened a details screen whose description, category and coordinates matched that fixture |
| Load more (feed reaches 38) | 15 markers | exact match | none |
| 11 existing reports at one identical coordinate | 11 IDs under one tap point | exact set under the tap | Chooser listing "11 reports at this location"; picking one opened that report's details (description, category, coordinates) |
| Category filter Garbage | 2 markers (one existing, one fixture) | exact match | Tap on the Garbage fixture opened the right details |

Negative control: the same test with one nonexistent ID injected into the expected set failed with `after load more: rendered 15 IDs, expected 16. Missing=[<that id>]`, so a pass is not vacuous. Details screens do not show a report ID, so identity is established by matching unique fixture fields (description, category, coordinates) that the oracle ties to the expected ID.

## Defect found and fixed (BG-003)

Eleven existing reports share exactly 13.78, 121.07, and two more differ by about one metre. The map tap handler opened only the first rendered feature, so the others could not be opened from the map. SMS reports will stack the same way, because each SMS report in a barangay takes that barangay's single centroid. Local reproduction: `MapIntegrationTest.stackedMarkersOfferAChooserSoEveryReportIsReachable` failed (no chooser; one report opened) before the fix. `ReportMap` now opens the report directly when one marker is hit, and otherwise shows an AlertDialog listing up to 25 reports (category, first 60 characters of the description, status). The test passes locally (MapIntegrationTest 2/2) and on hosted data (above). This is a runtime Kotlin change; no schema, dependency or backend change.

## Fixtures and cleanup

No existing data was changed. The driver created two labelled citizen reports through the ordinary `submit_report` RPC (prefix MAPNAV-20261122-A01, unique synthetic coordinates 13.600,121.000 and 13.650,121.100, no photos, no votes or notices), recorded their IDs in a private ledger before creating them, and after each run deleted only those IDs by privileged SQL. Baseline before and after every cycle (including three earlier failed attempts): 36 reports, 13 with coordinates, 15 history rows, 15 notices, 15 push-outbox rows, 0 votes, 0 SMS receipts; the ordered report-ID list was identical. Evidence (outside Git): work/map-nav-ledger.json, map-nav-oracle.json, map-nav-cleanup-verification.json, map-nav-test-real.log, map-nav-negative-control.log.

## Reproduce

Needs a Supabase CLI login (linked project), local.properties, the private demo-accounts JSON, an isolated emulator with both debug APKs installed, and the citizen demo account. From the repo root:

~~~powershell
python backend/scripts/map_nav_hosted.py full --credentials <accounts.json> --work <private dir> --serial emulator-5556
~~~

`prepare`, `run` and `cleanup` can be run separately. The driver never prints credentials, guards the serial to `emulator-N`, and cleanup runs even when the test fails.

## Limits

Basemap tiles come from the public OpenStreetMap server. The test does not compare basemap pixels, does not use device GPS, and was not run on the TECNO phone. Other filter combinations through the map UI, the official account, permission/error states, and outdoor accuracy remain separate acceptance work. The chooser lists at most 25 reports per tap point; a larger stack would need paging.
