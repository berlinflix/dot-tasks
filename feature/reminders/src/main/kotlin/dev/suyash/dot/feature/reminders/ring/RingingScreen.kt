package dev.suyash.dot.feature.reminders.ring

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.component.DotPill
import dev.suyash.dot.core.designsystem.component.DotRoundButton
import dev.suyash.dot.core.designsystem.component.SectionLabel
import dev.suyash.dot.core.designsystem.component.dotGrid
import dev.suyash.dot.core.designsystem.dotmatrix.DotMatrixText
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.time.DayParts
import dev.suyash.dot.core.domain.time.SnoozeCalculator
import dev.suyash.dot.core.domain.time.SnoozePreset
import dev.suyash.dot.core.ui.DateTimePickerDialog
import dev.suyash.dot.core.ui.SnoozeSheetContent
import dev.suyash.dot.core.ui.TimeFormats
import dev.suyash.dot.feature.reminders.R
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime

/** A quick-snooze chip: the time is computed when tapped, not when the screen opened. */
private class QuickSnooze(val label: String, val resolve: (ZonedDateTime) -> ZonedDateTime?)

/**
 * The call-style ring screen. [tasks] empty + [genericMode] = before-unlock ring with no details.
 *
 * @param now current time, read at the moment of each action.
 * @param ringSeconds how long the reminder rings; the screen closes itself afterwards (the reminder
 *   is then a "missed" notification), unless the user is in the middle of picking a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RingingScreen(
    tasks: List<Task>,
    now: () -> ZonedDateTime,
    showTitles: Boolean,
    genericMode: Boolean,
    dayParts: DayParts,
    ringSeconds: Int,
    onSnooze: (ZonedDateTime) -> Unit,
    onDone: () -> Unit,
    onDismissGeneric: () -> Unit,
    onTimeout: () -> Unit,
) {
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val calculator = remember(dayParts) { SnoozeCalculator(dayParts) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val colors = DotTheme.colors
    val openedAt = remember { now() }
    val at = tasks.firstOrNull()?.reminder?.at?.atZone(ZoneId.systemDefault()) ?: openedAt

    // Close by itself when the ring is over (restarts the countdown after a sheet is dismissed).
    LaunchedEffect(ringSeconds, showSheet, showPicker) {
        if (showSheet || showPicker) return@LaunchedEffect
        delay((ringSeconds + 5) * 1_000L)
        onTimeout()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .dotGrid(color = Color.White.copy(alpha = 0.06f), spacing = 18.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(colors.accent)
                Spacer(Modifier.width(10.dp))
                SectionLabel(stringResource(R.string.ring_label), color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(36.dp))
            DotMatrixText(text = formats.clockFace(at), dotSize = 10.dp, color = Color.White, unlitColor = Color.White.copy(alpha = 0.07f))
            formats.meridiem(at)?.let {
                Spacer(Modifier.height(10.dp))
                DotMatrixText(text = it, dotSize = 4.dp, color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                formats.day(at.toLocalDate(), openedAt.toLocalDate()).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(40.dp))
            if (genericMode || !showTitles) {
                Text(stringResource(R.string.reminder_public_title_ring), style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.reminder_locked_body), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
            } else {
                tasks.take(3).forEach { task ->
                    Text(
                        task.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (tasks.size > 3) {
                    Text(stringResource(R.string.ring_more, tasks.size - 3), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f))
                }
            }

            Spacer(Modifier.weight(1f))

            if (!genericMode) {
                val plus10Label = stringResource(R.string.ring_quick_10)
                val plus60Label = stringResource(R.string.ring_quick_60)
                val quick = remember(calculator, openedAt, plus10Label, plus60Label) {
                    listOf(
                        QuickSnooze(plus10Label) { it.plusMinutes(10).withSecond(0).withNano(0) },
                        QuickSnooze(plus60Label) { it.plusHours(1).withSecond(0).withNano(0) },
                        QuickSnooze(
                            calculator.resolve(SnoozePreset.TOMORROW, openedAt)?.let { formats.chip(it, openedAt) } ?: "Tomorrow",
                        ) { calculator.resolve(SnoozePreset.TOMORROW, it) },
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(quick) { chip ->
                        DotPill(text = chip.label, selected = false, onClick = { chip.resolve(now())?.let(onSnooze) })
                    }
                    item {
                        DotPill(text = stringResource(R.string.ring_pick), selected = false, onClick = { showSheet = true })
                    }
                }
                Spacer(Modifier.height(32.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (genericMode) {
                    CallButton(label = stringResource(R.string.action_done), onClick = onDismissGeneric, filled = true, icon = DotIcons.Check)
                } else {
                    CallButton(
                        label = stringResource(R.string.action_snooze),
                        onClick = { onSnooze(now().plusMinutes(10).withSecond(0).withNano(0)) },
                        filled = false,
                        icon = DotIcons.Clock,
                    )
                    CallButton(label = stringResource(R.string.action_done), onClick = onDone, filled = true, icon = DotIcons.Check)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSheet) {
        val sheetNow = remember { now() }
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            SnoozeSheetContent(
                now = sheetNow,
                dayParts = dayParts,
                onPick = { showSheet = false; onSnooze(it) },
                onCustom = { showSheet = false; showPicker = true },
            )
        }
    }
    if (showPicker) {
        DateTimePickerDialog(
            initial = now().plusHours(1),
            onDismiss = { showPicker = false },
            onConfirm = { showPicker = false; onSnooze(it) },
        )
    }
}

@Composable
private fun CallButton(label: String, onClick: () -> Unit, filled: Boolean, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DotRoundButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            size = 76.dp,
            iconSize = 30.dp,
            containerColor = if (filled) Color.White else Color.White.copy(alpha = 0.12f),
            contentColor = if (filled) Color.Black else Color.White,
        )
        Spacer(Modifier.height(10.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}
