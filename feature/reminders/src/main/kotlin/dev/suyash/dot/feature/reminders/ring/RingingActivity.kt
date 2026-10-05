package dev.suyash.dot.feature.reminders.ring

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.feature.reminders.alarm.ReminderIntents
import dev.suyash.dot.feature.reminders.notify.ReminderNotifier
import java.time.ZonedDateTime

/**
 * Full-screen, call-style ring screen shown over the lock screen (via the notification's full-screen
 * intent). Before the first unlock after a reboot it runs in "generic" mode without touching the
 * encrypted database.
 */
@AndroidEntryPoint
class RingingActivity : ComponentActivity() {

    private val unlocked: Boolean get() = getSystemService(UserManager::class.java).isUserUnlocked
    private val viewModel: RingingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()

        val generic = !unlocked || ReminderIntents.idsFrom(intent).isEmpty()
        setContent {
            DotTheme(darkTheme = true) {
                if (generic) {
                    RingingScreen(
                        tasks = emptyList(),
                        now = { ZonedDateTime.now() },
                        showTitles = false,
                        genericMode = true,
                        dayParts = DayParts(),
                        ringSeconds = 60,
                        onSnooze = {},
                        onDone = {},
                        onDismissGeneric = {
                            ReminderNotifier(applicationContext).cancelRinging()
                            finish()
                        },
                        onTimeout = ::finish,
                    )
                } else {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state.finished) { if (state.finished) finish() }
                    val keyguardLocked = remember { getSystemService(KeyguardManager::class.java).isKeyguardLocked }
                    RingingScreen(
                        tasks = state.tasks,
                        now = viewModel::now,
                        showTitles = state.settings.showTitlesOnLockScreen || !keyguardLocked,
                        genericMode = false,
                        dayParts = state.settings.dayParts,
                        ringSeconds = state.settings.ringDurationSeconds,
                        onSnooze = viewModel::snooze,
                        onDone = viewModel::done,
                        onDismissGeneric = {},
                        onTimeout = ::finish,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A newer batch started ringing while this screen was open: show the newest reminders.
        setIntent(intent)
        recreate()
    }

    /** Volume keys silence the ring (like an incoming call) without dismissing the reminder. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (unlocked && ReminderIntents.idsFrom(intent).isNotEmpty()) {
                viewModel.silence()
            } else {
                ReminderNotifier(applicationContext).cancelRinging()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
