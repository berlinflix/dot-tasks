package dev.suyash.dot

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional app lock (Settings → App lock). The task screens stay hidden until the fingerprint, face or
 * screen lock is confirmed: at every cold start, and after the app has been in the background for
 * [RELOCK_AFTER_MS]. Reminders keep ringing while locked; they follow the lock-screen privacy setting.
 */
@Singleton
class AppLock @Inject constructor() : DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt ?: return
        if (SystemClock.elapsedRealtime() - since >= RELOCK_AFTER_MS) _locked.value = true
    }

    /** With the lock off there's nothing to unlock, so turning it on later doesn't lock the app on the spot. */
    fun notNeeded() {
        _locked.value = false
    }

    /** Shows the system prompt (biometrics, or the PIN/pattern/password as fallback). */
    fun unlock(activity: Activity, onError: (CharSequence) -> Unit = {}) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock Dot")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _locked.value = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString)
                }
            },
        )
    }

    private companion object {
        const val RELOCK_AFTER_MS = 30_000L
    }
}
