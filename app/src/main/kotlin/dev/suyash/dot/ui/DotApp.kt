package dev.suyash.dot.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.suyash.dot.BuildConfig
import dev.suyash.dot.feature.account.AccountCard
import dev.suyash.dot.feature.account.AccountGate
import dev.suyash.dot.feature.settings.SettingsRoute
import dev.suyash.dot.feature.tasks.TasksRoute
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
private data object TasksDestination

@Serializable
private data object SettingsDestination

@Composable
fun DotApp(openTaskId: StateFlow<String?>, onOpenTaskHandled: () -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val taskToOpen by openTaskId.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavHost(navController = navController, startDestination = TasksDestination) {
            composable<TasksDestination> {
                TasksRoute(
                    onOpenVoice = { VoiceLauncher.launch(context) },
                    onOpenSettings = { navController.navigate(SettingsDestination) },
                    openTaskId = taskToOpen,
                    onOpenTaskHandled = onOpenTaskHandled,
                    banner = {
                        Column {
                            ReminderSetupBanner()
                            SyncHintBanner(onOpenSettings = { navController.navigate(SettingsDestination) })
                        }
                    },
                )
            }
            composable<SettingsDestination> {
                SettingsRoute(
                    onBack = { navController.popBackStack() },
                    remindersStatus = { ReminderSetupBanner(showWhenReady = true) },
                    accountSection = { AccountCard() },
                    widgetsSection = { WidgetsSection() },
                    onOpenRingtoneSettings = { RingtoneSettings.open(context) },
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }
        }
        // Takes over the screen when encryption setup needs the user (new recovery key / unlock).
        AccountGate()
    }
}

/** Opens voice capture (the same screen the home-screen widget opens). */
internal object VoiceLauncher {
    fun launch(context: android.content.Context) {
        try {
            context.startActivity(dev.suyash.dot.feature.voice.VoiceCaptureActivity.intent(context))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Voice capture isn't available", Toast.LENGTH_SHORT).show()
        }
    }
}
