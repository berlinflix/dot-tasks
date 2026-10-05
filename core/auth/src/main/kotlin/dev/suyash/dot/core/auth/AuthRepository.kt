package dev.suyash.dot.core.auth

import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** OAuth "Web client" id (server client id) from google-services.json, provided by the app module. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebClientId

data class AccountUser(val uid: String, val email: String?, val displayName: String?)

sealed interface SignInResult {
    data class Success(val user: AccountUser) : SignInResult
    data object Cancelled : SignInResult
    data object NoGoogleAccount : SignInResult
    data class Failed(val message: String) : SignInResult
}

/**
 * Sign in with Google through Credential Manager, then Firebase Auth. Only the default
 * OpenID scopes (id, email, name) are requested — no access to Gmail, Drive or anything else.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @WebClientId private val webClientId: String,
) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val random = SecureRandom()

    val user: Flow<AccountUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAccountUser()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    fun current(): AccountUser? = auth.currentUser?.toAccountUser()

    /**
     * Shows the Google account sheet. [activity] hosts the system UI.
     * Tries previously-used accounts silently first, then the full "Sign in with Google" flow.
     */
    suspend fun signIn(activity: Activity): SignInResult {
        val manager = CredentialManager.create(activity)
        val nonce = newNonce()
        val returning = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .setNonce(nonce)
            .build()
        val button = GetSignInWithGoogleOption.Builder(webClientId).setNonce(nonce).build()

        val response = try {
            manager.getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(returning).build())
        } catch (_: NoCredentialException) {
            try {
                manager.getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(button).build())
            } catch (_: GetCredentialCancellationException) {
                return SignInResult.Cancelled
            } catch (_: NoCredentialException) {
                return SignInResult.NoGoogleAccount
            } catch (e: GetCredentialException) {
                Log.w(TAG, "Sign-in failed: ${e.type}")
                return SignInResult.Failed("Couldn't open Google sign-in.")
            }
        } catch (_: GetCredentialCancellationException) {
            return SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Sign-in failed: ${e.type}")
            return SignInResult.Failed("Couldn't open Google sign-in.")
        }

        val credential = response.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return SignInResult.Failed("Unexpected credential type.")
        }
        return try {
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            val user = result.user ?: return SignInResult.Failed("Sign-in returned no user.")
            SignInResult.Success(user.toAccountUser())
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed: ${e.javaClass.simpleName}")
            SignInResult.Failed("Sign-in failed. Check your connection and try again.")
        }
    }

    suspend fun signOut() {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        auth.signOut()
    }

    /**
     * Deletes the Firebase account. Firebase requires a recent sign-in; if it's too old we
     * re-authenticate through the Google sheet and retry once.
     */
    suspend fun deleteAccount(activity: Activity): Boolean {
        val user = auth.currentUser ?: return true
        return try {
            user.delete().await()
            true
        } catch (_: FirebaseAuthRecentLoginRequiredException) {
            when (signIn(activity)) {
                is SignInResult.Success -> runCatching { auth.currentUser?.delete()?.await() }.isSuccess
                else -> false
            }
        }
    }

    private fun newNonce(): String {
        val raw = ByteArray(32).also(random::nextBytes)
        val hashed = MessageDigest.getInstance("SHA-256").digest(raw)
        return Base64.encodeToString(hashed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun FirebaseUser.toAccountUser() = AccountUser(uid = uid, email = email, displayName = displayName)

    private companion object {
        const val TAG = "Auth"
    }
}
