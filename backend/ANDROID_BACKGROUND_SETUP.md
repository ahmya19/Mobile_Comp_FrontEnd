# Android background upload setup

PinPoint keeps reports/photos in its durable private queue. Android and device-vendor policies can delay execution even with a working Internet connection. A saved-on-device message is not a server acknowledgement.

## TECNO CL8 / Android 15

The November 22 diagnosis found a separate TECNO Battery Lab policy for Pinpoint. Android App Battery Usage showed Unrestricted and Phone Master App Launch already showed Allowed, while Battery Lab still had Intelligent Management selected. Targeted system logs recorded Hiber freezing PinPoint's UID and a job-proxy event. The kernel UID group was frozen even though Android's process flag said isFrozen=false.

For this prototype's background upload checks:

1. Open Settings > Battery Lab > Power Saving Management for Apps. Menu wording varies with HiOS version. On the tested phone, the installed Battery Lab app exposes this screen.
2. Search for **Pinpoint** and open its row.
3. Select **No Restrictions**. Reopen the row to verify the selected radio button. This changes only PinPoint; it can increase its background battery use.
4. In Android App info > Battery > Allow Background Usage, keep background usage enabled. Unrestricted was selected during these checks.
5. In Phone Master > App Launch, verify Pinpoint is Allowed. It was already allowed on the tested phone; no Manage All or global optimization switch was changed.

The app does not modify these settings automatically. No global power saver, freezer control, security protection or other app's settings were disabled. No root command was used.

## Verify the ordinary app

Use a demo citizen and a uniquely labeled synthetic photo report, without personal GPS or originals. Verify the phone is actually offline before saving. Wait for Report Saved on Device, then press Home without force-stopping. Arm the external observer offline; reconnect through the specified transport without opening PinPoint or pressing Retry uploads. Keep screen-on and screen-off conditions separate.

Compare the stable report UUID, one server row, downloaded/decoded compressed-photo hash and local Synced acknowledgement. Record availability and validation endpoints separately. Host polling times are not exact reconnect-to-app-acknowledgement latency; subsecond validation intervals can be dominated by sampling.

The observed comparisons and remaining limits are in [BACKGROUND_SYNC_INVESTIGATION.md](BACKGROUND_SYNC_INVESTIGATION.md). The established WorkManager scheduler remains in the delivered app. An Android user-initiated transfer experiment also stalled under the OEM restriction and was removed after comparison; it is not an additional runtime requirement.

These USB-powered, single-phone Wi-Fi checks do not certify other vendors, unplugged/overnight Doze, carrier reconnection, reboot, simultaneous phones or the proposal's statistical targets. Keep CP-25's remaining measurements explicit.

## Recorded comparison

| Existing WorkManager runtime | Wi-Fi availability to server observed | Validation to server observed | Sampled state |
| --- | --- | --- | --- |
| No Restrictions, Home | 1.310 s | 0.096 s | Awake, app off screen, kernel-unfrozen |
| Intelligent Management, reverse control | No row in 90 s | Absent for 82.831 s | Awake, app off screen, frozen in 18/19 samples |
| No Restrictions, final build | 1.337 s | 1.337 s | Asleep, app off screen, kernel-unfrozen |

Restoring No Restrictions after the reverse control recovered its same saved report without reopening PinPoint. The phone was left with No Restrictions, Android Unrestricted, App Launch Allowed and the tested final APK. Its original airplane/Wi-Fi/display settings were restored; the temporary USB stay-awake override is no longer active. The setup is a verified mitigation on this phone, not a universal Android timing guarantee.
