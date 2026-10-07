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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.ui.DotApp
import dev.suyash.dot.ui.LockScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var appLock: AppLock

    /** A task to open, delivered by a notification tap. */
    private val openTaskId = MutableStateFlow<String?>(null)

    /** False until settings have loaded: the splash stays up instead of an empty first frame. */
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !contentReady }
        enableEdgeToEdge()
        if (savedInstanceState == null) openTaskId.value = intent.taskIdExtra()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    settingsRepository.settings.map { it.hideInRecents || it.appLock }.distinctUntilChanged().collect { hide ->
                        setRecentsScreenshotEnabled(!hide)
                    }
                }
            }
        }

        val appLockEnabled = settingsRepository.settings.map { it.appLock }.distinctUntilChanged()
        setContent {
            DotTheme {
                val lockEnabled by appLockEnabled.collectAsStateWithLifecycle(initialValue = null)
                val locked by appLock.locked.collectAsStateWithLifecycle()
                var lockMessage by remember { mutableStateOf<String?>(null) }
                if (lockEnabled != null) SideEffect { contentReady = true }
                when (lockEnabled) {
                    null -> Unit // settings not loaded yet: show nothing rather than flash the tasks
                    true -> if (locked) {
                        LockScreen(message = lockMessage, onUnlock = { appLock.unlock(this) { lockMessage = it.toString() } })
                    } else {
                        DotApp(openTaskId = openTaskId, onOpenTaskHandled = { openTaskId.value = null })
                    }
                    false -> {
                        LaunchedEffect(Unit) { appLock.notNeeded() }
                        DotApp(openTaskId = openTaskId, onOpenTaskHandled = { openTaskId.value = null })
                    }
                }
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
