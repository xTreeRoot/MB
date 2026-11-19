package com.xtreeroot.mb.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.preferences.AppearanceBackgroundStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class BackgroundError { Load, Import, Clear }

internal data class AppearanceBackgroundState(val image: Bitmap? = null, val busy: Boolean = true, val error: BackgroundError? = null)

internal class AppearanceBackgroundModel(application: Application) : AndroidViewModel(application) {
    private val store = AppearanceBackgroundStore(application)
    private val current = MutableStateFlow(AppearanceBackgroundState())
    val state = current.asStateFlow()

    init { edit(BackgroundError.Load, store::load) }

    fun select(uri: Uri) {
        if (!current.value.busy) edit(BackgroundError.Import) { store.replace(uri) }
    }

    fun clear() {
        if (!current.value.busy) edit(BackgroundError.Clear) { store.clear(); null }
    }

    private fun edit(error: BackgroundError, action: suspend () -> Bitmap?) {
        val previous = current.value
        current.value = previous.copy(busy = true, error = null)
        viewModelScope.launch {
            current.value = try { AppearanceBackgroundState(action(), busy = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { previous.copy(busy = false, error = error) }
        }
    }
}

@Composable
internal fun AppearanceBackdrop(image: Bitmap?, dark: Boolean, content: @Composable (Color) -> Unit) {
    val base = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        if (image != null) {
            Box(Modifier.matchParentSize().background(base))
            Image(remember(image) { image.asImageBitmap() }, contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop)
            val veil = if (dark) Color.Black.copy(alpha = 0.76f) else Color.White.copy(alpha = 0.80f)
            Box(Modifier.matchParentSize().background(veil))
        }
        content(if (image == null) base else Color.Transparent)
    }
}

@Composable
internal fun AppearanceBackgroundSetting(state: AppearanceBackgroundState, choose: () -> Unit, clear: () -> Unit) {
    Column {
        TextButton(onClick = choose, enabled = !state.busy) { Text(mb("选择背景图片", "Choose Background Image")) }
        if (state.image != null || state.error == BackgroundError.Load) {
            TextButton(onClick = clear, enabled = !state.busy) { Text(mb("恢复默认背景", "Restore Default Background")) }
        }
        val error = when (state.error) {
            BackgroundError.Load -> mb("无法读取已保存的背景", "Can't read the saved background")
            BackgroundError.Import -> mb("无法使用这张图片", "Can't use this image")
            BackgroundError.Clear -> mb("无法清除已保存的背景", "Can't clear the saved background")
            null -> null
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
