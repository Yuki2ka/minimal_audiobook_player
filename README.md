# Minimal Audiobook Player

A dependency-free Android audiobook player: **21 Java classes, no libraries at all**
(no AndroidX, no Kotlin runtime, no image loaders), a **172 KB APK**, and a UI built
for imprecise fingers on small, old phones.

* **Android 7.0+** (`minSdk 24`), compiled and targeted against API 34.
* Handles non-Latin file names everywhere: NFC normalisation, locale-aware
  collation, and natural ("Chapter 2 before Chapter 10") ordering.
* Remembers **where you stopped in every file**, not just the last one.

## Install the test build

```
artifacts/MinimalAudiobookPlayer-1.0.0.apk
```

```bash
adb install -r artifacts/MinimalAudiobookPlayer-1.0.0.apk
```

Or copy the APK to the phone and open it (Android will ask to allow installing
from this source). The shipped build is **debug-signed** (`CN=Android Debug`),
so it can be installed next to nothing else and Android may warn about the
certificate — that is expected for a test build. It is zipaligned and signed
with the v2 + v3 schemes.

On first launch the app asks for storage access. If you decline, it offers its
own app-specific folder, which needs no permission at all.

## What it does

| Screen | Behaviour |
|--------|-----------|
| **All files** (tab 1) | One flat list of every audio file under the root folder, sorted naturally *ignoring folders*. The file that is playing is pinned to the top: when a file finishes it moves to the bottom and the next one rises to the top. |
| **Folders** (tab 2) | Folder explorer with sub-folders, per-folder file counts, breadcrumb path, and an up button. Each folder reopens at the last file you played in it. |
| **Settings** (tab 3) | Root folder (with a built-in picker and a reset), seek step 1–60 s, "delete without confirmation", keep screen on, storage-access status, clear all saved positions, rescan, library file count. |

Swipe left/right to change pages; the tab bar does the same. Back goes
settings → all files, and folders → parent folder → all files.

### Playback panel (fixed at the bottom)

* Title + folder/state line.
* Thin **rough progress bar** for an at-a-glance position.
* Precise **seek bar** — tap anywhere on it to jump.
* Position, percentage and remaining time.
* **−N s / +N s** buttons (N is the configured step; long-press jumps 60 s).
* **Previous file · Play/Pause · Next file**, each an equally wide 68 dp button.

Every row of both lists is at least 64 dp tall and the delete button is its own
48×48 dp target on the right, so tapping a row to play almost never deletes it.
Deleting asks yes/no unless you switch the confirmation off in settings.

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
  model/      AudioFile, FolderNode, ScanResult
  playback/   PlayerEngine     one MediaPlayer, audio focus, wake lock
              PlayQueue        the rotating "All files" order
  scan/       Scanner, Library recursive scan on a worker thread
  ui/         AudioFileAdapter, FolderAdapter, PickerAdapter,
              FolderBrowser, SwipePager
  util/       Fingerprint, NaturalSort, Storage, TimeFormat
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
* Scans only touch the chosen folder, run on one low-priority daemon thread, and
  store nothing but names, sizes and timestamps.

## Known limitations

* **No foreground service**, so Android may kill playback in the background on
  aggressive OEM ROMs, and there are no lock-screen or notification controls.
  Headphone unplugging pauses playback; Bluetooth media buttons are not handled.
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
