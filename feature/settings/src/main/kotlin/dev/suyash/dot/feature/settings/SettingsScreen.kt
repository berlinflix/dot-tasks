package dev.suyash.dot.feature.settings

import android.app.KeyguardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.DateFormat
import dev.suyash.dot.core.data.settings.UserSettings
import dev.suyash.dot.core.designsystem.component.DotPill
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.dotmatrix.DotMatrixText
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.time.DayParts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * App settings. App-level sections that depend on other features (reminder permissions, account &
 * sync) are passed in as slots so this module stays independent.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    remindersStatus: @Composable () -> Unit,
    accountSection: @Composable () -> Unit,
    widgetsSection: @Composable () -> Unit,
    onOpenRingtoneSettings: () -> Unit,
    appVersion: String,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(context.contentResolver, uri)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    SettingsScreen(
        settings = settings,
        onBack = onBack,
        onUpdate = viewModel::update,
        remindersStatus = remindersStatus,
        accountSection = accountSection,
        widgetsSection = widgetsSection,
        onOpenRingtoneSettings = onOpenRingtoneSettings,
        onExport = { exportFile.launch("dot-tasks-${LocalDate.now()}.json") },
        onAppLock = { enable ->
            val secure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
            if (enable && !secure) {
                Toast.makeText(context, "Set a screen lock in system settings first.", Toast.LENGTH_LONG).show()
            } else {
                viewModel.update { it.copy(appLock = enable) }
            }
        },
        appVersion = appVersion,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onUpdate: ((UserSettings) -> UserSettings) -> Unit,
    remindersStatus: @Composable () -> Unit,
    accountSection: @Composable () -> Unit,
    widgetsSection: @Composable () -> Unit,
    onOpenRingtoneSettings: () -> Unit,
    onExport: () -> Unit,
    onAppLock: (Boolean) -> Unit,
    appVersion: String,
) {
    var editingTime by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    // No browser installed is the only failure; there's nothing useful to show for it.
    val openUrl = { url: String -> runCatching { uriHandler.openUri(url) }; Unit }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        item {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(DotIcons.Back, contentDescription = "Back") }
                Spacer(Modifier.padding(start = 4.dp))
                DotMatrixText("SETTINGS", dotSize = 4.dp)
            }
            Spacer(Modifier.height(16.dp))
        }

        item { accountSection() }

        item { Header("Home screen") }
        item { widgetsSection() }

        item { Header("Reminders") }
        item { remindersStatus() }
        item {
            Label("New reminders")
            ChoiceRow(
                options = listOf(RingMode.RING to "Ring like a call", RingMode.NOTIFY to "Notification"),
                selected = settings.defaultRingMode,
                onSelect = { mode -> onUpdate { it.copy(defaultRingMode = mode) } },
            )
        }
        item {
            Label("Ring for")
            ChoiceRow(
                options = listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min"),
                selected = settings.ringDurationSeconds,
                onSelect = { seconds -> onUpdate { it.copy(ringDurationSeconds = seconds) } },
            )
        }
        item {
            Label("If unanswered")
            ChoiceRow(
                options = listOf(0 to "Mark missed", 5 to "Snooze 5 min", 10 to "Snooze 10 min", 15 to "Snooze 15 min"),
                selected = settings.autoSnoozeMinutes,
                onSelect = { minutes -> onUpdate { it.copy(autoSnoozeMinutes = minutes) } },
            )
        }
        item {
            NavRow(
                title = "Ringtone & vibration",
                subtitle = "Choose the ringing sound in system settings",
                onClick = onOpenRingtoneSettings,
            )
        }

        item { Header("Your day") }
        item {
            Text(
                "Used by snooze options and by phrases like “tomorrow” or “tonight”.",
                style = MaterialTheme.typography.bodySmall,
                color = DotTheme.colors.muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        val parts = settings.dayParts
        item { TimeRow("Morning", parts.morning) { editingTime = "morning" } }
        item { TimeRow("Afternoon", parts.afternoon) { editingTime = "afternoon" } }
        item { TimeRow("Evening", parts.evening) { editingTime = "evening" } }
        item { TimeRow("Night", parts.night) { editingTime = "night" } }
        item {
            Label("Weekend starts on")
            ChoiceRow(
                options = listOf(DayOfWeek.FRIDAY to "Friday", DayOfWeek.SATURDAY to "Saturday", DayOfWeek.SUNDAY to "Sunday"),
                selected = parts.weekendStart,
                onSelect = { day -> onUpdate { it.copy(dayParts = it.dayParts.copy(weekendStart = day)) } },
            )
        }

        item { Header("Privacy") }
        item {
            SwitchRow(
                title = "App lock",
                subtitle = "Ask for your fingerprint or screen lock to open Dot. Widgets hide task titles; reminders still ring.",
                checked = settings.appLock,
                onChange = onAppLock,
            )
        }
        item {
            SwitchRow(
                title = "Show titles on the lock screen",
                subtitle = "Off: the ring screen and notifications say only “Task reminder” until you unlock.",
                checked = settings.showTitlesOnLockScreen,
                onChange = { value -> onUpdate { it.copy(showTitlesOnLockScreen = value) } },
            )
        }
        item {
            SwitchRow(
                title = "Hide task titles in widgets",
                subtitle = if (settings.appLock) "Always on while App lock is on." else "Widgets show counts only.",
                checked = settings.hidesWidgetTitles,
                enabled = !settings.appLock,
                onChange = { value -> onUpdate { it.copy(hideWidgetTitles = value) } },
            )
        }
        item {
            SwitchRow(
                title = "Hide app preview in Recents",
                subtitle = "The app switcher shows a blank card instead of your tasks.",
                checked = settings.hideInRecents,
                onChange = { value -> onUpdate { it.copy(hideInRecents = value) } },
            )
        }
        item {
            NavRow(
                title = "Privacy policy",
                subtitle = "What Dot stores, and how to delete your account",
                onClick = { openUrl(PRIVACY_POLICY_URL) },
            )
        }

        item { Header("Your data") }
        item {
            NavRow(
                title = "Export tasks",
                subtitle = "Save all lists and tasks as a JSON file. The file isn't encrypted.",
                onClick = onExport,
            )
        }

        item { Header("About") }
        item {
            Text(
                "Dot $appVersion\nFonts: Space Grotesk & Space Mono (SIL Open Font License 1.1).\n" +
                    "No ads, no analytics, no trackers. Your tasks are encrypted on this phone before they are synced.",
                style = MaterialTheme.typography.bodySmall,
                color = DotTheme.colors.muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        item {
            NavRow(
                title = "Source code & licenses",
                subtitle = "Open source under the Apache License 2.0",
                onClick = { openUrl(SOURCE_CODE_URL) },
            )
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }

    editingTime?.let { key ->
        val parts = settings.dayParts
        val current = when (key) {
            "morning" -> parts.morning
            "afternoon" -> parts.afternoon
            "evening" -> parts.evening
            else -> parts.night
        }
        TimeDialog(
            initial = current,
            onDismiss = { editingTime = null },
            onConfirm = { time ->
                editingTime = null
                onUpdate { s ->
                    val p: DayParts = s.dayParts
                    s.copy(
                        dayParts = when (key) {
                            "morning" -> p.copy(morning = time)
                            "afternoon" -> p.copy(afternoon = time)
                            "evening" -> p.copy(evening = time)
                            else -> p.copy(night = time)
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun Header(text: String) {
    Column {
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = DotTheme.colors.hairline)
        SectionLabel(text, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp))
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (value, label) ->
            DotPill(text = label, selected = value == selected, onClick = { onSelect(value) })
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DotTheme.colors.muted)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DotTheme.colors.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = DotTheme.colors.accent),
        )
    }
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onClick: () -> Unit) {
    val context = LocalContext.current
    val formatter = remember(context) {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(formatter.format(time), style = MaterialTheme.typography.labelLarge, color = DotTheme.colors.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

private const val PRIVACY_POLICY_URL = "https://dot-tasks-pys6y.web.app/privacy"
private const val SOURCE_CODE_URL = "https://github.com/berlinflix/dot-tasks"
