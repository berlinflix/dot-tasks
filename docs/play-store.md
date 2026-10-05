# Publishing Dot on Google Play

Everything the Play Console asks for, with the exact answers for this app. Store texts and graphics
are in [`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US) (fastlane `supply`
layout, so they can also be uploaded with `fastlane supply`).

## 0. One-time setup

- A Play Console developer account. New **personal** accounts must run a closed test with at least
  **12 testers for 14 days in a row** before production access is granted.
- Keep the upload key safe: `%USERPROFILE%\.dot-release\dot-upload.p12` plus its password in
  `keystore.properties` (git-ignored). Back both up (e.g. in a password manager). If the upload key is
  ever lost, Play App Signing lets you request an upload-key reset.
- Recommended before a public launch: move Firebase to the **Blaze** plan with a budget alert (the free
  Spark quota of 50k reads / 20k writes per day is shared by all users), then turn on the TTL policy:
  `gcloud firestore fields ttls update exp --collection-group=records --enable-ttl --project=dot-tasks-pys6y`.

## 1. Create the app

Play Console → **Create app**: name `Dot: Private Tasks & Reminders`, default language English (United
States), **App**, **Free**. Accept the declarations.

## 2. Main store listing

| Field | Value |
|---|---|
| App name | `title.txt` |
| Short description | `short_description.txt` |
| Full description | `full_description.txt` |
| App icon | `images/icon.png` (512×512) |
| Feature graphic | `images/featureGraphic.png` (1024×500) |
| Phone screenshots | `images/phoneScreenshots/*.png` (1080×1920) |
| Category | Productivity |
| Email | suyashsinghdtg@gmail.com |
| Website | https://dot-tasks-pys6y.web.app |
| Privacy policy | https://dot-tasks-pys6y.web.app/privacy |

## 3. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://dot-tasks-pys6y.web.app/privacy |
| App access | **All functionality is available without special access.** Sign-in is optional and works with any Google account. |
| Ads | **No**, the app contains no ads. |
| Content rating | Category *Utility, Productivity, Communication or Other*. Answer **No** to every content question; users can't interact with or share content with other users in the app. Expected: Everyone / PEGI 3. |
| Target audience | **18 and over** (simplest). Adding 13–17 is fine too; don't include under-13 groups (that brings in the Families policy). "Appeals to children?" **No**. |
| News app | No |
| COVID-19 | Not a contact-tracing or status app |
| Data safety | See below |
| Government app | No |
| Financial features | None |
| Health | No health features |
| Advertising ID | **No**. The app doesn't use it (the permission isn't in the manifest). |
| Full-screen intent | **Yes, core functionality: alarm.** Text: *"Dot is a reminders app. Reminders the user sets to 'ring like a call' show a full-screen ringing screen over the lock screen at the time the user chose, so the user can snooze or complete the task. Ringing reminders are the app's core feature."* |
| Exact alarms | Nothing to declare: the app uses `SCHEDULE_EXACT_ALARM` (user-grantable), not `USE_EXACT_ALARM`. |
| Foreground services | None used. |

### Data safety

- *Does your app collect or share any of the required user data types?* **Yes**
- *Is all of the user data collected by your app encrypted in transit?* **Yes**
- *Do you provide a way for users to request that their data is deleted?* **Yes**: in the app
  (Settings → Account & sync → Delete account) and on the web at https://dot-tasks-pys6y.web.app/delete

Data types, all **collected, not shared**, only when the user signs in, so **optional**:

| Data type | Purposes |
|---|---|
| Personal info → Name | Account management |
| Personal info → Email address | Account management |
| Personal info → User IDs | Account management, App functionality |

Not declared, and why:

- **Tasks and lists**: end-to-end encrypted before upload, with keys the developer never has. Google's
  Data safety rules exempt data "transmitted using end-to-end encryption". If you'd rather over-disclose,
  add *App activity → Other user-generated content* (collected, App functionality, optional), which is
  always allowed.
- **Audio**: speech is recognised on the device and never leaves it.
- **Crash logs / diagnostics**: no crash-reporting or analytics SDK (Play's own vitals are Google's, not the app's).

Security practices: data encrypted in transit **Yes**; users can request deletion **Yes**.

Firebase's own per-SDK disclosures: https://firebase.google.com/docs/android/play-data-disclosure

## 4. Release

1. Build the bundle: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
   (signed with the upload key from `keystore.properties`).
2. **Testing → Closed testing**: create a track, add ≥ 12 testers (email list or Google Group),
   upload the AAB, add release notes from `changelogs/<versionCode>.txt`, roll out. Keep it running for
   14 days, then apply for production.
3. Accept **Play App Signing** on the first upload. Then go to **Test and release → App integrity** and copy
   the *App signing key certificate* SHA-1 and SHA-256 into Firebase. Without this, Google sign-in
   fails for installs from Play:
   ```bash
   firebase apps:android:sha:create 1:458044538223:android:4caf4c5c815f27e5117c9a <SHA1> --project dot-tasks-pys6y
   firebase apps:android:sha:create 1:458044538223:android:4caf4c5c815f27e5117c9a <SHA256> --project dot-tasks-pys6y
   ```
4. **Production**: promote the tested release (or upload a newer `versionCode`).

## 5. Hardening once the app is on Play

- **App Check**: Firebase console → App Check → Android app → Play Integrity (uses the app signing
  SHA-256); in Play Console link the Cloud project under *App integrity → Play Integrity API*. Watch
  the metrics for a week, then **Enforce** for Cloud Firestore. Before enforcing, also register the web
  app with reCAPTCHA Enterprise, or the web deletion page stops working.
- **API key restrictions** (Cloud console → APIs & Services → Credentials):
  - *Android key*: Android apps → `dev.suyash.dot` with the SHA-1 of the upload key and the Play app
    signing key (and the debug key for development). API restrictions: Identity Toolkit, Token Service,
    Cloud Firestore, Firebase App Check.
  - *Browser key*: HTTP referrers `https://dot-tasks-pys6y.web.app/*` and `https://dot-tasks-pys6y.firebaseapp.com/*`.
- **Android developer verification** for the GitHub APK: from September 2026 (Brazil, Indonesia,
  Singapore, Thailand; everywhere from 2027) sideloaded apps must come from verified developers.
  Register the package name and the upload key in the Android Developer Console.
