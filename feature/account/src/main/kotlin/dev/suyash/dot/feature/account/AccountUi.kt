package dev.suyash.dot.feature.account

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.suyash.dot.core.crypto.RecoveryKey
import dev.suyash.dot.core.designsystem.component.DotCard
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.dotmatrix.DotMatrixText
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.sync.AccountState
import kotlinx.coroutines.delay

/** Account & sync card for Settings. */
@Composable
fun AccountCard(viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pendingChanges.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val rotated by viewModel.rotatedKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    DotCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            SectionLabel("Account & sync")
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                AccountState.SignedOut -> {
                    Text("Back up and sync your tasks, end-to-end encrypted.", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Encrypted on this phone before upload — Google, the app's servers and anyone else see only ciphertext.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DotTheme.colors.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { context.findActivity()?.let(viewModel::signIn) }) { Text("Sign in with Google") }
                }
                is AccountState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                }
                is AccountState.Ready -> {
                    Text(s.user.email ?: s.user.displayName ?: "Signed in", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (pending == 0) "End-to-end encrypted · up to date" else "End-to-end encrypted · $pending change(s) waiting to sync",
                        style = MaterialTheme.typography.bodySmall,
                        color = DotTheme.colors.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = viewModel::syncNow) { Text("Sync now") }
                        TextButton(onClick = viewModel::rotateRecoveryKey) { Text("New recovery key") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete account", color = DotTheme.colors.accent) }
                    }
                }
                is AccountState.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::retry) { Text("Try again") }
                }
                is AccountState.NeedsRecoveryKey, is AccountState.ShowRecoveryKey -> {
                    Text("Finish setting up encryption…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = DotTheme.colors.accent)
                LaunchedEffect(it) {
                    delay(6_000)
                    viewModel.dismissMessage()
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your tasks are removed from this phone. The encrypted cloud copy stays, and you can sign back in anytime.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        HideOverlays()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete account?") },
            text = {
                Text(
                    "This permanently deletes your encrypted cloud data, the key backup and your account. Tasks on this phone " +
                        "are removed too. This can't be undone. You'll confirm with your Google account first.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    context.findActivity()?.let(viewModel::deleteAccount)
                }) { Text("Delete everything", color = DotTheme.colors.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    rotated?.let { key ->
        RecoveryKeyDialog(recoveryKey = key, cloudBackup = null, onSaved = viewModel::rotatedKeySaved)
    }
}

/**
 * App-root overlay that takes over when the encryption setup needs the user: showing a brand-new
 * recovery key once, or asking for it on a new phone.
 */
@Composable
fun AccountGate(viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is AccountState.ShowRecoveryKey -> RecoveryKeyDialog(s.recoveryKey, s.cloudBackup, onSaved = viewModel::recoveryKeySaved)
        is AccountState.NeedsRecoveryKey -> EnterRecoveryKeyDialog(
            wrongKey = s.wrongKey,
            email = s.user.email,
            onSubmit = viewModel::submitRecoveryKey,
            onStartOver = viewModel::startOver,
            onSignOut = viewModel::signOut,
        )
        else -> Unit
    }
}

@Composable
private fun RecoveryKeyDialog(recoveryKey: RecoveryKey, cloudBackup: Boolean?, onSaved: () -> Unit) {
    val context = LocalContext.current
    var confirmed by rememberSaveable { mutableStateOf(false) }
    SecureWindow()
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                DotMatrixText("RECOVERY KEY", dotSize = 4.dp)
                Spacer(Modifier.height(20.dp))
                Text("Save this key somewhere safe", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your tasks are encrypted with a key only you hold. " +
                        if (cloudBackup == true) {
                            "It's also backed up end-to-end encrypted with your screen lock, so reinstalling on this phone just works. "
                        } else {
                            ""
                        } +
                        "On a new phone you may need this recovery key. If you lose it and the backup, nobody — not even us — can recover your tasks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DotTheme.colors.muted,
                )
                Spacer(Modifier.height(24.dp))
                DotCard(Modifier.fillMaxWidth()) {
                    Text(
                        recoveryKey.formatted(),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 1.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { copySensitive(context, recoveryKey.formatted()) }) { Text("Copy to clipboard (clears in 60 s)") }
                Text(
                    "Tip: paste it into your password manager, or write it down.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DotTheme.colors.muted,
                )
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("I saved my recovery key", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSaved, enabled = confirmed, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun EnterRecoveryKeyDialog(
    wrongKey: Boolean,
    email: String?,
    onSubmit: (String) -> Unit,
    onStartOver: () -> Unit,
    onSignOut: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    SecureWindow()
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                DotMatrixText("UNLOCK", dotSize = 4.dp)
                Spacer(Modifier.height(20.dp))
                Text("Enter your recovery key", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${email ?: "This account"} has encrypted tasks, but this phone doesn't have the key yet. " +
                        "Type the 26-character recovery key you saved when you first signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DotTheme.colors.muted,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX") },
                    isError = wrongKey,
                    supportingText = { if (wrongKey) Text("That key doesn't unlock this account.") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSubmit(text) }, enabled = text.count { it.isLetterOrDigit() } >= 26, modifier = Modifier.fillMaxWidth()) {
                    Text("Unlock")
                }
                Spacer(Modifier.height(32.dp))
                Text("Lost your key?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your tasks can't be decrypted without it. You can start over with a fresh encrypted account (old cloud data is deleted), or sign out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DotTheme.colors.muted,
                )
                Row {
                    TextButton(onClick = { confirmStartOver = true }) { Text("Start over", color = DotTheme.colors.accent) }
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                }
            }
        }
    }
    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text("Delete the old encrypted data?") },
            text = { Text("Without the recovery key it can never be read again. Tasks on this phone are kept and uploaded to your fresh account.") },
            confirmButton = {
                TextButton(onClick = { confirmStartOver = false; onStartOver() }) {
                    Text("Start over", color = DotTheme.colors.accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmStartOver = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/** Blocks screenshots/screen recording and blanks the Recents preview while a secret is on screen. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Other apps' overlays can't draw over a key being shown or typed (no tapjacking or peeking).
        window?.setHideOverlayWindows(true)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window?.setHideOverlayWindows(false)
        }
    }
}

/** While shown, other apps' overlays are hidden, so they can't trick a tap on a destructive button. */
@Composable
private fun HideOverlays() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.setHideOverlayWindows(true)
        onDispose { activity?.window?.setHideOverlayWindows(false) }
    }
}

private fun copySensitive(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Recovery key", text).apply {
        // Android 13+: keeps the value out of the clipboard preview and keyboard suggestions.
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching { clipboard.clearPrimaryClip() }
    }, 60_000)
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

