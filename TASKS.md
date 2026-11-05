# Tasks — next-step pointer

> Updated as work progresses. See [PROGRESS.md](PROGRESS.md) for milestones and
> verified build/test results.

## v1.6.0 — built, needs a device check

- [x] All-files folder names are delimiters before contiguous folder groups,
      with a closing rule after the last file, instead of subtitles on every row.
- [x] Smooth and discrete file rows share the same fixed 64 dp height; discrete
      folder delimiters use that height too, so names/subtitles are not clipped.
- [x] Discrete browsing applies each newly crossed row while the finger drags,
      handles reversals as deltas, and does not repeat those rows on release.
- [x] Normal and fast play buttons have equal 88×68 dp frames; fast speed is
      inline, and only the button owning the playing speed is accent-filled and
      shows the pause icon. Inactive/paused buttons stay dark and outlined.
- [x] Tests 560 assertions, 0 failed; resource check and full app Java
      compilation passed; versionCode 8; `AudiobooksMinimalPlayer-1.6.0.apk`
      built, aligned and signed (v2 + v3).
- [ ] Confirm on a device: delimiters/closing rules, equal row sizing, live drag
      updates, all three play-button states, and active-speed highlighting.

## v1.5.0 — built, needs a device check

- [x] **Settings → Discrete file-list scrolling** (checkbox, off by default),
      persisted in SharedPreferences.
- [x] The All-files page uses fixed-height single-line rows in this mode; vertical
      swipes jump by whole rows without pixel scrolling or flings, and the
      viewport leaves no partial row visible.
- [x] Swipe up advances through the circular list; swipe down goes backward,
      prepending the last row at the top and wrapping at the other end. Browsing
      does not change playback; selecting/advancing a track re-anchors it at row
      zero.
- [x] Folder rows no longer show a redundant chevron; they have a separate
      delete target. Confirmed folder deletion removes its contents, while the
      parent-navigation row remains protected. Delete icons are inset farther
      from the right edge.
- [x] Tests 525 assertions, 0 failed; versionCode 7;
      `AudiobooksMinimalPlayer-1.5.0.apk` built and signed.
- [ ] **Confirm on a device**: whole rows only, no fling, short/long swipes move
      expected row counts, down at the top reveals the prior track, deletes and
      horizontal page swipes still work, and next-track playback resets the
      playing file to the top.

## v1.4.0 — done, needs a device check

- [x] **Fast play button** right of the play button: double-triangle glyph, the
      chosen speed shown underneath. Press rules are identical for both buttons:
      paused → play at that button's speed; same button while playing → pause;
      other button while playing → switch speed, keep playing. The active
      button shows the pause glyph.
- [x] **Settings → Fast play speed**: slider x1.1 … x2.0 in x0.1 steps, default
      **x1.3**. The speed is kept for the next chapter and applied through
      `setPlaybackParams()` only while playing.
- [x] Tests 519 assertions, 0 failed; versionCode 6;
      `AudiobooksMinimalPlayer-1.4.0.apk` built and signed.
- [ ] **Confirm on the Android 7 phone**: fast button starts at x1.3, pressing it
      again pauses, pressing plain play switches to x1 without a gap, and the
      speed survives "next chapter".

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

1. Install `artifacts/AudiobooksMinimalPlayer-1.6.0.apk` on **Android 7** (the
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
   - press fast play while paused (starts at x1.3, no immediate pause), press it
     again (pauses), press plain play while playing (switches to x1, keeps
     playing), and change the speed in Settings while listening;
   - enable discrete file-list scrolling: check that only complete rows are
     shown, each swipe snaps without a fling, swiping down at the top brings in
     the preceding track, and next-track playback puts the playing track at top;
     also verify row delete taps and horizontal page swipes still work;
   - in Folders, confirm the chevron is gone, the separate trash target opens a
     confirmation for the folder and its contents, cancel leaves it untouched,
     and confirm removes it and refreshes the list;
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
