# Dot

A private, Google-Tasks-style to-do app for Android with a Nothing-OS-style design:

- **Snooze anything**: in 10 min, in 1 hour, later today, this evening, tomorrow, this weekend, next weekend, next week, or a custom time. Every option shows the exact time it resolves to.
- **Voice widget**: a round Nothing-style home-screen mic. Tap it, say *"remind me to buy milk tomorrow at 8 am"*, and the task and reminder are saved. Speech is recognised **on the device only**, never in the cloud.
- **Rings like a call**: a looping ringtone, vibration and a full-screen call screen over the lock screen. It **never rings when the phone is on silent or in Do Not Disturb** (you get a quiet notification instead). Snooze from the ring screen or the notification.
- **End-to-end encrypted sync** with Sign in with Google. Tasks survive uninstall and reinstall. The cloud only ever holds ciphertext.

## Architecture

```
Widgets (Glance) · Voice capture · Screens · Notification actions
                         │
                TaskCommands  ── single write path: Room transaction + outbox row
                         │      after commit → alarms · widgets · sync (TaskChangeObserver set)
        Room + SQLCipher (the only place text exists in readable form)
                         │
     SyncEngine ── RecordCipher (Tink XChaCha20-Poly1305) ── keys: Keystore · Block Store · recovery key
                         │ TLS, ciphertext only
     Firebase Auth (Google) · Firestore users/{uid}/… (strict rules) · App Check (Play Integrity)
```

| Module | What's inside |
|---|---|
| `core/domain` (pure Kotlin) | models, `SnoozeCalculator`, `RingPolicy`, voice/typing parser, HLC + LWW merge, fractional indexing |
| `core/crypto` | Keystore wrapping, SQLCipher key, account keys, recovery key, record AEAD, Block Store |
| `core/data` | Room (SQLCipher), DAOs, `TaskCommands`, `SyncStore`, settings |
| `core/auth` | Credential Manager → Firebase Auth |
| `core/sync` | Firestore store, sync engine, WorkManager scheduling, live sync, account session |
| `core/designsystem`, `core/ui` | Dot theme, dot-matrix renderer, icons, snooze sheet, pickers |
| `feature/tasks` | lists, tasks, quick add with natural-language parsing, editor, snooze |
| `feature/reminders` | single-alarm scheduler, receivers, ring/quiet/missed notifications, call screen |
| `feature/voice` | on-device speech capture overlay |
| `feature/widget` | mic widget and tasks widget (Glance) |
| `feature/account`, `feature/settings` | sign-in, recovery key, delete account; preferences |
| `firebase/` | Firestore rules + rules tests, indexes, Hosting pages (privacy, account deletion) |

Docs: [crypto spec](docs/crypto-spec.md) · [threat model](docs/threat-model.md).

## Build

Requirements: Android Studio Panda 4 (2025.3.4) or newer (AGP 9.2), JDK 21 (Studio's bundled JBR).

```bash
# Windows (PowerShell): $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleDebug
./gradlew testDebugUnitTest :core:domain:test
```

`app/google-services.json` is not committed. Regenerate it with:

```bash
firebase apps:sdkconfig ANDROID 1:458044538223:android:4caf4c5c815f27e5117c9a --project dot-tasks-pys6y -o app/google-services.json
```

## Firebase project `dot-tasks-pys6y`

- Firestore (Native, `asia-south1`, delete protection on). Rules and index overrides: `cd firebase && firebase deploy --only firestore`.
- Authentication: Google provider enabled. Debug SHA-1/SHA-256 are registered. Add the release/upload and Play App Signing fingerprints before shipping (`firebase apps:android:sha:create`).
- App Check: the client is wired up (debug provider in debug builds, Play Integrity in release). Register the debug token printed in logcat, link Play Integrity once the app exists in Play Console, then turn on enforcement for Firestore.
- Rules tests: `cd firebase && npm --prefix rules-test install && firebase emulators:exec --only firestore --project demo-dot "npm --prefix rules-test test"`.

## Release checklist (Google Play)

- [ ] Release keystore + Play App Signing; add both SHA-256s to Firebase; restrict the Android API key (package + SHA) in Cloud Console.
- [ ] App Check: Play Integrity linked, enforcement on.
- [ ] Hosting: replace `CONTACT_EMAIL_PLACEHOLDER` in `firebase/hosting/public/privacy.html`, then `firebase deploy --only hosting`.
- [ ] Play Console: Data safety form, full-screen intent declaration (alarm-style reminders), account deletion URL (`/delete.html`), privacy policy URL.
- [ ] Move Firebase to the Blaze plan with budget alerts before a public launch (Spark quota is shared by all users).
- [ ] Closed test: 12 testers for 14 days (new personal developer accounts), then production.
