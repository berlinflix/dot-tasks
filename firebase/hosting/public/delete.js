// Account deletion without the app (Google Play requires a web option).
// Deletes the user's encrypted records, the key backup record and the Firebase account itself.
'use strict';

(() => {
  const auth = firebase.auth();
  const db = firebase.firestore();
  const $ = (id) => document.getElementById(id);
  const status = (text) => { $('status').textContent = text; };

  auth.onAuthStateChanged((user) => {
    if (!user) return;
    $('who').textContent = 'Signed in as ' + (user.email || 'your Google account') + '.';
    $('signin').hidden = true;
    $('confirm').hidden = false;
  });

  $('signin').addEventListener('click', async () => {
    try {
      await auth.signInWithPopup(new firebase.auth.GoogleAuthProvider());
    } catch (e) {
      status('Sign-in was cancelled or failed.');
    }
  });

  $('sure').addEventListener('change', (e) => { $('delete').disabled = !e.target.checked; });

  $('delete').addEventListener('click', async () => {
    const user = auth.currentUser;
    if (!user) return;
    $('delete').disabled = true;
    try {
      status('Deleting encrypted tasks…');
      const records = db.collection('users').doc(user.uid).collection('records');
      for (;;) {
        const page = await records.limit(400).get();
        if (page.empty) break;
        const batch = db.batch();
        page.docs.forEach((d) => batch.delete(d.ref));
        await batch.commit();
      }
      status('Deleting key backup…');
      await db.collection('users').doc(user.uid).collection('meta').doc('keyring').delete();
      status('Deleting account…');
      try {
        await user.delete();
      } catch (e) {
        if (e && e.code === 'auth/requires-recent-login') {
          await user.reauthenticateWithPopup(new firebase.auth.GoogleAuthProvider());
          await user.delete();
        } else {
          throw e;
        }
      }
      status('Done. Your account and all cloud data have been deleted.');
      $('confirm').hidden = true;
    } catch (e) {
      status('Something went wrong; nothing more was deleted. Please try again.');
      $('delete').disabled = false;
    }
  });
})();
