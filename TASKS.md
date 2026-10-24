# Tasks — next-step pointer

> Updated as work progresses. See [PROGRESS.md](PROGRESS.md) for milestones and
> verified build/test results.

## v1.3.0 — done, needs a device check

- [x] **Held volume key moves the volume twice as fast** (two stream steps per
      repeat while capturing the keys); with the setting off or the screen on,
      the keys keep the stock single step.
- [x] **Landscape**: the three tab headers moved to the top of the right column,
      directly above the transport panel; the list now uses the full left column.
- [x] **Seek band**: the time labels and the slider are one touch target
      (`ui/SeekZone`) — a tap above the bar seeks to the same position, without
      spending extra height — and the gap to the pad row grew from 10 dp to
      15 dp to stop mis-taps. Vertical drags still scroll the landscape panel.
- [x] APK artifact renamed to **`AudiobooksMinimalPlayer-1.3.0.apk`**.
- [x] The version line in Settings is a **link to the GitHub project**
      (`ACTION_VIEW`; no new permission, no network code in the app).
- [x] Tests 448 assertions, 0 failed; versionCode 5.

## v1.2.1 — done, needs a device retest

- [x] Fix "the volume keys do nothing at all" with the screen off: the
      remote-volume `MediaSession` now installs a `MediaSession.Callback`
      (Android drops every adjustment routed to a session without one), keeps
      the published playback position current, and falls back to a volume step
      when there is no duration to seek in yet.
- [x] Gesture logic extracted to `util/VolumeGesture` and driven by the events
      Android really sends (`+1`/`-1` for the press and each repeat, `0` on
      release): quick release seeks, a repeat or a late release changes the
      music volume, and a swallowed release still gives one volume step after
      2.5 s. Unit-tested, so a tap can never be mistaken for a hold.
- [x] `check-resources.py` fails if `setPlaybackToRemote` ever appears without
      `setCallback(...)`, since that combination silently kills the keys.
- [x] Tests 448 assertions, 0 failed; `AudiobooksMinimalPlayer-1.3.0.apk`
      (versionCode 5) built and verified.
- [ ] **Confirm on the Android 7 phone**: toggle on, screen off, playing →
      short press seeks by the step, hold changes the media volume; toggle off →
      stock keys and the system volume panel.

## v1.2 — done

- [x] Left (back) pad mirrored: minimum next to the play button, maximum at the
      outer edge, arrow pointing left. Direction declared in the layout and
      guarded by `check-resources.py`.
- [x] Optional **volume keys seek while the screen is off** (Settings toggle);
      holding a key still changes the volume, screen-on behaviour unchanged.

## v1.1 — done

- [x] Transport panel: times `played · total · remaining`, no title/status, no
      extra progress bar, no percentage.
- [x] Proportional seek pads (2–60 s by distance from the middle) around an
      icon-only play button; `prev / −N s / +N s / next` row underneath.
- [x] Settings: rescan at the top; access block above the folder chooser; green
      card outline instead of "access granted" text.
- [x] Default `Books` folder plus one-tap Music / Books / Downloads / SD card,
      and a recent-folder list with delete buttons (no "Reset" button any more).

## v1 — done

- [x] Build the app with **no dependencies**, minSdk 24, targetSdk 34.
- [x] Unified natural-sorted list with a rotating queue (playing file on top,
      finished file to the bottom).
- [x] Folder explorer with per-folder last-file/scroll memory.
- [x] Tap-to-play, per-row delete with yes/no dialog, "delete without confirmation" setting.
- [x] Per-file positions in SQLite keyed by a content fingerprint, pruned when finished.
- [x] Rough progress bar, precise tap-to-seek bar, ±step buttons (1–60 s), long-press 60 s.
- [x] Previous / Play-Pause / Next transport, audio focus, headphone-unplug handling.
- [x] Root folder settings + built-in directory picker, app-folder fallback.
- [x] Unicode-safe sorting, display and hashing (NFC + Collator + UTF-8).
- [x] JVM logic tests (145 assertions) and static resource/format checks.
- [x] Debug-signed, zipaligned APK in `artifacts/` + README/architecture docs.

## Next: on-device QA (needs a phone or emulator; none in this sandbox)

1. Install `artifacts/AudiobooksMinimalPlayer-1.3.0.apk` on **Android 7** (the
   minimum) and on **Android 13/14**, then walk through:
   - first launch → permission card → grant → scan;
   - decline the permission and use the app folder;
   - choose a folder with sub-folders, non-Latin names, and `Chapter 2` / `Chapter 10`;
   - kill the app mid-chapter and reopen: position restored, list rotated to that file;
   - finish a chapter: it moves to the bottom, the next one starts, the saved row is deleted;
   - open a folder, play a file, leave, come back: the folder opens at that file;
   - delete a file with and without the confirmation setting; decline a delete;
   - seek by tapping the bar, by tapping a time label above the bar, by ±step and
     by long-press; change the step in settings;
   - rotate to landscape: tabs above the transport panel, list full height;
   - tap the version line in Settings: the browser opens the GitHub project;
   - press the pads near the middle (≈2–6 s) and near the outer edge (≈40–60 s),
     and drag inside a pad;
   - use Music / Books / Downloads (and SD card if present) quick picks, and the
     recent-folder list: tap to switch, trash to remove;
   - on both pads: nearest-to-button is the smallest seek, outer edge the
     largest, and the back pad moves backwards;
   - enable "Volume keys seek (screen off)", start playback, turn the screen off:
     short press should seek by the configured step, a long press should change
     the volume, and with the screen on the keys must behave normally.
2. Check the log for `MediaPlayer` errors, and that background playback survives a
   screen-off period on the target device.

## Backlog (post-v1, deliberately out of scope now)

- [ ] Foreground playback service + media notification (survives process kill).
      Would also make the volume-key capture reliable in the background.
- [ ] Bluetooth/media-button handling via `MediaSession` (framework only is possible
      with `MediaSession`, but it is more code than v1 needs).
- [ ] Sleep timer.
- [ ] Rewind-on-resume setting (e.g. rewind 5 s when reopening a book).
- [ ] Optional SAF mode for devices where "all files access" is unavailable.
- [ ] Localisation of the UI strings (all text is already in `strings.xml`).
- [ ] Instrumented UI tests (needs an emulator).
