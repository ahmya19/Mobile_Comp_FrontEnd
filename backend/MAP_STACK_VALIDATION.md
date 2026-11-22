# BG-004 — all loaded stacked reports remain reachable

Verified November 22, 2026 on the isolated PinPoint_Backend_API36 emulator, API 36.1. The user was using the physical phone, so this batch sent no commands, installs or setting changes to it. No hosted fixtures, sign-ins, migrations, deployments, secrets or dependencies were needed.

## Reproduction and change

The original stacked-marker fix retained only hits.take(25). A native regression rendered all 26 co-located report IDs, tapped their marker and failed because the chooser title/count was 25:

~~~text
Every rendered hit must remain in the chooser expected:<2[6] reports at this loc...> but was:<2[5] reports at this loc...>
~~~

The red run took 8.464 seconds. ReportMap now retains every loaded matching report and shows a height-bounded LazyColumn keyed by report ID. The dialog title reflects the full count. Scrolling reaches older entries without eagerly laying out every row; Cancel and selecting an entry retain their existing behavior.

This applies to reports already loaded into the map. It does not fetch unseen backend pages automatically; the map's Load more control remains the way to include older pages. Labels still use category, the first 60 description characters and status.

## Verification

The final combined emulator run passed **6/6 in 23.028 seconds**:

- Existing native marker/filter/page/correct-callback regression.
- Existing two-report stacked chooser.
- 26 co-located rendered reports: full count, last-row selection and correct report-ID callback.
- 75 co-located rendered reports: scroll to last row, Cancel without opening any report, reopen at the first row, select report 75 and dismiss correctly.
- Existing form-map long-press coordinate update.
- Existing older-notification paging and correct report navigation callback.

Debug/test APK builds and ten JVM tests passed. Final lint passed with **0 errors and 34 warnings**. Temporary debug prints, touch listener, screenshot capture and alternate input probes were removed from code.

The large-stack cancellation test initially tapped again before Android finished the dialog's exit transition. Native projection and focus probes were correct, but the second map-click callback was absent. A transition screenshot showed the old window still fading after its Compose semantics disappeared. The final test waits for Android accessibility UI idle before the second native tap; this synchronization is test-only. No runtime sleep or dialog-dismissal workaround was added. The targeted 75-report test passed, then the full combined run passed. These runtimes are test durations, not user navigation or network-latency measurements.

## Delivery and limits

Candidate debug APK SHA-256: 40f966685f549045b533e59dbbb637cdaba674131f62939f7038404a203c094b. Installed only on the owned emulator for verification. The physical phone's last recorded build remains 659bebd4b9736529b14abc706cefddf46bd36b3be317fa56dd6b36c07b0e1752 from the earlier 11-report phone pass; it was not queried or updated here.

Large-stack checks use local screen fixtures with actual MapLibre rendering/projection/native hit testing. They verify the selection callback, not a new hosted 75-report or physical-phone/details-route acceptance test. The previous hosted/phone 11-report evidence remains in [MAP_HOSTED_NAVIGATION_VALIDATION.md](MAP_HOSTED_NAVIGATION_VALIDATION.md). The [official-account hosted map follow-up](OFFICIAL_MAP_VALIDATION.md) now covers existing-data read/navigation and role controls. Outdoor GPS, broader filter/permission/error and field acceptance remain separate.

Private evidence outside Git: work/stack-pagination-red.log, stack-pagination-green-build.log, stack-pagination-final-lint.log, stack-pagination-native-idle.log and stack-pagination-final-ui.log. Intermediate probe logs and the transition image are retained privately for the harness diagnosis. No hosted cleanup is pending. The owned emulator is stopped after verification; the user's phone and other projects are left alone.
