package dev.suyash.dot.feature.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.suyash.dot.core.designsystem.component.DotCard
import dev.suyash.dot.core.designsystem.component.DotPill
import dev.suyash.dot.core.designsystem.component.DotRoundButton
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.time.SnoozeCalculator
import dev.suyash.dot.core.ui.TimeFormats
import dev.suyash.dot.core.ui.label
import dev.suyash.dot.feature.voice.speech.SpeechFailure
import kotlinx.coroutines.delay

/**
 * One tap from the home-screen widget: listen → understand → save → confirm → close.
 * Floats over the home screen; never keeps audio.
 */
@AndroidEntryPoint
class VoiceCaptureActivity : ComponentActivity() {

    private val viewModel: VoiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DotTheme {
                VoiceCapture(viewModel = viewModel, onClose = ::finish, onEdit = ::openInApp, firstLaunch = savedInstanceState == null)
            }
        }
    }

    private fun openInApp(taskId: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_TASK_ID, taskId)
        startActivity(launch)
        finish()
    }

    companion object {
        /** Must match the main app's open-task extra. */
        const val EXTRA_OPEN_TASK_ID = "dev.suyash.dot.extra.OPEN_TASK_ID"

        fun intent(context: android.content.Context): Intent =
            Intent(context, VoiceCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

@Composable
private fun VoiceCapture(viewModel: VoiceViewModel, onClose: () -> Unit, onEdit: (String) -> Unit, firstLaunch: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.permissionDenied()
    }

    LaunchedEffect(Unit) {
        if (!firstLaunch) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Saved → confirm haptic, then close by itself unless the user starts interacting.
    LaunchedEffect(state) {
        if (state is VoiceState.Saved) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            delay(AUTO_CLOSE_MS)
            if (viewModel.shouldAutoClose() && (state as VoiceState.Saved).at != null) onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        DotCard(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(12.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewModel.touched() },
        ) {
            AnimatedContent(targetState = state, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "voice") { current ->
                when (current) {
                    VoiceState.Starting, is VoiceState.Listening -> ListeningContent(
                        state = current as? VoiceState.Listening,
                        onType = viewModel::typeInstead,
                    )
                    is VoiceState.Saved -> SavedContent(
                        state = current,
                        viewModel = viewModel,
                        onUndo = { viewModel.undo(); onClose() },
                        onEdit = { onEdit(current.taskId.value) },
                        onDone = onClose,
                    )
                    is VoiceState.Failed -> FailedContent(current.reason, onRetry = viewModel::retry, onType = viewModel::typeInstead)
                    is VoiceState.Typing -> TypingContent(unavailable = current.unavailable, onSubmit = viewModel::submitTyped)
                    VoiceState.NeedsPermission -> PermissionContent(
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onType = viewModel::typeInstead,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningContent(state: VoiceState.Listening?, onType: () -> Unit) {
    val colors = DotTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            SectionLabel("Listening", modifier = Modifier.weight(1f))
            TextButton(onClick = onType) { Text("Type", color = colors.muted) }
        }
        Spacer(Modifier.height(8.dp))
        LevelMeter(levels = state?.levels.orEmpty(), lit = colors.accent, unlit = colors.dotUnlit)
        Spacer(Modifier.height(12.dp))
        Text(
            text = state?.partial?.ifBlank { null } ?: "Try “remind me to buy milk tomorrow at 8 am”",
            style = MaterialTheme.typography.titleMedium,
            color = if (state?.partial.isNullOrBlank()) colors.muted else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SavedContent(
    state: VoiceState.Saved,
    viewModel: VoiceViewModel,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val colors = DotTheme.colors
    val now = remember { viewModel.now() }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotRoundButton(
                icon = DotIcons.Check,
                contentDescription = "Saved — close",
                onClick = onDone,
                size = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(12.dp))
            SectionLabel("Saved", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(state.title.ifBlank { state.heard }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        val at = state.at
        if (at != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DotPill(
                    text = formats.dayAndTime(at, now),
                    selected = true,
                    onClick = onEdit,
                    leadingIcon = if (state.mode == RingMode.RING) DotIcons.Phone else DotIcons.Bell,
                )
                DotPill(
                    text = if (state.mode == RingMode.RING) "Rings" else "Notifies",
                    selected = false,
                    onClick = { viewModel.touched(); viewModel.toggleMode() },
                )
            }
        } else {
            Text("No time heard — remind me…", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            Spacer(Modifier.height(8.dp))
            val options = remember(now) { SnoozeCalculator(viewModel.dayParts).options(now).take(4) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options) { option ->
                    DotPill(
                        text = option.preset.label(),
                        subtitle = formats.chip(option.at, now),
                        selected = false,
                        onClick = { viewModel.touched(); viewModel.remindAt(option.at) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onUndo) { Text("Undo", color = colors.accent) }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun FailedContent(reason: SpeechFailure, onRetry: () -> Unit, onType: () -> Unit) {
    val message = when (reason) {
        SpeechFailure.NO_MATCH, SpeechFailure.NO_SPEECH -> "Didn't catch that."
        SpeechFailure.LANGUAGE_UNAVAILABLE -> "Offline speech for your language is downloading. Try again in a minute, or type."
        SpeechFailure.PERMISSION -> "Microphone permission is needed."
        SpeechFailure.BUSY -> "The speech service is busy."
        SpeechFailure.UNAVAILABLE -> "On-device speech isn't available right now."
        SpeechFailure.OTHER -> "Something went wrong."
    }
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Voice")
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onType) { Text("Type instead") }
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun TypingContent(unavailable: Boolean, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("New task")
        if (unavailable) {
            Spacer(Modifier.height(6.dp))
            Text(
                "On-device speech recognition isn't available on this phone, so nothing is sent anywhere — just type.",
                style = MaterialTheme.typography.bodySmall,
                color = DotTheme.colors.muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500) },
            placeholder = { Text("call mom tomorrow 6pm") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit(text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Save") }
        }
    }
}

@Composable
private fun PermissionContent(onOpenSettings: () -> Unit, onType: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Microphone")
        Spacer(Modifier.height(8.dp))
        Text(
            "Allow the microphone to add tasks by voice. Speech is turned into text on this phone; audio is never stored or uploaded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onType) { Text("Type instead") }
            TextButton(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

private const val AUTO_CLOSE_MS = 3_500L
