# Progress log

Living document: what is done, what was verified, what was decided and why.
Next-step pointer lives in [TASKS.md](TASKS.md).

Project: **Minimal Audiobook Player** — a dependency-free Android audiobook player
(minSdk 24 / Android 7.0, targetSdk 34), package `io.github.yuki2ka.audiobookplayer`,
version 1.3.0.

## Milestones

| # | Milestone | Status |
|---|-----------|--------|
| 0 | Toolchain available in this sandbox (offline Android SDK + JDK + d8/aapt2) | ✅ |
| 1 | Gradle project skeleton, manifest, resources, theme, icons | ✅ |
| 2 | Core logic: natural sort, time format, fingerprint, queue rotation | ✅ |
| 3 | Storage layer: SQLite positions, folder state, resume lookup | ✅ |
| 4 | Playback engine (MediaPlayer, audio focus, wakelock) | ✅ |
| 5 | Library scanner + folder browser | ✅ |
| 6 | Unified rotating list UI | ✅ |
| 7 | Player panel UI (rough bar, seek bar, ±step, transport) | ✅ |
| 8 | Settings page (swipe) + root folder picker | ✅ |
| 9 | Delete flow + confirmation setting | ✅ |
| 10 | Permission handling (API 24 → 34) | ✅ |
| 11 | JVM logic tests (incl. Unicode fixtures) | ✅ |
| 12 | Test build APK (minSdk 24 verify) + signing | ✅ |
| 13 | Documentation (README, architecture, build, install) | ✅ |
| 14 | UI pass: proportional seek pads, icon-only play, compact panel | ✅ |
| 15 | Settings pass: rescan first, quick folders, recent paths, green access state | ✅ |
| 16 | Pad geometry fix (mirrored back pad) + volume-key seeking (opt-in) | ✅ |
| 17 | Volume-key regression fix (1.2.1): session callback + release-driven gestures | ✅ |
| 18 | 1.3.0: 2x volume ramp, landscape tabs on the right, seek band, new APK name, project link | ✅ |

## Verification (last run)

| Check | Result |
|-------|--------|
| `tools/run-tests.sh` | **448 assertions, 0 failed** (locale forced to UTF-8) |
| `tools/check-resources.py` | 80 ids, 10 layouts, 111 strings, 28 drawables — all references resolve |
| `tools/build-apk.sh` | `artifacts/AudiobooksMinimalPlayer-1.3.0.apk`, **196 KB** |
| Manifest / badging | package correct, versionCode 5, versionName 1.3.0, **sdkVersion 24**, target 34, launcher `MainActivity` |
| DEX | dex 037, 116,752 B; no AndroidX, no Kotlin, no `java.time` / `java.nio.file` / streams / `Optional` references (API 24 safe) |
| Signature | `apksigner verify`: v2 + v3 true, `CN=Android Debug` (RSA-2048) |
| Alignment | `zipalign -c -v -p 4`: successful |

Reproduce:

```bash
JAVA_HOME=/tmp/toolchain/jdk-17.0.20.1+1 ANDROID_HOME=/tmp/sdk ./tools/build-apk.sh
./tools/run-tests.sh
```

## Design decisions

- **Toolchain**: this sandbox can only reach `github.com` / `pypi.org` / `registry.npmjs.org`.
  Google Maven and `dl.google.com` are unreachable, so Gradle + AGP cannot resolve here.
  A public mirror of the official SDK build-tools 34 + platform 34 + Temurin JDK 17 is used
  to compile, dex, align and sign a real APK with `aapt2`/`javac`/`d8`/`apksigner`, with
  `--min-api 24`. The repository still ships a normal Gradle project for Android Studio.
- **No third-party libraries at all** (no AndroidX, no Kotlin stdlib, no Material Components):
  smallest possible APK and runtime footprint, fastest cold start. Everything is framework
  classes + `java.*`.
- **Java** (not Kotlin) for the same reason: no 1.5 MB+ runtime and no reflection.
- **Views + ListView** instead of RecyclerView/Compose: framework only, efficient recycling,
  low memory. Icons are vector drawables (no bitmaps in memory), launcher icon is a small
  generated PNG set + adaptive icon XML.
- **Playback**: framework `MediaPlayer` (one decoder, released on every track change),
  audio focus, noisy-headset handling and a partial wake lock so screen-off playback
  continues. No foreground service in v1 (see TASKS.md) — keeps the process small.
- **Storage**: `SQLiteOpenHelper` with **two** tiny tables — `positions` (keyed by the
  content fingerprint) and `folder_state` (last file + scroll per folder). The unified
  queue order is *not* stored; it is re-derived from the natural sort and rotated to the
  last played file. Position identity = SHA-256 over size + first/last 64 KiB, so
  renamed/moved files still resume. A read of ≤128 KiB per file keeps recognition cheap.
- **Sorting**: natural (digit-aware) sort with `Collator` primary strength, NFC-normalised
  names — correct for Cyrillic / CJK / Arabic and for `Chapter 2 < Chapter 10`.
- **UI**: single dark theme, three swipeable pages (All files / Folders / Settings) with a
  visible tab bar, fixed transport panel, touch targets ≥48 dp (rows 64 dp, transport
  68 dp), controls spread evenly across the width.
- **Root folder** is chosen by the user; nothing outside it is scanned. Plain file paths are
  used directly (fast, low memory) with an explicit permission strategy per Android version
  (runtime read/write ≤ Android 10, "all files access" on 11+, audio-only read as fallback).
- **Rotation semantics**: rotate the *whole* list when a row is tapped (`c d a b` for a tap
  on `c` out of `a b c d`), move the finished file to the bottom on completion. Rotating only
  a prefix was a bug; do not reintroduce it.

## Bugs found and fixed during the final review

1. **`pages_container.xml` root was a `FrameLayout`** while `MainActivity` casts
   `R.id.pages` to `SwipePager` → guaranteed `ClassCastException` on launch.
   The root tag is now `<io.github.yuki2ka.audiobookplayer.ui.SwipePager>`; verified in the
   built APK with `aapt2 dump xmltree`. `check-resources.py` now validates custom view tags.
2. **Permission dead end on Android 11+**: the first-run card's button only asked for
   `READ_EXTERNAL_STORAGE`/`READ_MEDIA_AUDIO`, which cannot list an arbitrary folder, so
   the card could never be dismissed by granting. It now opens the "all files access"
   screen on 11+, falls back to audio-only access once if that is refused, and the card's
   text explains why it is still there.
3. **Unified list not rotated when playing from the folder page**: the playing file was
   pinned only in folder mode. `playFile()` now always rotates the unified queue.
4. Back from the folder page with no parent went straight out of the app; it now returns
   to the All-files page. Deleting the last played file also clears the remembered path.

## 1.1.0 UI changes (requested after trying 1.0.0)

- **Transport panel rebuilt**: no file name/status line, no extra progress bar,
  no percentage. It is now times (`played · total · remaining`), the seek bar,
  the proportional pads around an icon-only play button, and the
  `prev / −N s / +N s / next` row.
- **Proportional seek** (`ui/SeekPad` + `util/SeekCurve`): 2–60 s depending on
  the distance from the middle of the pad, quadratic so the inner half is fine
  control; the value is shown live and tested (monotonic, clamped, reachable 10 s).
- **Settings reordered**: rescan first, then the folder card with the access
  block at its top, quick folders (`Music`, `Books`, `Downloads`, `SD card`),
  the folder picker and the recent-folder list with delete buttons. The old
  "Reset" button is gone.
- **Default folder is `<shared storage>/Books`**, created on first scan when the
  platform allows it. Switching to a folder that cannot be created now keeps the
  old library instead of silently pointing at nothing.
- **Access state is visual**: with full access the notification block is hidden
  and the card gets a green outline instead of text.
- Fixed while testing: `SeekPad` used to reset its value before seeking (every
  tap would have seeked 2 s); switching the root folder no longer stops playback
  of a file that still exists.

## 1.2.0 changes

- **Back pad was mirrored twice**: `setDirection(-1)` was never called on the
  left pad, so it drew a "forward" arrow *and* seeked forwards. The direction is
  now declared in the layout (`app:padDirection="-1"`), parsed through a
  `declare-styleable`, and the 0..1 mapping lives in the tested
  `SeekCurve.fraction(direction, x, width)`. `check-resources.py` fails if a
  `SeekPad` in any layout omits the attribute.
- **Volume keys seek while the screen is off** (opt-in): `playback/VolumeKeySeeker`
  activates a framework `MediaSession` with a remote `VolumeProvider` only while
  (playing && screen off && enabled), and **installs a `MediaSession.Callback`** —
  without one, Android routes the key to the session and then drops the
  adjustment, so the keys do nothing at all (the 1.2.0 bug). Android sends
  `+1`/`-1` for the press (and each auto-repeat) and `0` for the release, so
  `util/VolumeGesture` decides from those events: quick release → seek, repeat
  or late release → stream volume, no release at all → volume step after 2.5 s.
  The 450 ms threshold and the timeout are unit-tested. Screen on → session
  inactive, keys keep stock behaviour.
- Step seeking (pads, ± buttons, volume keys) now shares
  `SeekCurve.steppedPosition()` and is covered by tests.

## Known risks (device-only)

- No emulator/adb in the sandbox, so runtime flows are reviewed but not executed:
  permission dialogs, MediaPlayer focus changes, delete confirmation, list recycling.
- Background playback has no foreground service, so aggressive OEM ROMs may kill it.
- Volume-key capture (`MediaSession` + `VolumeProvider`) could not be tried on a
  device in this sandbox. The 1.2.0 build was reported to leave the keys dead in
  this mode; the cause (no session callback) is fixed in 1.2.1, and the gesture
  logic is now unit-tested, but the confirmation has to come from the Android 7
  phone. A ROM that never forwards the rocker to a media session keeps the keys
  on their stock behaviour.

Details of the internals: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
