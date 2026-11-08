# Architecture

This document explains how the app is put together and why. It is meant to stay
useful after a break: every non-obvious decision here exists because of the
"small, old phone" target.

## Constraints that shape everything

| Constraint | Consequence |
|------------|-------------|
| Android 7.0 (`minSdk 24`) | No `java.time`, no `java.nio.file`, no streams-heavy code, no `List.of`; everything compiles against API 34 but runs on 24. |
| Minimal memory / install size | Zero dependencies, framework views only, one decoder, vector drawables, no bitmaps in the UI, no reflection. |
| Imprecise fingers | All interactive targets ≥ 48 dp, primary controls 52–68 dp, rows at least 60 dp, controls spread across the full width. |
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
    page_all          DiscreteListView + empty/scan state
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
storage framework APIs. Its rows still show the navigation chevron. In the
Folder explorer, `FolderAdapter` hides that redundant chevron and adds a separate
trash target to subfolder rows (never the `..` row); confirming deletion removes
the folder subtree and its saved positions. Recursive deletion preflights the
whole tree, rejects symlinks/aliases, and verifies the target is below—not equal
to—the chosen library root.

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
`keep_screen_on`, `volume_keys_seek`, `discrete_file_scroll` (default off),
`last_file`, `last_mode`, `last_folder`, `last_page`, `root_history`
(newline-separated, newest first, capped at 6 — shown as the recent-folder list
in Settings; newline is safe because paths cannot contain one).

### Fingerprint (`util/Fingerprint`)

SHA-256 over a version tag, the file length, the first 64 KiB and the last
64 KiB. Costs at most 128 KiB of reads and one 64 KiB buffer; enough to tell a
re-encoded or replaced file from a renamed one. Computed lazily when a track is
opened, cached in the `AudioFile` object for the session.

## The rotating unified list (`PlayQueue`)

The list is the library's folder tree, and it is a ring: the same order is
rotated so the file in progress is reachable at the top. Rotation rules:

* **reset(root, anchor)** — rebuild the rows from the tree and rotate so
  `anchor` (last/current file) is the first file.
* **rotateTo(path)** — a tap on any row brings it to the top, preserving the
  relative order of everything else (`a b c d` + tap `c` → `c d a b`). The
  implementation rotates the *whole* list; rotating only a prefix gives the
  wrong result and used to be a bug.
* **advanceFrom(current)** — finished file goes to the bottom, the new first
  file is returned. End of queue → `null`, and the finished track stays loaded
  so Play restarts it instead of doing nothing.
* **retreatFrom(current)** — "previous file" steps back one file, wrapping to
  the last one.
* **rotateViewBackward() / rotateViewForward()** — discrete list swipes rotate
  one row in either direction without changing the playing file. At the top, a
  backward swipe prepends the last row; at the bottom, a forward swipe moves the
  head to the tail. A playback selection/advance re-anchors the current track at
  the top again.

Because the rows include the separators, all of these move files and folder
lines together — there is no second grouping pass that could disagree with the
scan.

### The list is the tree

The queue is **one ordered list of rows**, not a list of files with delimiters
computed around them. `model/QueueEntry` is one row — an audio file, a folder's
opening row (icon + name) or its closing row (a bare rule, no name) — and
`model/TreeOrder` produces them by walking the scanned tree depth first:

```
Dune                     <- opening row, depth 0
    Part 1               <- opening row, depth 1 (indented 14 dp per level)
    Chapter 01           <- Part 1's own files
    Chapter 02
    -------------------  <- closing row of Part 1 (a line, no name)
Prologue                 <- Dune's own files come after its sub-folders
-----------------------  <- closing row of Dune
```

Sub-folders come before a folder's own files, matching the Folders page; the
library root is not named; folders that contain no audio anywhere are skipped,
so a folder whose last file was deleted does not leave a pair of lines around
nothing. `ScanResult.allFiles` is this order with the separators removed, so the
play sequence and the visible order are the same thing.

`PlayQueue` rotates *that* list: `Collections.rotate` over files **and**
separators, never a re-sort. Whole rows move together, and the activity re-reads
the live list (`FileListRows` → `AudioFileAdapter`) instead of grouping rows on
every refresh. Because a ring has no start, a folder may straddle the wrap point
— closing lines are kept before parent/root files and at wrap, but omitted
when another separator already marks the boundary.

Rotation anchors the playing file: it is always the first *file* row. When it is
the first file of its folder, the rows naming that folder (and its parents) lead
it, as the flat list always did; otherwise the file itself is the head and the
folder's closing line sits right below it. The activity scrolls to
`AudioFileAdapter.firstFileRow()`, not to row 0.

The separators are ordinary rows of the queue, so ListView recycles them through
the same view type, `DiscreteListView`'s whole-row browsing moves them like any
other row, and a separator is simply not clickable (`isEnabled()` is false).

Folder separators are `layout/item_folder_delimiter.xml`: the icon and the name
are hidden for a closing row, which leaves only the full-width rule, and the
adapter indents the whole row by `depth × folder_indent` (14 dp from
`dimens.xml`) so nesting is visible at a glance.

`ui/DiscreteListView` owns vertical swipe detection only while the setting is
on. It bypasses ListView's pixel drag/fling and adjusts the bottom padding to
an integer number of rows. A file row (`layout/item_audio.xml`) is a
`FrameLayout` that wraps its content with a 60 dp minimum: names can use two
lines and the size / saved-position line stays fully visible. Compact
8 dp text padding and 1 dp row margins keep the gaps between files small.

In discrete mode `AudioFileAdapter.discreteRowHeightPx()` measures a one-line
file row at the current font size, floors it at `row_min_height`, and gives that
one exact height to every file row (folder delimiters stay at 24 dp) (names go back to one
line). This replaces a fixed `64 sp` constant, which grew with the font scale
and made discrete gaps much larger than the smooth list's. `MainActivity`
passes the measured height to `DiscreteListView.setDiscreteRowHeight()`, which
uses it for both the bottom gutter and the finger's row step — a measured child
height could otherwise leave a half row visible. `util/DiscreteDrag` emits only
newly crossed row deltas while the finger moves (including reversals), and
release applies no delta twice. A short swipe still moves one row. Horizontal
swipes belong to `SwipePager`, and taps on the row/delete button stay normal.
The activity applies the row steps and queue rotations on the main thread.

`PlayQueue`, `QueueEntry`, `TreeOrder`, `FileListRows` and `DiscreteDrag` are
pure Java (no Android imports) and are covered by the JVM tests.

## Scanning (`Scanner`, `Library`)

`Scanner.scan(root, progress)` is a recursive, depth-limited (24) directory
walk on the caller's thread. It:

* skips dot-files and dot-directories,
* accepts a fixed extension set (`mp3 m4a m4b aac ogg oga opus flac wav wave
  mka mp4 3gp amr`, upper/lower case),
* builds `FolderNode`s with recursive file counts,
* sorts files and folders with `NaturalSort`,
* flattens the sorted tree into the unified order with `TreeOrder` (a folder's
  sub-folders first, then its own files — see above),
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
  `AudioManager.adjustStreamVolume(STREAM_MUSIC, …, FLAG_SHOW_UI)`, twice per
  event so a held key ramps the volume at double the stock rate.
* Every seek republishes the session's `PlaybackState` position so the system's
  copy stays honest.

## Seek band (`ui/SeekZone`)

`player_panel.xml` puts the three time labels and the `SeekBar` inside a
`SeekZone` (a `LinearLayout`). The bar alone is a 44 dp target; the labels above
it used to be dead space. The zone maps any touch that misses the bar to the same
`x` on the bar's track, using the bar's own `paddingStart`/`paddingEnd` as
insets, so tapping above a point seeks exactly where tapping the bar would.
`SeekCurve.fractionForX()`/`progressForX()` do the maths and are unit-tested.

Touch routing:

| Gesture | Owner |
|---------|-------|
| Press starts on the bar (including the thumb) | `SeekBar` — native drag, latched for the whole gesture |
| Press starts on a time label | `SeekZone` — tap seeks on release, horizontal drag scrubs |
| Vertical drag from a label | enclosing `ScrollView` (landscape panel), the zone stops at the slop threshold |

The row is followed by a 15 dp gap (`@dimen/seek_gap`, 1.5× the normal 10 dp) so
an overshooting thumb lands on nothing instead of the seek pads.

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
* `SeekCurve` (clamping, monotonic, quadratic spread, 10 s reachable, and the
  row→slider mapping `fractionForX`/`progressForX` used by `SeekZone`),
* `VolumeGesture` (tap → seek, repeat/long release/timeout → volume, stray and
  late releases, threshold boundary, reset),
* `NaturalSort` numeric/case/accent ordering, Cyrillic, Greek, Japanese, Korean,
  Hebrew, emoji, total-order and NFC idempotence,
* `AudioFile.displayName`,
* `Fingerprint` (same content under different names, empty files, 300 KiB
  sampled-range semantics, missing files),
* `PlayQueue` (reset/advance/retreat/promote, discrete view rotations,
  single/empty/non-head advance),
* `Scanner` (natural order, dotfiles skipped, non-audio excluded, Unicode folder
  names, missing/not-a-directory errors, progress callbacks, a 307-file tree).

`tools/check-resources.py` covers the resource layer statically: missing ids,
strings, drawables and styles, layout includes, custom view tags (class exists,
`(Context, AttributeSet)` constructor, name matches the file) and
`getString(...)` format-argument counts.

Not covered: everything that needs a running Android device — permission
dialogs, `MediaPlayer` behaviour, `ListView` recycling with real content, the
delete dialog, and lifecycle edge cases.
