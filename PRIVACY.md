# Privacy

The full, authoritative privacy policy is published at
**https://dot-tasks-pys6y.web.app/privacy** (source: [`firebase/hosting/public/privacy.html`](firebase/hosting/public/privacy.html)).

In short:

- **No account needed.** Without signing in, your tasks never leave your phone.
- **End-to-end encrypted sync.** If you sign in with Google, tasks are encrypted on your phone before upload
  (see the [encryption spec](docs/crypto-spec.md)). The server stores ciphertext that nobody else can read,
  including the developer and Google.
- **What the server knows:** your Google account ID, email and name (for sign-in), and metadata about your
  encrypted records (how many, roughly how large, when they change, and which are deletion markers).
- **Voice stays on the phone.** Speech is recognised on the device; audio is never recorded or uploaded.
- **No ads, analytics, trackers or crash-reporting SDKs.**
- **Retention:** your data stays until you delete it. Deleted tasks are erased at once; their empty, encrypted
  deletion markers are removed after 30 days.
- **Deletion:** delete your account and all cloud data in the app (Settings → Account & sync → Delete account)
  or at https://dot-tasks-pys6y.web.app/delete. Export your tasks any time (Settings → Your data).

Questions: suyashsinghdtg@gmail.com
