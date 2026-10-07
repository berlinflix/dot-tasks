package dev.suyash.dot.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.suyash.dot.core.designsystem.component.DotCard
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.feature.reminders.ReminderPermissions
import dev.suyash.dot.feature.reminders.ReminderReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells the user exactly what reminders still need, with one-tap fixes. Re-checks every time the app
 * comes back to the foreground (the user may have just toggled a setting).
 */
@Composable
fun ReminderSetupBanner(showWhenReady: Boolean = false) {
    val context = LocalContext.current
    val permissions = remember(context) { ReminderPermissions(context.applicationContext) }
    // A check is several system calls, so it runs off the main thread; nothing shows until the first one.
    var checked by remember { mutableStateOf<ReminderReadiness?>(null) }
    val scope = rememberCoroutineScope()
    val refresh = { scope.launch { checked = withContext(Dispatchers.Default) { permissions.readiness() } } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        if (!granted) context.safeStart(permissions.appNotificationSettings())
    }

    val readiness = checked ?: return
    if (readiness.allGood && !showWhenReady) return

    DotCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (readiness.allGood) MaterialTheme.colorScheme.primary else DotTheme.colors.accent, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (readiness.allGood) "Reminders are ready to ring" else "Reminders need your OK",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (!readiness.allGood) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "So they can ring on time — even when the phone is locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DotTheme.colors.muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            SetupItem("Notifications", readiness.notifications) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            SetupItem("Alarms & reminders (on-time ringing)", readiness.exactAlarms) {
                context.safeStart(permissions.exactAlarmSettings())
            }
            SetupItem("Full-screen ring screen", readiness.fullScreen) {
                context.safeStart(permissions.fullScreenSettings())
            }
            SetupItem("Ringing channel", readiness.ringChannelOn) {
                context.safeStart(permissions.ringChannelSettings())
            }
        }
    }
}

@Composable
private fun SetupItem(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            (if (ok) "✓  " else "•  ") + label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) DotTheme.colors.muted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (!ok) TextButton(onClick = onFix) { Text("Allow", color = DotTheme.colors.accent) }
    }
}

private fun Context.safeStart(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
