package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal val musicLight = lightColorScheme(
    primary = Color(0xFFAF173F), onPrimary = Color.White, primaryContainer = Color(0xFFFBE3E8),
    onPrimaryContainer = Color(0xFF590D23), secondary = Color(0xFF5F4B53), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E7EB), onSecondaryContainer = Color(0xFF432C35),
    tertiary = Color(0xFF386460), onTertiary = Color.White,
    background = Color(0xFFFAF9F9), surface = Color(0xFFFFFDFD), onBackground = Color(0xFF1E1A1D),
    onSurface = Color(0xFF1E1A1D), surfaceVariant = Color(0xFFF0ECED), onSurfaceVariant = Color(0xFF655C62),
    surfaceContainerLow = Color(0xFFF6F3F4), surfaceContainer = Color(0xFFF2EFF0),
    surfaceContainerHigh = Color(0xFFECE7EA), surfaceContainerHighest = Color(0xFFE8E2E5),
    outline = Color(0xFF80757C), outlineVariant = Color(0xFFDED6DA),
)

internal val musicDark = darkColorScheme(
    primary = Color(0xFFFF86A1), onPrimary = Color(0xFF43041B), primaryContainer = Color(0xFF641832),
    onPrimaryContainer = Color(0xFFFFDFE7), secondary = Color(0xFFD2B6C0), onSecondary = Color(0xFF382930),
    secondaryContainer = Color(0xFF402F37), onSecondaryContainer = Color(0xFFF7DDE5),
    tertiary = Color(0xFF9ACAC3), onTertiary = Color(0xFF123A35),
    background = Color(0xFF141214), surface = Color(0xFF1D1A1D), onBackground = Color(0xFFF5EFF2),
    onSurface = Color(0xFFF5EFF2), surfaceVariant = Color(0xFF342E33), onSurfaceVariant = Color(0xFFC7BCC3),
    surfaceContainerLow = Color(0xFF211D21), surfaceContainer = Color(0xFF262226),
    surfaceContainerHigh = Color(0xFF302930), surfaceContainerHighest = Color(0xFF372F36),
    outline = Color(0xFF9B8E97), outlineVariant = Color(0xFF453B43),
)

@Composable
internal fun MusicBottomDock(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.navigationBars), content = content)
}

@Composable
internal fun MusicNavigation(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
internal fun MusicNavigationItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit,
                                 modifier: Modifier = Modifier) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.heightIn(min = 56.dp).selectable(active, onClick = onClick, role = Role.Tab)
        .padding(horizontal = 3.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = color)
        Text(label, color = color, textAlign = TextAlign.Center, maxLines = 2,
            style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}
