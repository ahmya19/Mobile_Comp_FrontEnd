# Hosted whole-app map validation — November 22, 2026

Closes the gap left by [MAP_INTEGRATION_VALIDATION.md](MAP_INTEGRATION_VALIDATION.md): real authenticated hosted data → pagination and filter → rendered native marker → correct full report details through the actual AppNavigation route. Emulator scenarios below are complemented by the ordinary-phone follow-up. Outdoor GPS and carrier measurements remain separate.

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

Eleven existing reports share exactly 13.78, 121.07, and two more differ by about one metre. The map tap handler opened only the first rendered feature, so the others could not be opened from the map. SMS reports will stack the same way, because each SMS report in a barangay takes that barangay's single centroid. Local reproduction: `MapIntegrationTest.stackedMarkersOfferAChooserSoEveryReportIsReachable` failed (no chooser; one report opened) before the fix. `ReportMap` now opens the report directly when one marker is hit, and otherwise shows an AlertDialog with report choices (category, first 60 characters of the description, status). That initial implementation capped choices at 25; the follow-up [BG-004 fix](MAP_STACK_VALIDATION.md) retains all loaded hits in a bounded lazy list. The test passes locally (MapIntegrationTest 2/2) and on hosted data (above). This is a runtime Kotlin change; no schema, dependency or backend change.

## Fixtures and cleanup

No existing data was changed. The driver created two labelled citizen reports through the ordinary `submit_report` RPC (prefix MAPNAV-20261122-A01, unique synthetic coordinates 13.600,121.000 and 13.650,121.100, no photos, no votes or notices), recorded their IDs in a private ledger before creating them, and after each run deleted only those IDs by privileged SQL. Baseline before and after every cycle (including three earlier failed attempts): 36 reports, 13 with coordinates, 15 history rows, 15 notices, 15 push-outbox rows, 0 votes, 0 SMS receipts; the ordered report-ID list was identical. Evidence (outside Git): work/map-nav-ledger.json, map-nav-oracle.json, map-nav-cleanup-verification.json, map-nav-test-real.log, map-nav-negative-control.log.

## Reproduce

Needs a Supabase CLI login (linked project), local.properties, the private demo-accounts JSON, an isolated emulator with both debug APKs installed, and the citizen demo account. From the repo root:

~~~powershell
python backend/scripts/map_nav_hosted.py full --credentials <accounts.json> --work <private dir> --serial emulator-5556
~~~

`prepare`, `run` and `cleanup` can be run separately. Use a fresh private work directory for each cycle; both credentials and work paths must resolve outside the repository. Existing ledgers and test archives are preserved. The driver never prints credentials and guards the serial to `emulator-N`.

The driver durably records both intended fixture IDs before submitting either report. Partial preparation and test failures both enter cleanup, while an existing run's ledger is never claimed by another invocation. The host citizen session is signed out with local scope after submission; instrumentation removes its own device registration and signs out its separate session. Cleanup validates fixture identity, refuses unexpected references or changes to the original report fingerprint, and deletes only recorded fixture IDs in one guarded transaction. An interrupted run can use `cleanup` with its original work directory. Legacy ledgers without the schema/fingerprint require manual scope verification and are refused automatically.

Merge review regression coverage: 11 mocked Python failure-path tests, plus two PGlite tests executing the actual generated PostgreSQL cleanup (partial setup succeeds; unexpected history rolls back). These run through `npm test` alongside the existing backend suite.

## Ordinary TECNO follow-up — November 22

Codex resumed from Claude's backend handoff and verified BG-003 on the ordinary TECNO CL8, Android 15 / API 35, using validated Wi-Fi and the pre-existing citizen session. The initial ADB check showed no device; testing began only after the user reconnected it.

The app/test builds passed (Gradle tasks up-to-date). Only the app APK was installed with install -r, retaining data/session; no test instrumentation was installed on the phone. Installed APK SHA-256: 659bebd4b9736529b14abc706cefddf46bd36b3be317fa56dd6b36c07b0e1752. The previous phone build was d008315e7a25bf8800a684e8143b5bc82b2eb2c8494127d3d56965761073fdcf.

Ordinary UI actions opened Map, then Load more reports on map. The loaded-location summary increased from 6 to 13 and the page button disappeared. Basemap/markers were visible. Tapping the existing coincident marker opened "11 reports at this location". Scrolling the chooser exposed all 11 expected labels in the order independently queried from SQL; unique category/description-prefix/status labels mapped them to the expected IDs. Selecting the oldest entry opened details with that report's full description, category and coordinates. Back returned to Map. This checks the real application route, not a test callback.

No report fixtures or new sign-ins were needed. Citizen identity was verified privately before/after navigation; no logout, clear-data or uninstall was performed. The phone returned to PinPoint Home, awake on USB, with stay-awake 2, airplane off and Wi-Fi on. Radios and per-app exemptions were unchanged. Initial foreground activity was not captured reliably, so restoration of an unrelated prior foreground app is not claimed.

Before/after read-only SQL matched all 36 report rows and 15 notification rows by full-row fingerprints. Counts also remained 13 coordinate reports, 15 history rows, 15 outbox rows and zero votes. No hosted cleanup was necessary. Private evidence outside Git: work/phone-map-before-oracle.json, phone-map-result.json, phone-map-after-verification.json, phone-map-network.json, phone-map-chooser-*.xml and phone-map-details-*.xml, plus map/chooser/details screenshots. One private evidence writer failed on a Unicode arrow under Windows cp1252; UTF-8 capture resumed on the already-open details. This was a host capture error, not an application failure; no phone navigation or upload latency target is inferred.

## Limits

Basemap tiles come from the public OpenStreetMap server. Pixel equivalence and device GPS were not measured. The ordinary phone pass covers citizen pagination, the 11-report chooser and selected details/back navigation; category filtering remains covered by the hosted emulator test above. Other filter combinations through the map UI, the official account, permission/error states and outdoor accuracy remain separate acceptance work. The initial chooser cap of 25 was removed in the [BG-004 emulator follow-up](MAP_STACK_VALIDATION.md), tested with 26 and 75 local reports. That new candidate was not installed on the physical phone; the 11-report phone results above apply to its earlier build.
