package dev.suyash.dot.feature.reminders.ring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.ui.DateTimePickerDialog
import dev.suyash.dot.core.ui.SnoozeSheetContent
import androidx.activity.viewModels

/** "Later…" from a reminder notification: a snooze sheet floating over whatever is on screen. */
@AndroidEntryPoint
class SnoozePickerActivity : ComponentActivity() {

    private val viewModel: RingingViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DotTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showPicker by rememberSaveable { mutableStateOf(false) }
                val now = remember { viewModel.now() }
                if (showPicker) {
                    DateTimePickerDialog(
                        initial = now.plusHours(1),
                        onDismiss = ::finish,
                        onConfirm = { viewModel.snooze(it); finish() },
                    )
                } else {
                    ModalBottomSheet(onDismissRequest = ::finish) {
                        SnoozeSheetContent(
                            now = now,
                            dayParts = state.settings.dayParts,
                            onPick = { viewModel.snooze(it); finish() },
                            onCustom = { showPicker = true },
                        )
                    }
                }
            }
        }
    }
}
