package com.xtreeroot.mb.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.xtreeroot.mb.music.SignInStatus
import com.xtreeroot.mb.music.LibraryRoute
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccountView(model: AccountModel, open: (LibraryRoute) -> Unit) {
    val state by model.account.collectAsStateWithLifecycle()
    var signOut by rememberSaveable { mutableStateOf(false) }
    val current = state.account
    val refreshing = state.loading && !state.signingIn && current != null
    RefreshableList(refreshing = refreshing, refresh = { if (!state.loading) model.refresh() }) {
        item {
            AccountHeader(mb("哔哩哔哩", "Bilibili"), current, state.loading,
                mb("扫码登录", "QR Sign In").takeIf { state.canSignIn }, model::signIn)
        }
        if (current != null) {
            item {
                AccountDestinations(listOf(LibraryRoute.Creator(current.id) to Icons.Default.AccountBox,
                    LibraryRoute.Favorites to Icons.AutoMirrored.Filled.List,
                    LibraryRoute.Liked to Icons.Default.Favorite, LibraryRoute.History to Icons.Default.PlayArrow,
                    LibraryRoute.Following to Icons.Default.Person).map { (route, icon) ->
                    val title = if (route is LibraryRoute.Creator) mb("我的投稿", "My Uploads") else route.title()
                    AccountDestination(title, icon) { open(route) }
                })
            }
            item {
                TextButton(onClick = { signOut = true }, enabled = !state.loading, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(mb("退出登录", "Sign Out"), color = if (state.loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
        if (!state.signingIn) {
            if (state.loading && !refreshing) item { Loading() }
            state.error?.let { message -> item { Retry(message, model::refresh) } }
        }
    }
    if (state.signingIn) SignInDialog(state, model, builtIn = true)
    if (signOut) AccountSignOutDialog({ signOut = false }, model::signOut)
}

@Composable
internal fun AccountSignOutDialog(close: () -> Unit, signOut: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(mb("退出登录？", "Sign out?")) },
        confirmButton = { TextButton(onClick = { close(); signOut() }) { Text(mb("退出", "Sign Out")) } },
        dismissButton = { TextButton(onClick = close) { Text(mb("取消", "Cancel")) } })
}

@Composable
internal fun SignInDialog(state: AccountState, model: AccountModel, builtIn: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompt = state.prompt
    val image = remember(prompt?.qrUrl) { prompt?.qrUrl?.let { runCatching { qrBitmap(it) }.getOrNull() } }
    var shareError by remember(prompt?.qrUrl) { mutableStateOf(false) }
    val qrLabel = mb("登录二维码", "Sign In QR Code")
    val shareLabel = mb("分享二维码", "Share QR Code")
    AlertDialog(onDismissRequest = model::cancelSignIn,
        title = { Text(if (builtIn) mb("扫码登录", "QR Sign In") else mb("登录音源", "Sign In to Source")) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (image != null) {
                    Image(bitmap = image.asImageBitmap(), contentDescription = qrLabel,
                        modifier = Modifier.size(228.dp), filterQuality = FilterQuality.None)
                    TextButton(onClick = {
                        scope.launch {
                            try { shareQr(context, image, qrLabel, shareLabel) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { shareError = true }
                        }
                    }) { Text(shareLabel) }
                }
                if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
                else if (prompt == null) Loading()
                else Text(when (prompt.status) {
                    SignInStatus.Scan -> mb("用哔哩哔哩 App 扫码登录", "Scan with the Bilibili app.")
                    SignInStatus.Confirm -> mb("已扫码，请在哔哩哔哩 App 确认登录", "Scanned. Confirm sign in in the Bilibili app.")
                    SignInStatus.Refreshed -> mb("二维码已刷新，请扫描新的二维码", "QR code refreshed. Scan the new code.")
                    SignInStatus.Message -> prompt.message
                })
                if (shareError) Text(mb("无法分享二维码", "Could not share the QR code"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (state.error != null) TextButton(onClick = model::signIn) { Text(mb("重新登录", "Try Again")) }
        },
        dismissButton = { TextButton(onClick = model::cancelSignIn) { Text(mb("取消", "Cancel")) } })
}

private fun qrBitmap(url: String): Bitmap {
    val size = 384
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val colors = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
}

private suspend fun shareQr(context: Context, image: Bitmap, qrLabel: String, shareLabel: String) {
    val uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "sign-in").apply { mkdirs() }
        val file = File(directory, "mb-login.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(qrLabel, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, shareLabel))
}
