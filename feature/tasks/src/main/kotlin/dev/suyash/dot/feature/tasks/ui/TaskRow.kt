package dev.suyash.dot.feature.tasks.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.ui.TimeFormats
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One task. Swipe right to complete, swipe left to snooze. Tap to edit.
 */
@Composable
internal fun TaskRow(
    task: Task,
    now: ZonedDateTime,
    onToggleDone: () -> Unit,
    onToggleStar: () -> Unit,
    onOpen: () -> Unit,
    onSnoozeRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onToggleDone()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSnoozeRequest()
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // Always spring back; the list itself updates from the database.
        },
    )
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = !task.isDone,
        backgroundContent = { SwipeBackground(swipe.targetValue, task.isDone) },
    ) {
        TaskRowContent(task, now, onToggleDone, onToggleStar, onOpen)
    }
}

@Composable
private fun SwipeBackground(target: SwipeToDismissBoxValue, isDone: Boolean) {
    val colors = DotTheme.colors
    val (color, icon, alignment) = when (target) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(MaterialTheme.colorScheme.primary, DotIcons.Check, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart -> Triple(colors.accent, DotIcons.Clock, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, Alignment.Center)
    }
    val animated by animateColorAsState(color, label = "swipeColor")
    Box(
        Modifier
            .fillMaxSize()
            .background(animated)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            val tint = if (target == SwipeToDismissBoxValue.StartToEnd) MaterialTheme.colorScheme.onPrimary else colors.onAccent
            Icon(if (isDone && icon == DotIcons.Check) DotIcons.Close else icon, contentDescription = null, tint = tint)
        }
    }
}

@Composable
private fun TaskRowContent(
    task: Task,
    now: ZonedDateTime,
    onToggleDone: () -> Unit,
    onToggleStar: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val colors = DotTheme.colors
    val reminderAt = task.reminder?.at?.atZone(ZoneId.systemDefault())
    val overdue = !task.isDone && reminderAt != null && reminderAt.isBefore(now)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DoneCircle(done = task.isDone, onClick = onToggleDone)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.isDone) colors.muted else MaterialTheme.colorScheme.onBackground,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.notes.isNotBlank() && !task.isDone) {
                Text(task.notes, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = buildList {
                if (reminderAt != null && !task.isDone) add(formats.dayAndTime(reminderAt, now))
                else task.dueDate?.let { if (!task.isDone) add(formats.day(it, now.toLocalDate())) }
            }
            if (meta.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (reminderAt != null) {
                        Icon(
                            imageVector = if (task.reminder?.mode == RingMode.RING) DotIcons.Phone else DotIcons.Bell,
                            contentDescription = if (task.reminder?.mode == RingMode.RING) "Rings" else "Notifies",
                            tint = if (overdue) colors.accent else colors.muted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overdue) colors.accent else colors.muted,
                    )
                }
            }
        }
        IconButton(onClick = onToggleStar) {
            Icon(
                imageVector = if (task.isStarred) DotIcons.StarFilled else DotIcons.Star,
                contentDescription = if (task.isStarred) "Unstar" else "Star",
                tint = if (task.isStarred) MaterialTheme.colorScheme.onBackground else colors.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Round Nothing-style checkbox. */
@Composable
internal fun DoneCircle(done: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val colors = DotTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Checkbox) {
                view.performHapticFeedback(if (done) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.CONFIRM)
                onClick()
            }
            .semantics {
                contentDescription = "Complete"
                stateDescription = if (done) "Done" else "Not done"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(1.5.dp, if (done) MaterialTheme.colorScheme.primary else colors.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(DotIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
        }
    }
}
