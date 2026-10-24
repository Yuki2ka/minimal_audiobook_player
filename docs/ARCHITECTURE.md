# Architecture

This document explains how the app is put together and why. It is meant to stay
useful after a break: every non-obvious decision here exists because of the
"small, old phone" target.

## Constraints that shape everything

| Constraint | Consequence |
|------------|-------------|
| Android 7.0 (`minSdk 24`) | No `java.time`, no `java.nio.file`, no streams-heavy code, no `List.of`; everything compiles against API 34 but runs on 24. |
| Minimal memory / install size | Zero dependencies, framework views only, one decoder, vector drawables, no bitmaps in the UI, no reflection. |
| Imprecise fingers | All interactive targets ≥ 48 dp, primary controls 52–68 dp, rows 64 dp, controls spread across the full width. |
| User-chosen folder only | No full-file-system scan; `Scanner` walks one tree and nothing else. |
| Non-Latin file names | NFC normalisation at every boundary, `Collator`-based natural sort, UTF-8 everywhere. |

## Process-wide state

`App` (the `Application`) owns three singletons:

* `Db` — SQLite (`library.db`), two tables.
* `Library` — last scan result, the rotating queue, the path→position map, the
  background scan thread.
* `PlayerEngine` — the single `MediaPlayer` plus audio focus and the noisy
  headset receiver.

Keeping them at application scope means an orientation change (or the activity
being recreated for any reason) does **not** restart playback or trigger a
rescan. There is no retained fragment, no service and no duplicate state.

## UI

One activity, three swipeable pages inside `SwipePager` (a 160-line
`FrameLayout` subclass that only intercepts horizontal drags and ignores
gestures starting on a `SeekBar`, `Switch` or `EditText`):

```
activity_main.xml
  tabs_bar            three equal-weight TextViews + state-list text colour
  pages_container     SwipePager
    page_all          ListView + empty/scan state
    page_folders      breadcrumb bar + ListView + empty state
    page_settings     ScrollView with cards
  player_panel        fixed, outside the pager: times, seek bar,
                      proportional pads + icon-only play, transport row
  permission_card     full-screen scrim, shown only while access is missing
```

The landscape variant (`layout-land/activity_main.xml`) keeps the same ids, so
no Java code changes; it just gives the list more of the screen.

`ui/SeekPad` is a code-built `FrameLayout` (arrow + live value, ripple
background) used twice beside the play button. It maps the touch x to a
0..1 fraction measured from its inner edge and asks `util/SeekCurve` for the
seek amount, so the curve stays Android-free and unit-tested:

| fraction | 0.0 | 0.1 | 0.25 | 0.5 | 0.75 | 1.0 |
|----------|-----|-----|------|-----|------|-----|
| seconds  | 2   | 3   | 6    | 17  | 35   | 60  |

`FolderPickerActivity` is a second, tiny activity: a directory list, a path
field, up/use/cancel. It returns an absolute path in an intent extra and uses no
storage framework APIs.

## Data and persistence

### SQLite (`Db`)

```sql
positions(hash TEXT PRIMARY KEY, path TEXT NOT NULL, position INTEGER,
          duration INTEGER, updated INTEGER)
-- UNIQUE INDEX on path
folder_state(folder TEXT PRIMARY KEY, last_path TEXT NOT NULL, scroll INTEGER)
```

* `positions` is keyed by the **content fingerprint**, so renaming or moving a
  file keeps its position. `path` is stored too so lists can render "resume at
  1:23:45" without opening anything.
* Rows are removed when a file is finished (or when it is deleted, or when the
  same path now holds different content), so the table only holds what is
  actually in progress.
* `folder_state` remembers, per folder, the last played file and the scroll
  offset, which is what makes the folder page reopen where you left it.
* The unified queue order is **not** stored. It is re-derived from the natural
  sort and rotated to the last played file, so one string in `SharedPreferences`
  is enough state.

### SharedPreferences (`Prefs`)

`root_path`, `seek_step_seconds` (1–60, default 10), `delete_without_confirm`,
`keep_screen_on`, `last_file`, `last_mode`, `last_folder`, `last_page`,
`root_history` (newline-separated, newest first, capped at 6 — shown as the
recent-folder list in Settings; newline is safe because paths cannot contain one).

### Fingerprint (`util/Fingerprint`)

SHA-256 over a version tag, the file length, the first 64 KiB and the last
64 KiB. Costs at most 128 KiB of reads and one 64 KiB buffer; enough to tell a
re-encoded or replaced file from a renamed one. Computed lazily when a track is
opened, cached in the `AudioFile` object for the session.

## The rotating unified list (`PlayQueue`)

The list is ordered naturally by file name, across folders. Rotation rules:

* **reset(sorted, anchor)** — rebuild, rotate so `anchor` (last/current file) is first.
* **rotateTo(path)** — a tap on any row brings it to the top, preserving the
  relative order of everything else (`a b c d` + tap `c` → `c d a b`). The
  implementation rotates the *whole* list; rotating only a prefix gives the
  wrong result and used to be a bug.
* **advanceFrom(current)** — finished file goes to the bottom, the new first
  item is returned. End of queue → `null`, and the finished track stays loaded
  so Play restarts it instead of doing nothing.
* **retreatFrom(current)** — "previous file" pulls the last item to the top.

`PlayQueue` is pure Java (no Android imports) and is covered by the JVM tests.

## Scanning (`Scanner`, `Library`)

`Scanner.scan(root, progress)` is a recursive, depth-limited (24) directory
walk on the caller's thread. It:

* skips dot-files and dot-directories,
* accepts a fixed extension set (`mp3 m4a m4b aac ogg oga opus flac wav wave
  mka mp4 3gp amr`, upper/lower case),
* builds `FolderNode`s with recursive file counts,
* sorts files and folders with `NaturalSort`,
* reports progress every 250 files and honours thread interruption.

`Library` runs it on a single low-priority daemon executor, increments a
generation counter so a newer scan discards an older result, and posts the
finished `ScanResult` back to the main thread. All listener callbacks happen on
the main thread.

## Playback (`PlayerEngine`)

One `MediaPlayer`, a small state machine (`IDLE / PREPARING / READY / PLAYING /
PAUSED / ENDED / ERROR`):

* `open(path, startMs, autoPlay)` creates the player, sets audio attributes
  (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`), a partial wake lock, and prepares
  asynchronously. A stale player's callbacks are ignored by comparing the
  `MediaPlayer` instance.
* `play()` during `PREPARING` only records the intent (`playWhenPrepared`);
  `pause()` cancels it. This makes a fast double-tap behave.
* Audio focus: loss → pause and abandon, transient loss → pause and resume on
  gain, "can duck" → volume 0.2. Headphone unplug pauses.
* Completion sets `ENDED`, abandons focus and notifies the activity, which
  advances the queue.

The UI ticks every 500 ms while playing and writes the position to SQLite every
5 ticks plus on pause, stop and destroy.

## Storage permissions (`util/Storage`)

Three levels: `ACCESS_NONE`, `ACCESS_READ` (play, maybe no delete), `ACCESS_FULL`
(list + delete). `accessLevel()` maps the platform state for the running API
level; `requestReadAccess()` asks for the correct runtime permission;
`openAllFilesSettings()` opens the per-app "all files access" screen on
Android 11+; `isAppFolder()` recognises paths that need no permission at all.

The permission card is shown when `Storage.canRead(root)` is false, when a scan
reports the root as unreadable, and its primary button targets the strongest
permission the platform offers. Returning from the system screen without
granting falls back to audio-only read access once, so the app can still play
even when folder listing stays restricted.

## Volume keys (`playback/VolumeKeySeeker`)

Owned by `PlayerEngine` (one per process), constructed with a `Target` that maps
to `position()`, `duration()` and `seekTo()`.

* `setPlaying(boolean)` is called on every playback transition; `refresh()`
  decides whether to activate.
* Active only when **playing && screen off && the setting is on**. The screen
  state comes from a dynamically registered `ACTION_SCREEN_ON/OFF` receiver and
  `PowerManager.isInteractive()`.
* Activation = framework `MediaSession` (created lazily, only when first needed)
  with `setCallback(...)`, `setPlaybackToRemote(VolumeProvider(RELATIVE, …))`
  and `setActive(true)`. The system then routes the volume rocker to
  `onAdjustVolume()` instead of the stream. Deactivation restores stock
  behaviour, including the volume panel.
* **The callback is mandatory.** `MediaSession.postToCallback()` drops every
  command while `mCallback` is null, and `mCallback` only exists after
  `setCallback()`. Without it, Android forwards the key to the session and the
  provider never hears about it — the keys appear completely dead (the 1.2.0
  bug). `tools/check-resources.py` fails if `setPlaybackToRemote` ever appears
  without a `setCallback(callback, …)` in the same file.
* What the platform sends: `MediaSessionLegacyHelper.sendVolumeKeyEvent()`
  forwards `ACTION_DOWN` (and every auto-repeat) as `+1`/`-1`, and `ACTION_UP`
  as `0`.
* `util/VolumeGesture` (pure Java, unit-tested) maps that stream to actions:
  release within `SHORT_PRESS_MS` (450 ms) → `SEEK`; a repeat before the release
  → `VOLUME`; a later release without repeats → `VOLUME` (ROMs that do not
  auto-repeat); `onTimeout()` after `RELEASE_TIMEOUT_MS` (2.5 s) → `VOLUME` (ROMs
  that swallow the release). Seeking goes through
  `SeekCurve.steppedPosition()` with `Prefs.seekStep()`; volume goes through
  `AudioManager.adjustStreamVolume(STREAM_MUSIC, …, FLAG_SHOW_UI)`.
* Every seek republishes the session's `PlaybackState` position so the system's
  copy stays honest.

## Threading summary

| Work | Thread |
|------|--------|
| Everything UI-related, `Db`, `Prefs`, `PlayQueue`, `Library` API | main |
| Recursive directory walk | `library-scan` daemon, result posted to main |
| Media decoding | `MediaPlayer` internal threads |

No locks are needed: the scan thread never touches shared state, it only
produces a `ScanResult` that is applied on the main thread.

## Testing

`tools/tests/LogicTests.java` is a plain `main()` runner (no JUnit) covering:

* `TimeFormat.clock/clamp`,
* `SeekCurve` (clamping, monotonic, quadratic spread, 10 s reachable),
* `VolumeGesture` (tap → seek, repeat/long release/timeout → volume, stray and
  late releases, threshold boundary, reset),
* `NaturalSort` numeric/case/accent ordering, Cyrillic, Greek, Japanese, Korean,
  Hebrew, emoji, total-order and NFC idempotence,
* `AudioFile.displayName`,
* `Fingerprint` (same content under different names, empty files, 300 KiB
  sampled-range semantics, missing files),
* `PlayQueue` (reset/advance/retreat/promote/single/empty/non-head advance),
* `Scanner` (natural order, dotfiles skipped, non-audio excluded, Unicode folder
  names, missing/not-a-directory errors, progress callbacks, a 307-file tree).

`tools/check-resources.py` covers the resource layer statically: missing ids,
strings, drawables and styles, layout includes, custom view tags (class exists,
`(Context, AttributeSet)` constructor, name matches the file) and
`getString(...)` format-argument counts.

Not covered: everything that needs a running Android device — permission
dialogs, `MediaPlayer` behaviour, `ListView` recycling with real content, the
delete dialog, and lifecycle edge cases.
