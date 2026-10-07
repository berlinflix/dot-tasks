package dev.suyash.dot.feature.tasks.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.component.DotRoundButton
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme
import dev.suyash.dot.core.domain.nlp.ParsedUtterance
import dev.suyash.dot.core.ui.TimeFormats
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime

private const val PREVIEW_DELAY_MS = 120L

/**
 * Bottom input: type "pay rent on 5th 9am" and the parsed time shows up live before you submit.
 * The mic opens voice capture, which uses the same parser.
 */
@Composable
internal fun QuickAddBar(
    now: () -> ZonedDateTime,
    parse: suspend (String) -> ParsedUtterance,
    onSubmit: (String) -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val formats = remember(context) { TimeFormats.from(context) }
    val colors = DotTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    // The preview parses in the background once typing pauses, so keystrokes never wait for it.
    var parsed by remember { mutableStateOf<ParsedUtterance?>(null) }
    LaunchedEffect(text) {
        if (text.isBlank()) {
            parsed = null
            return@LaunchedEffect
        }
        delay(PREVIEW_DELAY_MS)
        parsed = parse(text)
    }

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = parsed?.at != null) {
            val result = parsed
            val at = result?.at?.atZone(ZoneId.systemDefault())
            if (result != null && at != null) {
                Row(
                    Modifier.padding(start = 20.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (result.repeat != null) DotIcons.Repeat else if (result.ringRequested) DotIcons.Phone else DotIcons.Bell,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    val repeat = result.repeat?.let { " · " + it.describe(at.toLocalDate()) }.orEmpty()
                    Text(
                        "${result.title.ifBlank { "…" }} · ${formats.dayAndTime(at, now())}$repeat",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (text.isNotBlank()) {
                        onSubmit(text)
                        text = ""
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .background(DotTheme.colors.card, RoundedCornerShape(50))
                    .border(1.dp, colors.hairline, RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text("Add a task — “call mom 6pm”", style = MaterialTheme.typography.bodyLarge, color = colors.muted, maxLines = 1)
                    }
                    inner()
                },
            )
            Spacer(Modifier.width(10.dp))
            if (text.isBlank()) {
                DotRoundButton(
                    icon = DotIcons.Mic,
                    contentDescription = "Add task by voice",
                    onClick = onMic,
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                )
            } else {
                DotRoundButton(
                    icon = DotIcons.Plus,
                    contentDescription = "Add task",
                    onClick = {
                        onSubmit(text)
                        text = ""
                    },
                )
            }
        }
    }
}
