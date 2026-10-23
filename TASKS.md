# Tasks — next-step pointer

> Updated as work progresses. See [PROGRESS.md](PROGRESS.md) for milestones and
> verified build/test results.

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

1. Install `artifacts/MinimalAudiobookPlayer-1.1.0.apk` on **Android 7** (the
   minimum) and on **Android 13/14**, then walk through:
   - first launch → permission card → grant → scan;
   - decline the permission and use the app folder;
   - choose a folder with sub-folders, non-Latin names, and `Chapter 2` / `Chapter 10`;
   - kill the app mid-chapter and reopen: position restored, list rotated to that file;
   - finish a chapter: it moves to the bottom, the next one starts, the saved row is deleted;
   - open a folder, play a file, leave, come back: the folder opens at that file;
   - delete a file with and without the confirmation setting; decline a delete;
   - seek by tapping the bar, by ±step and by long-press; change the step in settings;
   - press the pads near the middle (≈2–6 s) and near the outer edge (≈40–60 s),
     and drag inside a pad;
   - use Music / Books / Downloads (and SD card if present) quick picks, and the
     recent-folder list: tap to switch, trash to remove.
2. Check the log for `MediaPlayer` errors, and that background playback survives a
   screen-off period on the target device.

## Backlog (post-v1, deliberately out of scope now)

- [ ] Foreground playback service + media notification (survives process kill).
- [ ] Bluetooth/media-button handling via `MediaSession` (framework only is possible
      with `MediaSession`, but it is more code than v1 needs).
- [ ] Sleep timer.
- [ ] Rewind-on-resume setting (e.g. rewind 5 s when reopening a book).
- [ ] Optional SAF mode for devices where "all files access" is unavailable.
- [ ] Localisation of the UI strings (all text is already in `strings.xml`).
- [ ] Instrumented UI tests (needs an emulator).
