// Security-rules tests against the Firestore emulator.
// Run: firebase emulators:exec --only firestore --project demo-dot "npm --prefix rules-test test"
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  Bytes,
  deleteDoc,
  doc,
  getDoc,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
} from 'firebase/firestore';

const PROJECT = 'demo-dot';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const RID = 'b0a1c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d';

let env;

const google = { firebase: { sign_in_provider: 'google.com' } };
const ct = (n = 64) => Bytes.fromUint8Array(new Uint8Array(n).map((_, i) => i % 251));

function record(v, extra = {}) {
  return { v, ct: ct(), updatedAt: serverTimestamp(), del: false, ...extra };
}

function keyring(v, extra = {}) {
  return {
    v,
    wrappedMk: ct(60),
    kcv: ct(16),
    encDataKeyset: ct(200),
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...extra,
  };
}

const db = (uid, token = google) => (uid ? env.authenticatedContext(uid, token) : env.unauthenticatedContext()).firestore();
const recordRef = (firestore, uid = ALICE, rid = RID) => doc(firestore, `users/${uid}/records/${rid}`);
const keyringRef = (firestore, uid = ALICE) => doc(firestore, `users/${uid}/meta/keyring`);

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

describe('records', () => {
  test('owner can create v=1 and read it back', async () => {
    await assertSucceeds(setDoc(recordRef(db(ALICE)), record(1)));
    await assertSucceeds(getDoc(recordRef(db(ALICE))));
  });

  test('signed-out users can do nothing', async () => {
    await assertFails(setDoc(recordRef(db(null)), record(1)));
    await assertFails(getDoc(recordRef(db(null))));
  });

  test('other users cannot read or write', async () => {
    await setDoc(recordRef(db(ALICE)), record(1));
    await assertFails(getDoc(recordRef(db(BOB))));
    await assertFails(setDoc(recordRef(db(BOB), ALICE), record(1)));
    await assertFails(deleteDoc(recordRef(db(BOB), ALICE)));
  });

  test('only Google sign-in is accepted', async () => {
    await assertFails(setDoc(recordRef(db(ALICE, { firebase: { sign_in_provider: 'password' } })), record(1)));
    await assertFails(setDoc(recordRef(db(ALICE, { firebase: { sign_in_provider: 'anonymous' } })), record(1)));
  });

  test('versions must start at 1 and go up by exactly 1', async () => {
    await assertFails(setDoc(recordRef(db(ALICE)), record(2)));
    await assertSucceeds(setDoc(recordRef(db(ALICE)), record(1)));
    await assertFails(setDoc(recordRef(db(ALICE)), record(1)));
    await assertFails(setDoc(recordRef(db(ALICE)), record(3)));
    await assertSucceeds(setDoc(recordRef(db(ALICE)), record(2)));
    await assertSucceeds(updateDoc(recordRef(db(ALICE)), { v: 3, ct: ct(), updatedAt: serverTimestamp(), del: true }));
  });

  test('timestamps must come from the server', async () => {
    await assertFails(setDoc(recordRef(db(ALICE)), record(1, { updatedAt: Timestamp.fromMillis(0) })));
  });

  test('no extra or missing fields, ct must be bytes within 64 KiB', async () => {
    await assertFails(setDoc(recordRef(db(ALICE)), record(1, { title: 'plaintext leak' })));
    await assertFails(setDoc(recordRef(db(ALICE)), { v: 1, ct: ct(), updatedAt: serverTimestamp() }));
    await assertFails(setDoc(recordRef(db(ALICE)), record(1, { ct: 'not-bytes' })));
    await assertFails(setDoc(recordRef(db(ALICE)), record(1, { ct: ct(0) })));
    await assertFails(setDoc(recordRef(db(ALICE)), record(1, { ct: ct(65537) })));
    await assertSucceeds(setDoc(recordRef(db(ALICE)), record(1, { ct: ct(65536) })));
  });

  test('record ids must be sane', async () => {
    await assertFails(setDoc(recordRef(db(ALICE), ALICE, 'x'), record(1)));
    await assertFails(setDoc(recordRef(db(ALICE), ALICE, 'y'.repeat(65)), record(1)));
  });

  test('owner can delete', async () => {
    await setDoc(recordRef(db(ALICE)), record(1));
    await assertSucceeds(deleteDoc(recordRef(db(ALICE))));
  });
});

describe('keyring', () => {
  test('owner can create v=1 and read it', async () => {
    await assertSucceeds(setDoc(keyringRef(db(ALICE)), keyring(1)));
    await assertSucceeds(getDoc(keyringRef(db(ALICE))));
  });

  test('others cannot read the keyring', async () => {
    await setDoc(keyringRef(db(ALICE)), keyring(1));
    await assertFails(getDoc(keyringRef(db(BOB), ALICE)));
  });

  test('kcv must be exactly 16 bytes; no extra fields', async () => {
    await assertFails(setDoc(keyringRef(db(ALICE)), keyring(1, { kcv: ct(15) })));
    await assertFails(setDoc(keyringRef(db(ALICE)), keyring(1, { masterKey: ct(32) })));
  });

  test('updates must bump the version', async () => {
    await setDoc(keyringRef(db(ALICE)), keyring(1));
    await assertFails(setDoc(keyringRef(db(ALICE)), keyring(1)));
    await assertSucceeds(setDoc(keyringRef(db(ALICE)), keyring(2)));
  });
});

describe('everything else', () => {
  test('is denied', async () => {
    await assertFails(setDoc(doc(db(ALICE), `users/${ALICE}`), { name: 'x' }));
    await assertFails(setDoc(doc(db(ALICE), 'public/x'), { a: 1 }));
    await assertFails(getDoc(doc(db(ALICE), `users/${ALICE}/other/x`)));
  });
});
