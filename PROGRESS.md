# Progress log

Living document: what is done, what was verified, what was decided and why.
Next-step pointer lives in [TASKS.md](TASKS.md).

Project: **Minimal Audiobook Player** — a dependency-free Android audiobook player
(minSdk 24 / Android 7.0, targetSdk 34), package `io.github.yuki2ka.audiobookplayer`,
version 1.7.2.

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
| 19 | 1.4.0: fast play button + speed setting (x1.1–x2.0, default x1.3) | ✅ |
| 20 | 1.5.0: discrete whole-row circular browsing + folder-row deletion | ✅ |
| 21 | 1.6.0: folder delimiters, equal rows, live drags, equal/highlighted speed buttons | ✅ built |
| 22 | 1.7.0: readable file rows (wrap / font-scaled discrete height) + speed under the glyph | ✅ built |
| 23 | 1.7.1: compact gaps shared by smooth and discrete rows (measured one-line height) | ✅ built |
| 24 | 1.7.2: All-files list is a real folder tree again (name / bare-line separators, tree order, rotation of whole rows) | ✅ built |

## Verification (last run)

| Check | Result |
|-------|--------|
| `tools/run-tests.sh` | **601 assertions, 0 failed** (locale forced to UTF-8) |
| `tools/check-resources.py` | 91 ids, 11 layouts, 125 strings, 31 drawables — all references resolve, plus the file-row / speed-label / row-spacing / folder-tree guards |
| Full app Java compilation | Passed against an Android boot classpath, including the modified adapters / list view |
| `tools/build-apk.sh` | `artifacts/AudiobooksMinimalPlayer-1.7.2.apk`, **228 KB** |
| Manifest / badging | package correct, versionCode 11, versionName 1.7.2, **sdkVersion 24**, target 34, launcher `MainActivity` |
| DEX | `classes.dex` packaged by D8 with `--min-api 24`; no AndroidX or Kotlin runtime |
| Signature | `apksigner verify`: v2 + v3 true, `CN=Android Debug` (RSA-2048) |
| Alignment | every entry's data 4-byte aligned (`tools/zipalign.py` verifies it) |

Reproduce:

```bash
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/sdk ./tools/build-apk.sh
JAVA_HOME=/path/to/jdk-17 ./tools/run-tests.sh
# Sandbox build (runtime-only JDK + tool jars, no Gradle, no native build-tools):
JAVA_HOME=/tmp/toolchain/jdk/jdk4py/java-runtime ANDROID_HOME=/tmp/sdk \
  AAPT2=/tmp/toolchain/aapt2/package/bin/x64/linux/aapt2 \
  BUILD_TOOLS=/tmp/sdk/build-tools-34 PLATFORM_JAR=/tmp/sdk/platforms/android-34/android.jar \
  ./tools/build-apk.sh
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
  visible tab bar, fixed transport panel, touch targets ≥48 dp (rows 60 dp, transport
  68 dp), controls spread evenly across the width.
- **Root folder** is chosen by the user; nothing outside it is scanned. Plain file paths are
  used directly (fast, low memory) with an explicit permission strategy per Android version
  (runtime read/write ≤ Android 10, "all files access" on 11+, audio-only read as fallback).
- **Playback speed** (`util/PlaybackSpeed`, pure Java): speeds are integer percent
  (x1.3 = 130) so the "is this button's speed active?" test is exact instead of a float
  comparison, and the settings slider maps 0…9 → x1.1…x2.0 without rounding holes.
  The press rules (pause / play / switch) live in `PlaybackSpeed.actionFor()` and are
  unit-tested. `PlayerEngine` latches the requested speed and applies it **only while
  playing**: `setPlaybackParams()` on a prepared-but-paused player implicitly starts
  playback on several Android versions, which would have turned "press fast play while
  paused" into an immediate play. A rejected speed falls back to x1 with a toast.
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

## 1.7.0 changes (layout regression pass)

- **File rows grow with their text again.** The discrete-list rewrite put the
  name and the size / saved-position info into a fixed 64 dp vertical box with
  a weighted inner row; on phones with larger text the info line was cut in
  half. `item_audio.xml` is now a `FrameLayout` with `wrap_content` +
  `minHeight=64 dp` (as in 1.4.0): names may wrap to two lines, and the
  folder-group closing rule is overlaid at the bottom instead of consuming a
  pixel of text height.
- **Discrete rows still have one exact extent.** `AudioFileAdapter` fixes file
  rows and delimiters to `64 sp` (64 dp at the normal font scale, larger when
  the user scales text up) and limits names to one line in that mode.
  `DiscreteListView` uses that explicit extent for the bottom gutter and the
  drag step, instead of measuring whatever child happened to be laid out —
  which could expose a half row at the bottom.
- **Fast speed is back under the double triangle.** The inline 1.6.0 label
  shared 88 dp with the icon and got ellipsised to "..." at larger font sizes.
  The button stays the same 88×68 dp frame with the same pause glyph, accent
  highlight and press rules, but now stacks a 28 dp glyph over a full-width
  label with no `singleLine`/`ellipsize`. The alternative — large background
  triangles with contrasting text over them — was rejected: the text would
  have to fight the accent-filled selected state, and overlays age badly with
  font scaling and low-contrast screens.
- `tools/check-resources.py` now fails if either layout shape regresses (fixed
  root, one-line title, inline/ellipsised speed label, missing `sp` row dimen).
- Built and verified: 560 assertions, 0 failed; resource check, full Java
  compilation, `aapt2` resource dump (`64.000000sp`), D8 with `--min-api 24`,
  zipalign and v2 + v3 signing all pass.
- The `64 sp` discrete extent was replaced in 1.7.1: it grew with the font
  scale and made the gaps between discrete rows much larger than in smooth mode.

## 1.7.2 changes (All-files list is a folder tree again)

- **Sorted as a tree, not as a flat list.** `Scanner` used to sort every file by
  name across folders (`Collections.sort(allFiles, FILE_COMPARATOR)`), so a
  book's chapters interleaved with another folder's files and the "folder
  groups" of 1.6.0/1.7.x were really just name runs. `model/TreeOrder` now walks
  the scanned tree depth first — folder name row, sub-folders, the folder's own
  files, closing row — and `ScanResult.allFiles` is that order.
- **Two separators per folder.** An opening row shows the folder icon, its name
  and a rule to the right; a closing row is only the rule, no name. The whole
  row is indented by its nesting depth (`folder_indent`, 14 dp per level), so a
  sub-folder starts one step right of its parent's name. Folders without any
  audio (covers/notes, or a folder whose last file was just deleted) get no
  separators at all.
- **Rotation moves files and separators in one list.** `PlayQueue` now holds
  `model/QueueEntry` rows (file, opening row, closing row) instead of plain
  files, so a tap, "next"/"previous", and discrete browsing rotate the same
  ordered rows — the tree order is never re-derived or re-sorted mid-session.
  `FileListRows` exposes those rows 1:1 to `AudioFileAdapter` (a separator is
  simply not clickable), and the old `row_group_end` overlay is gone because a
  folder is closed by its own row.
- **The playing file stays on top.** When it is its folder's first file, the
  rows that name the folder (and its parents) lead it, exactly like 1.4.0;
  otherwise the file itself is the head and its folder's closing line sits right
  below it (the name reappears when the ring wraps). The activity scrolls to the
  first audio row rather than row 0.
- Verified: 601 assertions (tree order, rotation-is-a-rotation of the tree,
  folder-shaped fixtures for a real library), resource check with new
  folder-tree guards, full app compilation, D8 `--min-api 24`, 4-byte aligned
  entries, v2 + v3 signature — `AudiobooksMinimalPlayer-1.7.2.apk`, 228 KB,
  versionCode 11.

## 1.7.1 changes (compact list spacing)

- **Smooth rows are ~20% tighter.** `row_min_height` 64 → 60 dp, text padding
  10 → 8 dp, row margins 2 → 1 dp, and folder delimiters 40 → 32 dp. The rows
  still wrap two-line names and keep a 48 dp delete target.
- **Discrete gaps now match smooth mode.** The 1.7.0 `64 sp` constant scaled
  with the system font (e.g. 83 dp at 1.3x) while smooth rows stayed near
  64 dp, adding an empty band around every file and turning folder delimiters
  into tall, mostly empty rows. `AudioFileAdapter.discreteRowHeightPx()` now
  measures a one-line file row at the current font size and floors it at
  `row_min_height`; file rows and delimiters all use that one height.
  `DiscreteListView` receives it through `setDiscreteRowHeight()` and uses it
  for the bottom gutter and the finger's row step, so both modes have the same
  compact spacing instead of a font-scaled constant.
- `tools/check-resources.py` guards the compact geometry and fails on a fixed
  `sp` discrete height, a >60 dp row minimum, >8 dp padding or >1 dp margins.
- Built and verified: 560 assertions, 0 failed; resource check (including the
  new guards), full Java compilation, D8 with `--min-api 24`, zipalign and
  v2 + v3 signing all pass.

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
