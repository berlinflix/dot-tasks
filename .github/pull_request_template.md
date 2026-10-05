## What and why

<!-- What does this change, and why? Link the issue if there is one. -->

## Checklist

- [ ] `./gradlew :core:domain:test testDebugUnitTest lintDebug` passes
- [ ] On-device tests run if the database or `TaskCommands` changed
- [ ] No task content is logged; no new tracking or analytics
- [ ] Payload or rules changes are additive and documented (`docs/crypto-spec.md`, `firebase/rules-test`)
