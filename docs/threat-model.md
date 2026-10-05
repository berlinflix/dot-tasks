# Dot — threat model

## What we protect

Task titles, notes, lists, due dates and reminder times; the voice input (ephemeral); the keys.

## Defended

| Threat | Mitigation |
|---|---|
| Cloud breach, insider at the provider, subpoena of stored data | End-to-end encryption: the server only holds ciphertext ([crypto-spec](crypto-spec.md)). |
| Network attacker / malicious Wi-Fi / user-installed CA | HTTPS only, system CAs only (network security config), plus content is E2EE. |
| Google account takeover | Attacker gets ciphertext + a keyring wrapped by the recovery key. Block Store backups need the victim's screen lock. |
| Server tampering (swap, replay, rollback, forged deletes) | AEAD associated data binds uid + record id + version; deletion lives inside the ciphertext. |
| Another user reading or writing your data | Firestore rules: owner-only, Google sign-in only, App Check. |
| Other apps on the phone | Sandbox; nothing exported except the launcher, widget providers and protected system broadcasts; explicit + immutable PendingIntents; `intentMatchingFlags="enforceIntentFilter"`; intents carry ids only. |
| Lost or stolen locked phone | File-based encryption + SQLCipher database with a Keystore-bound key; lock-screen title hiding option; notifications use a generic public version. |
| Backups or device-to-device copies leaking data | `allowBackup=false` and data-extraction rules exclude everything; keys are Keystore-bound and useless elsewhere. |
| Shoulder-surfing / screen capture of secrets | Recovery-key screens use `FLAG_SECURE`; clipboard copies are marked sensitive and cleared after 60 s; Recents previews are blanked by default. |
| Data leaking into logs | Release builds strip `android.util.Log` (R8); no task text is ever logged. |
| Voice data | On-device speech recognition only; no cloud fallback; audio is never written to disk. |
| Trackers / third-party data flows | No analytics, ads, crash-reporting or FCM SDKs. |

## Not defended (out of scope)

- A rooted or otherwise compromised OS on the user's own unlocked phone.
- The keyboard app seeing what the user types.
- Metadata visible to the server: the number of records, when they change, and approximate sizes
  (rounded up to 256-byte buckets).
- The server withholding data (it can deny service; it cannot read or silently alter it).

## Assumptions

- Google's Block Store end-to-end encrypted backup works as documented (lock-screen knowledge factor,
  hardware-enforced guess limits).
- The Android Keystore / StrongBox implementation on the device is sound.
