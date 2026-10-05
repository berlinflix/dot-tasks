# Changelog

All notable changes to Dot. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

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

[1.0.0]: https://github.com/berlinflix/dot-tasks/releases/tag/v1.0.0
