# Security policy

Dot is built so that nobody but you can read your tasks. If you find a way around that, please tell us.

## Reporting a vulnerability

Please **don't open a public issue**. Instead, report privately:

- GitHub: **Security → Report a vulnerability** on this repository (private advisory), or
- email **suyashsinghdtg@gmail.com** with "SECURITY" in the subject.

Include what you found, how to reproduce it, and the impact you expect. You'll get an acknowledgement within
7 days. Please give us a reasonable time to ship a fix before disclosing publicly. We're happy to credit you.

## Scope

- The Android app in this repository (latest release).
- The end-to-end encryption design and implementation ([crypto spec](docs/crypto-spec.md)).
- The Firestore security rules (`firebase/firestore.rules`) and the web pages in `firebase/hosting`.

Out of scope: attacks that need a rooted or otherwise compromised device, the metadata the threat model already
lists as visible to the server, and denial of service. See the [threat model](docs/threat-model.md).

## Supported versions

Only the latest release gets security fixes.
