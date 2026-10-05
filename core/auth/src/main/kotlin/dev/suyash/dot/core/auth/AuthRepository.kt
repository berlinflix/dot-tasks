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
import com.google.firebase.auth.FirebaseAuthInvalidUserException
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
        val idToken = when (val token = requestGoogleIdToken(activity)) {
            is IdToken.Received -> token.value
            IdToken.Cancelled -> return SignInResult.Cancelled
            IdToken.NoAccount -> return SignInResult.NoGoogleAccount
            is IdToken.Failed -> return SignInResult.Failed(token.message)
        }
        return try {
            val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            val user = result.user ?: return SignInResult.Failed("Sign-in returned no user.")
            SignInResult.Success(user.toAccountUser())
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed: ${e.javaClass.simpleName}")
            SignInResult.Failed("Sign-in failed. Check your connection and try again.")
        }
    }

    /**
     * Confirms it's still the account owner (Google account sheet) right before something
     * irreversible. Fails if the user cancels or picks a different account.
     */
    suspend fun reauthenticate(activity: Activity): Boolean {
        val user = auth.currentUser ?: return false
        val idToken = (requestGoogleIdToken(activity) as? IdToken.Received)?.value ?: return false
        return try {
            user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Re-authentication failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Deletes the Firebase account itself (call [reauthenticate] first: Firebase needs a recent sign-in). */
    suspend fun deleteUser(): Boolean {
        val user = auth.currentUser ?: return true
        return try {
            user.delete().await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Account deletion failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** False if the account was deleted or disabled elsewhere (e.g. on the web deletion page). */
    suspend fun accountStillExists(): Boolean {
        val user = auth.currentUser ?: return false
        return try {
            user.reload().await()
            true
        } catch (_: FirebaseAuthInvalidUserException) {
            false
        } catch (_: Exception) {
            true // offline or transient: assume it still exists
        }
    }

    private sealed interface IdToken {
        data class Received(val value: String) : IdToken
        data object Cancelled : IdToken
        data object NoAccount : IdToken
        data class Failed(val message: String) : IdToken
    }

    /** Previously-used accounts silently first, then the full "Sign in with Google" sheet. */
    private suspend fun requestGoogleIdToken(activity: Activity): IdToken {
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
                return IdToken.Cancelled
            } catch (_: NoCredentialException) {
                return IdToken.NoAccount
            } catch (e: GetCredentialException) {
                Log.w(TAG, "Sign-in failed: ${e.type}")
                return IdToken.Failed("Couldn't open Google sign-in.")
            }
        } catch (_: GetCredentialCancellationException) {
            return IdToken.Cancelled
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Sign-in failed: ${e.type}")
            return IdToken.Failed("Couldn't open Google sign-in.")
        }

        val credential = response.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return IdToken.Failed("Unexpected credential type.")
        }
        return try {
            IdToken.Received(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable Google credential: ${e.javaClass.simpleName}")
            IdToken.Failed("Sign-in failed. Try again.")
        }
    }

    suspend fun signOut() {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        auth.signOut()
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
