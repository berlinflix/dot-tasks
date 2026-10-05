package dev.suyash.dot

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds attest with the App Check debug provider. The first run logs a debug token
 * ("Enter this debug secret into the allow list…"); add it in Firebase console → App Check → Apps →
 * Manage debug tokens before enforcing App Check.
 */
internal object AppCheckSetup {
    fun install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
