# CP-56: groupmate testing round, issues and fixes

Source: two groupmates' notes ("concerns to debug.pdf", November 27, 2026). Evidence labels: **hosted** (real Supabase project), **emulator** (`PinPoint_Backend_API36`), **unit/SQL** (JVM and in-memory Postgres), **not run**. The owner's phone was not touched.

| # | Reported issue | Cause | Fix | Evidence |
|---|---|---|---|---|
| 1 | Signup email had a link that errors; wanted the 6-digit code | Default link email; redirect target `http://localhost:3000`; templates cannot be edited on Free with the built-in sender; OTP length was 8 but the app takes 6 | Brevo Free SMTP enabled, templates published, OTP length 6 (hosted settings, BG-027) | hosted: live settings read back (templates have the code token and no link, OTP 6). Outside-address delivery **not yet recorded** |
| 2 | SMS guide count and text looked wrong | A full `PIN ROAD;Alangilan;...` typed into the details box was prefixed again; the septet count itself was correct (53 for that string) | Prefix stripped; helper text under the field | unit |
| 3 | Portrait photo showed as landscape in the form | Preview ignored the EXIF rotation (the uploaded photo is already rotated) | `PhotoCompressor.preview` applies it | emulator: picker suite 3/3. **Rotation on a real camera photo: not run** |
| 4 | Red `UnknownHostException` banner on top | Feed errors stored as raw `Class: message` | Banner runs `UiText.humanize` | unit |
| 5 | Make the photo tappable to see it in full | Cropped card only | Full-screen viewer with pinch-zoom on the form and on Report Details | compiles; emulator suites pass. **Gesture feel on a phone: not run** |
| 6 | Map pin is hard to zoom/slide; page scrolls instead | Map inside a vertical scroll | Touch listener asks the parent not to intercept while a finger is on the map | compiles. **Not run on a phone** |
| 7 | Barangay filter "Alangilan" showed nothing | Exact, case-sensitive match | Case-insensitive contains match (server and local rows) | unit; hosted data checked read-only: 34 Alangilan rows |
| 8 | Logout had no confirmation | Dialog only when unsent reports existed | Always asks | emulator: UiParity 6/6 (two other tests updated to confirm) |
| 9 | Profile could edit only the name | `profiles` had no such columns | Migration 202611270012 (nullable columns, checks, `save_profile_details`, extended `my_profile`), app fields with validation | SQL: backend 82/82 (privacy, normalising, check violations, clearing); unit. Migration applied to the hosted project November 27 (see below) |
| 10 | Paired push times not tested | A test, not a defect | Pending for the groupmates | not run |

Gates run: backend `npm test` 82/82; `:app:testDebugUnitTest` 31/31; `:app:lintDebug` 0 errors; `assembleDebug` and `assembleDebugAndroidTest` built. Emulator: `UiParityAndCameraDraftTest` 6/6, `NativePickerTest` 3/3. Not run: the full 14-suite phone run, SIGKILL S1-S7, `assembleRelease` and the release DEX audit, `npm run check:edge`.

**Applied November 27 (owner approved):** the file ran as one transaction through `supabase db query --remote -f` (not `db push`; the hosted migration history table was not edited). `python backend/scripts/catalog_drift.py` then reported identical, 0 differences (11 tables, 18 functions). The profile save itself was **not** exercised end to end on the hosted project (no fixture round trip yet); SQL behaviour is covered by the replay tests. PR #39 merged as `0481850`. Shareable debug APK built from that commit: SHA-256 `aecc319edaf534505db748594f73839763a6e34ce2fe1c9e2f93d0c941461d1a` (copy in the PinPoint-Share folder). Rollback: `drop function public.save_profile_details(text,text)`, restore `my_profile` from migration 001, drop the two constraints and two columns (no data is lost for existing users).
