package com.xtreeroot.mb.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.util.Consumer

internal enum class PlaybackDirection(val request: Int) {
    Portrait(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT),
    Landscape(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE),
}

@Composable
internal fun applyPlaybackOrientation(direction: PlaybackDirection?): Boolean {
    val activity = LocalActivity.current as? ComponentActivity ?: return false
    var multiWindow by remember(activity) { mutableStateOf(activity.isInMultiWindowMode) }
    DisposableEffect(activity) {
        val listener = Consumer<MultiWindowModeChangedInfo> { multiWindow = it.isInMultiWindowMode }
        activity.addOnMultiWindowModeChangedListener(listener)
        onDispose { activity.removeOnMultiWindowModeChangedListener(listener) }
    }
    val canRotate = !multiWindow && LocalConfiguration.current.smallestScreenWidthDp in 1 until 600
    val requested = direction?.request?.takeIf { canRotate } ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    LaunchedEffect(activity, requested) {
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
    }
    return canRotate
}
