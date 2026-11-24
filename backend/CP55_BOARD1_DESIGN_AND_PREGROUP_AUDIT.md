# CP-55: Board 1 design audit (login, onboarding, recovery, permissions) and pre-groupmate verification

November 24, 2026. Base `main` `c0cfd2a` (CP-54). Signed-out screens were captured on the **emulator** (`PinPoint_Backend_API36`, app data cleared there only, stopped afterwards). The owner's TECNO phone stayed signed in: no sign-out, uninstall or data clear; the secure-session file hash only changed through normal token refresh (same account, still signed in) and the device-id file hash never changed. The phone serial is not recorded.

## Design review (impeccable, native Android method: design review alone; the web detector does not apply)

Compared the live captures with PDF Page 2 (frames 1.1 to 1.7).

- **Matches the reference:** 1.1 launch splash (violet, white pin tile) and the in-app splash; 1.2 onboarding (dark card with orbit rings and category chips, "1 OF 3", Skip, pager dots, **Get started** and **I already have an account** on the last slide, **Next** on slides 1 and 2); 1.3 login (violet pin tile, 52 px-style grey fields, show-password eye, disabled **Sign in** until filled); 1.4 register (STEP 1 OF 2, strength bars, **Required** vs **Recommended, not required** rules, terms row); 1.5 forgot (lock tile, expiry warning card); 1.6 reset (six code boxes, new/confirm password, rule list); email confirmation (same box pattern); 1.7 permission primer (Location/Notifications/Camera cards with enabled states, Allow & continue, Maybe later). At 1.3x font scale onboarding, login, register and the primer show no clipping and keep their buttons visible.
- **Scope kept:** email/password citizen sign-in only. No mobile-number login button, no anonymous posting (the reference's extras stay out by the approved decisions).
- **On the phone (signed in):** Profile rows **App tour & onboarding** (opens the 3-slide tour with **Close**) and **App permissions** (primer shows "Location/Notifications/Camera enabled", **Continue**) work without signing out, also at 1.3x.

## Defects found and fixed (before and after)

1. **System Back on Register left the app** (it is a mode of the sign-in screen with no back handler). Now Back returns to Sign in, as the on-screen back arrow already did. Verified on the emulator.
2. **The six code boxes looked like the input but did nothing** (the real field was the plain "Confirmation code"/"Verification code" box below, with the first box shown as focused). Tapping the boxes now focuses the real field and typing fills the boxes (verified: typed 123456 appears as six digits). The field and its label stay, so tests and paste keep working.
3. **"Confirm an existing account" looked active but did nothing until an email was typed.** It is now dimmed while disabled.

Not changed (observations): the terms checkbox starts ticked (as in the reference); the Login footer links are stacked rather than bottom-anchored; the Android 12+ system launch splash shows the adaptive icon with fragments of its decorative shapes clipped (the in-app splash that follows is correct).

## Not exercised

`ReportSuccessScreen` spring animation was not triggered on the phone (it would need a real submission with the owner's account); `OnboardingAndMotionTest` covers it on the instrumentation side.

## Verification

- **TECNO phone, 14 suites: 87/87 pass** (includes `OnboardingAndMotionTest` and `NativePickerTest`), run on the CP-54 build and again on the build with the fixes. **Real-SIGKILL S1 to S7 pass** on both, no harness errors, so the 650 ms splash, onboarding and navigation transitions added no flakiness or process-death regression.
- Gates: backend `npm test` 81/81; Edge check; JVM unit tests; lint 0 errors; release DEX audit 0 hits for secret/service-role/JWT/fixture/loopback strings (positive control found the project host); staged diff scan for secrets and the phone serial 0 hits.
- **`GROUPMATE_TESTING_GUIDE.md` checked against the real labels** and corrected: Task 1 (Skip, Allow while using the app / Allow & continue / Maybe later), Task 2 (center Camera button, **Road/Trash** tiles, **Choose or adjust map pin** with a long-press, **Submit Report**), Task 3 (**Profile -> Saved offline reports (N)**, **Save changes**, **Delete saved report**), Task 4 (map chips **All - N / Road / Lights / Flood / Trash**, **Vote for urgency / Remove my urgency vote**, **Community votes: N**, **View community reports**, **Search loaded reports...**), Task 5 (**Status** then **Save status**). It previously named an "Upvote / Support" button, `Road Damage`/`Flooding` map pills and a My Reports shortcut to saved reports that do not exist as written.

## Not claimed

Real camera photo, 10-point outdoor GPS walk, 10 to 15 SUS forms, Brevo SMTP, SMS gateway phone, $0 restore quote and a signed release keystore remain owner/groupmate work.
