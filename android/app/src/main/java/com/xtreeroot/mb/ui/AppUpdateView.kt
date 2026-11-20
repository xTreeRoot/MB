package com.xtreeroot.mb.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.plugin.PluginHttpTransport
import com.xtreeroot.mb.update.AppRelease
import com.xtreeroot.mb.update.AppUpdates
import com.xtreeroot.mb.update.compareVersions
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AppUpdateState(val checking: Boolean = false, val checked: Boolean = false, val release: AppRelease? = null,
                                   val downloading: Boolean = false, val progress: Float = 0f, val file: File? = null, val error: String? = null)
internal class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MBApplication
    private val updates = AppUpdates(app, PluginHttpTransport(app.network.proxySelector, callTimeoutSeconds = 600))
    private var operation: Job? = null
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    val version: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    fun check() {
        if (mutable.value.checking || mutable.value.downloading) return
        mutable.update { it.copy(checking = true, error = null) }
        operation = viewModelScope.launch {
            try {
                val latest = checkNotNull(updates.latest()) { "远程尚无可用的安卓更新包" }
                val release = latest.takeIf { compareVersions(it.version, version) > 0 }
                mutable.value.file?.delete()
                mutable.update { it.copy(checked = true, release = release, file = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "检查版本失败") } }
            finally { mutable.update { it.copy(checking = false) } }
        }
    }
    fun download() {
        val release = mutable.value.release ?: return
        if (mutable.value.downloading) return
        mutable.update { it.copy(downloading = true, error = null, progress = 0f) }
        operation = viewModelScope.launch {
            try {
                val file = updates.download(release) { progress -> mutable.update { it.copy(progress = progress) } }
                mutable.update { it.copy(file = file) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "下载更新失败") } }
            finally { mutable.update { it.copy(downloading = false) } }
        }
    }
    fun report(error: Exception) { mutable.update { it.copy(error = error.message) } }
    fun cancel() { operation?.cancel() }
}

@Composable
internal fun AppUpdateDialog(close: () -> Unit) {
    val model: AppUpdateModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dismiss = { model.cancel(); close() }
    AppUpdateContent(model.version, state, model::check, model::download, install = {
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            } else {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", checkNotNull(state.file))
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        } catch (error: Exception) { model.report(error) }
    }, dismiss = dismiss)
}

@Composable
internal fun AppUpdateContent(version: String, state: AppUpdateState, check: () -> Unit, download: () -> Unit,
                              install: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(mb("检查版本", "App Updates")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(mb("当前版本 $version", "Current version $version"))
            if (state.checking) Text(mb("正在检查远程版本…", "Checking releases…"))
            state.release?.let { release -> Text(mb("发现新版本 ${release.version}", "Version ${release.version} available")); Text(release.notes) }
            if (state.checked && state.release == null) Text(mb("已是最新版本", "You're up to date"))
            if (state.downloading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.padding(vertical = 8.dp))
            state.error?.let { Text(it) }
        }
    }, confirmButton = {
        when {
            state.file != null -> TextButton(onClick = install) { Text(mb("安装更新", "Install Update")) }
            state.release != null -> TextButton(onClick = download, enabled = !state.downloading) { Text(mb("下载更新", "Download Update")) }
            else -> TextButton(onClick = check, enabled = !state.checking) { Text(mb("检查版本", "Check Version")) }
        }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(if (state.downloading) mb("取消下载", "Cancel Download") else mb("关闭", "Close")) } })
}
