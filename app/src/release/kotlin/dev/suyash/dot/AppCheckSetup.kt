package dev.suyash.dot

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds prove they are the genuine app on a genuine device via Play Integrity. */
internal object AppCheckSetup {
    fun install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
