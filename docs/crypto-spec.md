# Dot — end-to-end encryption specification (v1)

Everything the app stores in the cloud is encrypted on the phone first. The server (Firestore) stores
only ciphertext plus the minimum metadata needed to sync. This document is the contract: **any change
to these formats must add a new version, never alter an existing one**, so old data stays readable.

## Keys

| Key | Size | Where it lives | Purpose |
|---|---|---|---|
| Recovery key (RK) | 128-bit random | Only with the user (shown once, 26 Crockford-Base32 chars) | Unwraps the account key on a new phone |
| Account key (MK) | 256-bit random | Phone (Keystore-wrapped) · Block Store (E2EE Google backup) · server *wrapped by RK* | Encrypts the data keyset |
| Data keyset | Tink XChaCha20-Poly1305, rotatable | Server and phone, *encrypted under MK* | Encrypts every record |
| Database key | 256-bit random | Phone only, Keystore-wrapped | SQLCipher encryption of the local database |

Android Keystore keys are AES-256-GCM, non-exportable, StrongBox-backed when the device has a secure
element, and usable after first unlock without user presence (so reminders work while locked).

## Keyring document — `users/{uid}/meta/keyring`

```
v              int     incremented on every change (rules enforce +1)
wrappedMk      bytes   AES-256-GCM( key = HKDF-SHA256(ikm = RK, salt = uid, info = "dot.rk-wrap.v1", L = 32),
                                    plaintext = MK,
                                    aad = LP("dot.keyring.v1") ‖ LP(uid) )      →  nonce(12) ‖ ct ‖ tag(16)
kcv            bytes   HMAC-SHA256(MK, "dot.kcv.v1")[0..16]  — identifies the right MK without decrypting data
encDataKeyset  bytes   Tink encrypted keyset, keyset-encryption AEAD = AES-256-GCM(MK),
                       associated data = LP("dot.keyset.v1") ‖ LP(uid)
createdAt / updatedAt  server timestamps
```

`LP(x)` = 4-byte big-endian length ‖ x. Every associated-data field is length-prefixed so different
splits of the same bytes can never collide.

## Record document — `users/{uid}/records/{rid}`

```
rid        hex(SHA-256(kind ":" localId))[0..32]   — opaque: hides ids and whether it's a task or a list
v          int     optimistic-concurrency version (create = 1, every update = +1; enforced by rules)
ct         bytes   Tink AEAD (XChaCha20-Poly1305) of the padded frame below
updatedAt  server timestamp (the pull cursor; clients cannot set it)
del        bool    deletion hint for garbage collection only — the authoritative flag is inside ct
exp        timestamp, deletion markers only (required when del == true, forbidden otherwise):
           when the marker may be erased. Rules require 7–400 days after the write; clients use 30.
```

Associated data for `ct`: `LP("dot.rec.v1") ‖ LP(uid) ‖ LP(rid) ‖ LP(int64 v)`.
Binding the uid, slot and version means the server cannot move a ciphertext to another record or
user, and cannot replay an old version as if it were current (rollback); any such attempt fails
authentication and the record is skipped.

Plaintext frame: `format(1 byte = 1) ‖ length(4, BE) ‖ payload ‖ zero padding to a multiple of 256 bytes`
(padding hides the exact length of titles and notes).

`payload` is protobuf (`RecordPayload` in `core/sync`): kind (1 task / 2 list), the task or list
fields, the deleted flag, and per-field-group hybrid logical clocks used for conflict-free merging.
Unknown kinds/fields are ignored so newer app versions can extend the format. Task field 19 (added
in 1.0) is the repeat rule as an RFC 5545 `RRULE` subset (`FREQ`, `INTERVAL`, `BYDAY` incl. `2TU`/`-1FR`,
`BYMONTHDAY`, `UNTIL`, `COUNT` = occurrences left); rules the app can't honour exactly are ignored.

A deleted task or list is written as a **content-free marker**: title, notes, dates, reminder,
star and repeat are erased in the same write that sets the deleted flag, so the ciphertext of a
deleted record holds nothing but its id, placement and clocks.

## Restore paths

1. **Same phone:** MK from the Keystore-wrapped local file.
2. **Reinstall / new phone with backup:** MK from Block Store (backed up end-to-end encrypted with the
   screen-lock knowledge factor; only when `isEndToEndEncryptionAvailable()`), verified against `kcv`.
3. **Anything else:** the user types RK → unwrap MK → verify `kcv`.

Lost RK + no Block Store copy = the data cannot be decrypted by anyone. "Start over" deletes the old
ciphertext and creates a fresh account.

## Merge

Each record carries one HLC per field group (title, notes, status, starred, due, reminder, placement,
deleted, repeat). Merging takes the newer value per group (LWW-map CRDT): commutative, associative and
idempotent, so all devices converge regardless of order. Property-tested in `LwwMergeTest`.

Completing a repeating task creates the next occurrence with a **deterministic id**
(`UUIDv3("next:" + id)`), so two devices completing the same occurrence offline create one task,
not two.

## Retention

- Deletion markers expire 30 days after they are written (`exp`). A device erases expired markers
  in the cloud during its weekly full sync (each re-checked in a transaction, so a restored record is
  never touched); a Firestore TTL policy on `records.exp` does the same server-side when the project
  is on the Blaze plan. Devices forget local markers after 30 days too.
- Every 7 days (≤ the shortest marker lifetime the rules allow) each device downloads the full
  record set and drops records it had synced that no longer exist in the cloud — so a device that
  was offline longer than a marker's lifetime still learns about deletions. A push that finds its
  record gone from the cloud drops it locally instead of re-creating it.
- Deleting the account deletes all records and the keyring immediately (in the app, or on the web
  page), plus the Block Store copy (in the app).

## Tests

- `core/crypto`: `RecordCipherTest` (tamper, slot/version/user swap, padding, wrong key),
  `AccountCryptoTest` (recovery key wrap/unwrap, uid binding, kcv, rotation, keyset round trip).
- `core/sync`: `RecordCodecTest` (payload round trips, opaque ids).
- `core/domain`: `RepeatRuleTest` (RRULE parsing/round trips, next occurrences), `LwwMergeTest`.
- `core/data` (on device): `TaskCommandsTest` (content-free deletion, repeat successors and undo,
  marker purge, dropping records gone from the cloud).
- `firebase/rules-test`: server-side enforcement (owner-only, Google-only, version +1, field whitelist,
  size limits, server timestamps, marker expiry window).
