# Minimal Audiobook Player

A dependency-free Android audiobook player: **30 Java classes, no libraries at all**
(no AndroidX, no Kotlin runtime, no image loaders), a **224 KB APK**, and a UI built
for imprecise fingers on small, old phones.

* **Android 7.0+** (`minSdk 24`), compiled and targeted against API 34.
* Handles non-Latin file names everywhere: NFC normalisation, locale-aware
  collation, and natural ("Chapter 2 before Chapter 10") ordering.
* Remembers **where you stopped in every file**, not just the last one.

## Install the test build

```
artifacts/AudiobooksMinimalPlayer-1.6.0.apk
```

```bash
adb install -r artifacts/AudiobooksMinimalPlayer-1.6.0.apk
```

Or copy the APK to the phone and open it (Android will ask to allow installing
from this source). The shipped build is **debug-signed** (`CN=Android Debug`),
so it can be installed next to nothing else and Android may warn about the
certificate — that is expected for a test build. It is zipaligned and signed
with the v2 + v3 schemes.

On first launch the app asks for storage access. The default library is
`/storage/emulated/0/Books` (created automatically once access is granted);
Music, Downloads and the SD card root are one tap away in settings. If you
decline, the app offers its own app-specific folder, which needs no permission.

## What it does

| Screen | Behaviour |
|--------|-----------|
| **All files** (tab 1) | One flat list of every audio file under the root folder, sorted naturally *ignoring folders*. A folder name appears once as a delimiter before each contiguous folder group, with a rule after its last file—not repeated under every file. Playback starts with the current file at the top; when it finishes it moves to the bottom and the next one rises. Optional discrete browsing moves the queue view independently and wraps around. |
| **Folders** (tab 2) | Folder explorer with sub-folders, per-folder file counts, breadcrumb path, and an up button. Each folder reopens at the last file you played in it. Subfolders have a separate trash button; confirmed deletion removes the folder and its contents. |
| **Settings** (tab 3) | Rescan first, then the folder card: storage access (only while it is missing), one-tap **Music / Books / Downloads / SD card** folders, the built-in folder picker and the recent-folder list (tap to switch, trash to forget); then seek step 1–60 s, **discrete file-list scrolling**, "delete without confirmation", keep screen on, **volume keys seek while the screen is off**, clear all saved positions, about. The **version line is a link to the GitHub project** (opens the browser; the app itself still declares no internet permission). |

Swipe left/right to change pages; the tab bar does the same. Back goes
settings → all files, and folders → parent folder → all files.

### Discrete file-list scrolling

The Settings checkbox is off by default, preserving the normal smooth ListView.
When enabled, file rows keep exactly the same 64 dp height as in smooth mode,
folder delimiters use that height too, and only complete rows are shown. A
vertical swipe jumps by whole rows without a pixel scroll or fling; rows change
*while dragging* as each full row is crossed. A short swipe moves one row on
release. Swipe up to browse forward. Swipe down to browse backward: at the top, the last queue
item is inserted at the beginning, so previous tracks are immediately reachable.
The list wraps at either end without changing the playing track. Starting or
advancing playback re-anchors the current track at the top, like a virtual sheet
of files.

### Layout

Portrait: tabs, then the page, then the fixed panel at the bottom. Landscape:
pages on the left, and the three tab headers sit at the top of the right column
directly above the transport panel, so the list keeps the full height and the
tabs stay next to the controls.

### Playback panel (fixed at the bottom)

```
2:00            10:00            -8:00      played · total · remaining
───●──────────────────────────────          seek bar, tap to jump

[ 2–60 s ▶]   ( ▶ ) [▶▶ x1.3]  [◀ 2–60 s]  seek pads · equal play buttons
[prev]  [- 10 s]  [+ 10 s]  [next]
```

* **Proportional seek pads** — tap (or drag) near the play button for a 2 s
  nudge, farther outwards for up to 60 s; both pads grow away from the button
  (the left one is mirrored). The pad shows the amount live and resets to
  "2–60 s" when released.
* **Play/Pause** is icon-only and centred between the two pads.
* **Fast play** sits right of the play button in the same 88×68 dp frame
  (double triangle with the chosen speed beside it, default **x1.3**). The
  rules are the same for both buttons:
  * paused → the press starts playback **at that button's speed** (x1 for the
    plain button, the speed from Settings for the fast one);
  * playing at that speed → the press **pauses** (second press of the same
    button);
  * playing at the other speed → the press **switches the speed** and keeps
    playing.
  The button whose speed is actively playing is accent-filled and shows the
  pause glyph; the inactive/paused buttons stay dark and outlined. This makes
  both the active speed and the button that will pause clear at a glance. The
  speed stays for the next chapter as well.
* **The whole seek band is the slider.** The time labels above the bar are not
  dead space: a tap anywhere in that band lands on the same position as a tap on
  the bar below it, so the target is 90 dp tall without adding any height. A
  wider 15 dp gap separates it from the pads, so a thumb that overshoots hits
  nothing instead of the seek pads.
* **−N s / +N s** use the step from settings (long-press them for a full minute).
* The file name is deliberately not repeated here: the playing file is pinned to
  the top of the All-files list.

Audio-file and subfolder rows are at least 64 dp tall, and each delete button
is its own 48×48 dp target, set back from the right edge. Tapping a row to play
or open it therefore almost never deletes it. Deleting asks yes/no unless you
switch the confirmation off in Settings; deleting a folder removes its contents
too. The parent-navigation row has no delete button.

## Volume keys in the dark

In Settings you can turn on **"Volume keys seek (screen off)"**. While the screen
is off and audio is playing, a **short press** of a volume key seeks by the
configured step (10 s by default) instead of changing the volume — no need to
find the phone or wake it. **Holding** a key still changes the volume, and with
the screen on the keys behave normally (system volume panel included).

The app captures the keys through a framework `MediaSession` with a remote
`VolumeProvider`. Android forwards such a press as "adjust up/down" and then
"0" when the key is released, so the gesture logic (`util/VolumeGesture`,
covered by the JVM tests) works from those events: a release that arrives
within 450 ms is a tap → seek, while a key repeat (or a later release) is a
hold → music volume. If a ROM never sends the release event, the key falls back
to one volume step instead of doing nothing.

The session installs a `MediaSession.Callback`: Android silently drops every
adjustment routed to a session without one, which is what made the keys dead in
1.2.0.

Holding a key moves the volume **twice as fast** as the stock rocker (two steps
per event), because one step per key-repeat felt sluggish with the screen off.
With the setting off, or the screen on, the keys keep their normal single step.

Because there is no foreground service, this only works while the playback
process is alive — Android may end it if it aggressively reclaims memory.

## Storage access per Android version

The app reads a folder you choose with plain `File` APIs (fast, no MediaStore
index rebuilds). That needs different permissions per release:

| Android | Version | Permission | Without it |
|---------|---------|------------|------------|
| 7.0–9 | 24–28 | `READ` + `WRITE_EXTERNAL_STORAGE` at runtime | app folder still works |
| 10 | 29 | the same, plus `requestLegacyExternalStorage` | app folder still works |
| 11–12L | 30–32 | `MANAGE_EXTERNAL_STORAGE` ("all files access") | audio-only read fallback: playback usually works, folder listing and deleting may not |
| 13–14 | 33–34 | `MANAGE_EXTERNAL_STORAGE` | `READ_MEDIA_AUDIO` read fallback |

The settings page shows the current level and a button that opens the right
system screen; the first-run card offers the same plus the zero-permission app
folder.

## Build

### Android Studio / Gradle

The repository is a normal Gradle project (`app/build.gradle`, AGP 8.5.2).

```bash
./gradlew assembleDebug
```

### Offline command-line build (what produced the shipped APK)

No Gradle, no network — just `aapt2 → javac → d8 → zipalign → apksigner`:

```bash
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/sdk ./tools/build-apk.sh
```

`ANDROID_HOME` must contain `build-tools/34.0.0` (`aapt2`, `d8`, `zipalign`,
`apksigner`) and `platforms/android-34/android.jar`. The APK is written to
`artifacts/` and signed with a generated debug keystore.

### Tests and static checks

```bash
./tools/run-tests.sh          # 145 plain-JVM assertions, no JUnit needed
python3 tools/check-resources.py
```

`run-tests.sh` compiles the Android-free classes and runs Unicode, sorting,
fingerprint, queue-rotation and scanner tests (Cyrillic, Greek, Japanese,
Korean, Hebrew, emoji, NFC/NFD pairs). `check-resources.py` verifies that every
`R.id`/`R.string`/`R.drawable` reference from Java exists, that custom view tags
in layouts map to real classes with the right constructor, and that every
`getString(R.string.x, …)` passes the number of arguments the format string
expects (a mismatch is a runtime crash that aapt2 cannot catch). The build
script runs it automatically.

## Project layout

```
app/src/main/java/io/github/yuki2ka/audiobookplayer/
  App.java                     process-wide Db / Library / PlayerEngine
  MainActivity.java            the single screen (1.4 kLOC)
  FolderPickerActivity.java    plain directory picker
  data/       Db, Prefs        SQLite positions + folder state, SharedPreferences
  model/      AudioFile, FileListRows, FolderNode, ScanResult
  playback/   PlayerEngine     one MediaPlayer, audio focus, wake lock
              PlayQueue        the rotating "All files" order
  scan/       Scanner, Library recursive scan on a worker thread
  ui/         AudioFileAdapter, FolderAdapter, PickerAdapter,
              FolderBrowser, SwipePager
  util/       DiscreteDrag, Fingerprint, NaturalSort, Storage, TimeFormat
app/src/main/res/              9 layouts (+ landscape), 26 vector/PNG assets
tools/                         build, tests, resource checks
docs/ARCHITECTURE.md           internals and design rationale
```

## Small-memory choices

* No third-party libraries and no Kotlin runtime: nothing is loaded that is not used.
* Framework `ListView` with recycled rows and a plain `ViewHolder` — no
  RecyclerView, no Compose, no per-scroll allocations beyond text.
* One `MediaPlayer`, released on every track change; no second decoder is kept
  for gapless pre-loading.
* Vector icons; the launcher icon is a few small PNGs plus an adaptive-icon XML.
* The content fingerprint reads at most **128 KiB** per file (size + first and
  last 64 KiB), so recognising a 500 MB audiobook costs no more than a small image.
* Positions live in SQLite keyed by that fingerprint and are deleted when a file
  finishes, so the table does not grow forever.
* Storage access is one line of state: with full access the whole "storage
  access" block disappears and the folder card is outlined in green.
* Scans only touch the chosen folder, run on one low-priority daemon thread, and
  store nothing but names, sizes and timestamps.

## Known limitations

* **No foreground service**, so Android may kill playback in the background on
  aggressive OEM ROMs, and there are no lock-screen or notification controls.
  Headphone unplugging pauses playback; Bluetooth media buttons are not handled.
  The volume-key feature depends on the same process staying alive.
* Volume keys go through the framework's media-session volume path. 1.2.0
  missed the `MediaSession.Callback` that Android requires before it delivers
  anything to a remote-volume session, so the keys did nothing at all; 1.2.1
  fixes that. A ROM that does not forward volume keys to a media session at all
  simply leaves the keys on their stock behaviour.
* Deleting files uses the file-system API, so on Android 11+ it needs
  "all files access". The read-only fallback can play but may not delete.
* Positions are keyed by content: two byte-identical copies of the same file
  share one saved position.
* The APK in `artifacts/` is **debug-signed** and has **not been run on a
  physical device or emulator** (the sandbox has no adb/emulator). All logic
  tests pass, but permission dialogs, first-run flow and MediaPlayer focus
  handling have only been reviewed statically.

## License

MIT — see [LICENSE](LICENSE).
