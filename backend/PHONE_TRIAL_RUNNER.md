# CP-25 offline trial runner

Use only after the phone owner approves installation, demo-account use and labeled report/photo creation in the hosted project. The runner requires an explicit connected device and AllowPhysicalDevice for phones. It never toggles airplane mode, Wi-Fi/mobile data, disables security settings, grants permissions, clears application data or uninstalls the app.

Use PowerShell 7, JDK 17 and the configured Android SDK to build the debug app and test APK. Install the reviewed app APK with adb install -r. The initial setup below installs only the instrumentation APK and signs in to the existing citizen fixture. Keep credentials outside Git; the runner quotes shell arguments and redacts demo email/password values from its output.

~~~powershell
.\backend\scripts\run-phone-offline-trial.ps1 `
  -DeviceSerial YOUR_APPROVED_SERIAL -AllowPhysicalDevice -Phase setup `
  -CredentialFile 'C:\private\pinpoint-demo-accounts.json' -InstallTestApk
~~~

The credential JSON has citizen.email, citizen.password and citizen.uid. No service-role credential is used. The script rejects a physical device without the explicit switch, and malformed credential files fail without echoing their contents.

## Each trial

1. The user turns airplane mode on, Wi-Fi off, and confirms the device is offline. Keep USB connected. The test refuses to enqueue if Android still has an active default network or validated Internet.
2. Run phase enqueue with the chosen Run (4 through 10) and Transport (WiFi or Cellular). This saves a generated 512×512 JPEG through the real compressor/queue, with a unique TEST CP25 label, no GPS and no original-photo upload. These are controlled fixtures, not the user's gallery images.
3. Run phase restart. It force-stops only PinPoint, starts it offline and verifies the same report, saved compressed photo and SHA-256 hash. It does not clear a retry state or force a worker to run.
4. Start phase observe while the phone is still offline. Check that files/cp25_active_trial.json inside the debug application's private files says watching. Then tell the user to reconnect using the planned transport, without pressing Retry uploads. For mobile-only trials, explicitly disable Wi-Fi before entering airplane mode, and check it again after leaving airplane mode. KeepAppVisible holds the app's own test window awake during the observation; the user should dismiss Quick Settings and return to PinPoint. Its window flag is cleared afterwards; no system screen/battery setting is changed.
5. Wait for the phase to finish. It checks local Synced state, one hosted report matching the exact unique description, the expected owner/UUID and a byte-identical downloadable/decodable private photo. A failure must be reviewed before starting another trial; do not record it as a pass.

~~~powershell
# Repeat with the same device, private credentials and run number in every phase.
.\backend\scripts\run-phone-offline-trial.ps1 -DeviceSerial YOUR_APPROVED_SERIAL `
  -AllowPhysicalDevice -CredentialFile 'C:\private\pinpoint-demo-accounts.json' `
  -Run 4 -Transport WiFi -Phase enqueue
# Then use -Phase restart, followed by -Phase observe.
~~~

## Evidence and timing

Each phase persists private JSON at files/cp25_active_trial.json and files/cp25_trial_N.json in the app. Export a record using adb -s YOUR_APPROVED_SERIAL shell run-as com.example.pinpoint cat files/cp25_trial_N.json, and save the output outside Git. The record contains test identifiers, byte counts/hashes, queue/worker snapshots, connection type and timing; it contains no credentials, network names, phone numbers or GPS coordinates. Keep raw device identifiers outside public documentation.

The timing interval runs from the app's first onAvailable callback for the subsequently validated default network to the first observed local sync acknowledgement. Both endpoints use Android elapsedRealtime, with 200 ms queue polling. A second interval starts at the validated-capability callback. These measure app-observed recovery; they do not measure the user's toggle action or radio registration time. Callback delivery may be delayed when the app is suspended, so these intervals alone do not establish a universal reconnect-to-server SLA. Server created_at is retained as corroborating evidence without subtracting unsynchronized phone/server clocks.

Full instrumentation runtimes also include waiting for the user to reconnect and downloading/checking the cloud photo. Never use that runtime as upload latency. The observer allows five minutes total and stops after three minutes from an observed reconnect without acknowledgement. Record timeouts and wrong-network connections as failures or incomplete measurements, even if a report later arrives.

The first three historical phone runs used a different timing method and photo source. Keep their timings separate from this controlled batch. GPS, Wi-Fi-to-mobile handoffs, carrier SMS, notification timing and SUS are separate checkpoints.

## Failed observations and external deadline

A suspended device process cannot enforce its own timeout. After the run-8 stall, the host runner gained an independent 330-second deadline for observe and a 90-second deadline for other phases. It captures stdout/stderr without echoing credentials, ends only its own waiting adb client and force-stops only PinPoint on timeout. Queue/evidence files are not cleared. The host process/output/deadline logic was checked locally, including a deliberately delayed child process; this new watchdog was not deliberately triggered on the real phone.

Reopen PinPoint, wait for the existing UUID to recover, then use verify-recovery to check that exact report/photo. It accepts a failed observation, or an unfinished observation past the host deadline, and records complete_with_observation_failure with timing_valid=false. It does not turn the failed timer into a pass. For a wrong-network observation, verify-deviation preserves the original error and records complete_with_transport_deviation after checking the same report/photo. Neither creates a report. New enqueue requests refuse to overwrite an archived run number or bypass an unresolved prior trial.
