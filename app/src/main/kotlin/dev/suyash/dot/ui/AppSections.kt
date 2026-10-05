package dev.suyash.dot.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.suyash.dot.core.designsystem.component.DotCard
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.sync.AccountState
import dev.suyash.dot.feature.account.AccountViewModel
import dev.suyash.dot.feature.reminders.ReminderPermissions
import dev.suyash.dot.feature.widget.WidgetPinning
import kotlinx.coroutines.launch

/** A gentle, dismissible nudge on the home screen while not signed in. */
@Composable
fun SyncHintBanner(onOpenSettings: () -> Unit, viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (state != AccountState.SignedOut || dismissed) return
    DotCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            Text("Back up your tasks", style = MaterialTheme.typography.titleSmall)
            Text(
                "Sign in with Google to sync — end-to-end encrypted, so only you can read them.",
                style = MaterialTheme.typography.bodySmall,
                color = DotTheme.colors.muted,
            )
            Row {
                TextButton(onClick = onOpenSettings) { Text("Set up", color = DotTheme.colors.accent) }
                TextButton(onClick = { dismissed = true }) { Text("Not now") }
            }
        }
    }
}

/** "Add to home screen" for the voice widget and the tasks widget. */
@Composable
fun WidgetsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supported = remember(context) { WidgetPinning.isSupported(context) }
    DotCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            Text("Widgets", style = MaterialTheme.typography.titleSmall)
            Text(
                if (supported) {
                    "Add a one-tap voice button (round, slim pill, or both) or your upcoming tasks to the home screen."
                } else {
                    "Long-press your home screen → Widgets → Dot."
                },
                style = MaterialTheme.typography.bodySmall,
                color = DotTheme.colors.muted,
            )
            if (supported) {
                Row {
                    TextButton(onClick = { scope.launch { WidgetPinning.pinMic(context) } }) {
                        Text("Round voice", color = DotTheme.colors.accent)
                    }
                    TextButton(onClick = { scope.launch { WidgetPinning.pinMicPill(context) } }) {
                        Text("Pill voice", color = DotTheme.colors.accent)
                    }
                    TextButton(onClick = { scope.launch { WidgetPinning.pinTasks(context) } }) {
                        Text("Tasks")
                    }
                }
            }
        }
    }
}

internal object RingtoneSettings {
    fun open(context: Context) {
        val intent = ReminderPermissions(context.applicationContext).ringChannelSettings()
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
