package dev.suyash.dot

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.suyash.dot.core.data.settings.SettingsRepository
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.ui.DotApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** A task to open, delivered by a notification tap. */
    private val openTaskId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openTaskId.value = intent.taskIdExtra()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    settingsRepository.settings.map { it.hideInRecents }.distinctUntilChanged().collect { hide ->
                        setRecentsScreenshotEnabled(!hide)
                    }
                }
            }
        }

        setContent {
            DotTheme {
                DotApp(openTaskId = openTaskId, onOpenTaskHandled = { openTaskId.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.taskIdExtra()?.let { openTaskId.value = it }
    }

    private fun Intent.taskIdExtra(): String? =
        getStringExtra(EXTRA_OPEN_TASK_ID)?.takeIf { it.isNotBlank() && it.length <= 64 }

    private companion object {
        /** Must match ReminderIntents.EXTRA_OPEN_TASK_ID. */
        const val EXTRA_OPEN_TASK_ID = "dev.suyash.dot.extra.OPEN_TASK_ID"
    }
}
