# Native map integration validation — November 22, 2026

MapIntegrationTest passed **1/1 in 5.635 seconds** on the isolated API 36.1 emulator. The debug test APK built successfully. This batch changes tests/docs only; runtime, dependencies and hosted data are unchanged. Branch backend/map-integration-validation builds on backend/voting-validation (draft PR #4).

The test renders the existing MapScreen/ReportMap with local screen fixtures and the real MapLibre MapView, GeoJSON layer, projection and native hit testing. It verifies:

- One initial valid report is rendered; missing, unpaired, non-finite and out-of-range coordinates produce no rendered report features.
- The actual Load more reports on map button invokes its callback; the supplied second page adds the second expected rendered feature and removes the button.
- The actual category dropdown and Apply filters controls deliver the Garbage filter. Updated screen data removes the old rendered report feature and retains the expected one.
- A native screen-coordinate tap on that marker invokes onReportClick with exactly the Garbage report ID.

Marker assertions query rendered features by ID, rather than relying on the screen's location count. The test uses synthetic local coordinates, not device GPS. Tile requests follow the existing visible-tile configuration; no bulk map download is performed.

The screen callbacks supply local data. This verifies the renderer/control/callback integration, not a hosted map read or whole-app navigation into ReportDetailsScreen. AppNavigation's map callback is wired to the shared details handler; that complete route still requires Claude's full-app regression. Hosted report filters and pagination were verified separately in REPORT_FEED_VALIDATION.md. Basemap visual comparison, physical-phone marker behavior, outdoor accuracy, permission/error cases and all filter combinations through the map UI remain separate acceptance work.

No Supabase fixtures, sign-ins, photos or notifications were created. No physical-phone build/settings were changed. Phone Awake and user-requested USB stay-awake 2 were verified. Only the owned emulator was stopped after the test.

Repeat on an isolated emulator using the existing debug/test APKs:

~~~powershell
adb -s emulator-5556 shell am instrument -w -r `
  -e class com.example.pinpoint.MapIntegrationTest `
  com.example.pinpoint.test/androidx.test.runner.AndroidJUnitRunner
~~~

Keep these marker/filter/callback assertions during Claude's UI restructuring; complete the whole-app/backend matrix in CLAUDE_HANDOFF.md before declaring overall map acceptance complete.
