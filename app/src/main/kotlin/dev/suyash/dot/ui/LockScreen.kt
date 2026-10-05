package dev.suyash.dot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.suyash.dot.core.designsystem.component.DotRoundButton
import dev.suyash.dot.core.designsystem.dotmatrix.DotMatrixText
import dev.suyash.dot.core.designsystem.icon.DotIcons
import dev.suyash.dot.core.designsystem.theme.DotTheme

/** Covers the app while it's locked; asks to unlock right away. */
@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DotMatrixText("LOCKED", dotSize = 5.dp)
        Spacer(Modifier.height(24.dp))
        DotRoundButton(icon = DotIcons.Lock, contentDescription = "Unlock", onClick = onUnlock)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onUnlock) { Text("Unlock") }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = DotTheme.colors.muted, textAlign = TextAlign.Center)
        }
    }
}
