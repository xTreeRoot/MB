package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import com.xtreeroot.mb.ui.components.MBButton as Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.SourceAccount

@Composable
internal fun AccountHeader(source: String, account: SourceAccount?, loading: Boolean, signInLabel: String?, signIn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
        .clip(RoundedCornerShape(22.dp)).background(colors.surfaceContainerLow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val avatar = Modifier.size(54.dp).clip(CircleShape)
            if (account?.avatarUrl != null) Artwork(account.avatarUrl, account.avatarHeaders, avatar)
            else Box(avatar.background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, null, Modifier.size(26.dp), tint = colors.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account?.name?.takeIf(String::isNotBlank) ?: source, Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val subtitle = if (account != null) source else if (signInLabel != null) mb("未登录", "Not Signed In") else null
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
        }
        if (account == null && signInLabel != null) {
            Button(onClick = signIn, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(signInLabel) }
        }
    }
}

internal data class AccountDestination(val title: String, val icon: ImageVector, val open: () -> Unit)

@Composable
internal fun AccountDestinations(destinations: List<AccountDestination>) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerLow)) {
        destinations.forEachIndexed { index, destination ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 54.dp), color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = destination.open)
                .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(destination.icon, null, Modifier.size(24.dp), tint = colors.primary)
                Text(destination.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}
