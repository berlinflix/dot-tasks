package dev.suyash.dot.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.theme.DotTheme

/** Small caps mono label, e.g. "TODAY", "COMPLETED · 3". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = DotTheme.colors.muted) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier,
    )
}

/** Rounded selectable pill — list tabs, snooze presets, time chips. */
@Composable
fun DotPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    subtitle: String? = null,
    accent: Boolean = false,
) {
    val colors = DotTheme.colors
    val background = when {
        accent -> colors.accent
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val content = when {
        accent -> colors.onAccent
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(BorderStroke(1.dp, if (selected || accent) Color.Transparent else colors.hairline), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (subtitle == null) 9.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Column {
            Text(text, style = MaterialTheme.typography.titleSmall, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

/** A raised card with a hairline border (Nothing cards are flat, outlined, generously rounded). */
@Composable
fun DotCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = DotTheme.colors.card,
        border = BorderStroke(1.dp, DotTheme.colors.hairline),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** Circular icon button with an optional filled background (mic / add / call buttons). */
@Composable
fun DotRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = contentColor, modifier = Modifier.size(iconSize))
    }
}
