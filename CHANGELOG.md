# Changelog

All notable changes to Dot. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [1.0.1] — Unreleased

### Faster
- Startup and first use are compiled ahead of time (a Baseline Profile and an optimized dex layout), including the
  ring screen, voice capture and widgets.
- Heavy work moved off the main thread: sorting and grouping tasks, the natural-language preview while typing,
  alarm scheduling and widget updates after an edit, and opening the encrypted database. The parser compiles its
  patterns once instead of on every keystroke.
- The splash screen stays up until the task list is ready (no blank frame), and unchanged rows skip redrawing.

### Privacy & security
- Other apps can no longer open voice capture (and with it the microphone); "Share to Dot" still works and always
  asks before saving.
- Widgets hide task titles while App lock is on, and privacy settings now apply to widgets immediately.
- The ring screen hides titles again if the phone locks while it is ringing (with "Show titles on the lock screen" off).
- Other apps can't record Dot's audio output; CI actions are pinned to commit SHAs.

## [1.0.0] — 2026-10-06

First public release.

### Tasks
- Lists, subtasks with progress, notes, due dates with optional times, stars.
- Repeating tasks (daily, weekdays, weekly on chosen days, monthly by date or weekday such as "last Friday",
  yearly, every N units, ending on a date or after N times); completing one schedules the next, with undo.
- Today, Upcoming and Starred views across lists; per-list sorting (my order with drag to reorder, date,
  recently starred); search; move between lists; duplicate; share; delete all completed.
- Natural-language input for typing and voice, including repeats ("gym every Monday 7am").

### Reminders
- Reminders that ring like a call with a full-screen screen over the lock screen, or a normal notification;
  never ring in Silent or Do Not Disturb.
- Snooze presets, a custom time, auto-snooze or "missed" when unanswered; works after reboot and time-zone changes.

### Capture
- Round and pill voice widgets, a tasks widget, launcher shortcuts, a Quick Settings tile, and "Share to Dot".
- On-device speech recognition only.

### Privacy & security
- Optional Google sign-in with end-to-end encrypted sync (Tink XChaCha20-Poly1305; keys in Keystore, Block Store
  and a recovery key).
- SQLCipher database, no backups, no trackers; optional app lock; overlay protection on sensitive screens.
- Content-free deletion markers that expire after 30 days; account deletion in the app and on the web; JSON export.

[1.0.1]: https://github.com/berlinflix/dot-tasks/compare/v1.0.0...main
[1.0.0]: https://github.com/berlinflix/dot-tasks/releases/tag/v1.0.0
