# Contributing to Dot

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

## Before you start

- For anything bigger than a small fix, please open an issue first so we can agree on the approach.
- Security problems: see [SECURITY.md](SECURITY.md). Please don't file them as public issues.

## Development setup

1. Android Studio Panda 4 (2025.3.4) or newer, with its bundled JDK 21.
2. A `google-services.json` in `app/` from your own Firebase project (see the README), or skip it: everything
   except sign-in and sync works without Firebase.
3. `./gradlew assembleDebug`

## Before sending a pull request

```bash
./gradlew :core:domain:test testDebugUnitTest lintDebug
./gradlew :core:data:connectedDebugAndroidTest     # if you touched the database or TaskCommands
```

- Every write goes through `TaskCommands` (one transaction plus an outbox row); please keep it that way.
- Don't log task content. Don't add analytics, ads or tracking libraries.
- Changes to the encrypted payload must be additive: new protobuf field numbers only, and update
  [`docs/crypto-spec.md`](docs/crypto-spec.md).
- Changes to `firebase/firestore.rules` need matching tests in `firebase/rules-test`.
- Match the surrounding code style (Kotlin official style, small focused commits, KDoc on public behaviour).

## License

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
