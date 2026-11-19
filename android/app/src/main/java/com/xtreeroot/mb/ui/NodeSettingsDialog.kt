package com.xtreeroot.mb.ui

import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.edge.NodeException
import com.xtreeroot.mb.edge.NodeFailure
import com.xtreeroot.mb.edge.nodeOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NodeSettingsDialog(app: MBApplication, profileSync: NodeProfileSyncState, canPublish: Boolean,
                                publish: () -> Unit, close: () -> Unit) {
    val binding by app.settings.nodeBinding.collectAsStateWithLifecycle()
    val valid = binding?.let { it.expiresAt > System.currentTimeMillis() / 1_000 } == true
    var address by remember { mutableStateOf(binding?.origin?.toString().orEmpty()) }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<NodeFailure?>(null) }
    var invalidAddress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = loading || profileSync == NodeProfileSyncState.Publishing
    AlertDialog(onDismissRequest = close, title = { Text(mb("接入节点", "Connect to Node")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            binding?.let {
                val status = if (valid) mb("已接入", "Connected") else mb("授权已过期", "Authorization expired")
                Text("$status · ${it.origin.host}")
            }
            OutlinedTextField(address, { address = it; invalidAddress = false }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(mb("服务器域名 / HTTPS 地址", "Server Domain / HTTPS Address")) }, singleLine = true,
                isError = invalidAddress)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(mb("密码", "Password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text(mb("只接入可信节点；节点管理员能够解密同步数据。", "Use a trusted node; its administrator can decrypt synced data."),
                style = MaterialTheme.typography.bodySmall)
            val message = if (invalidAddress) mb("请输入仅包含域名和可选端口的 HTTPS 地址。", "Enter an HTTPS domain with an optional port.")
                else when (error) {
                    NodeFailure.BadPassword -> mb("节点密码错误", "Wrong node password")
                    NodeFailure.Unauthorized -> mb("授权已失效，请重新接入", "Authorization expired; reconnect")
                    NodeFailure.Retryable -> mb("节点暂不可达，请稍后重试", "Node temporarily unavailable; try again later")
                    NodeFailure.KeyChanged -> mb("节点密钥已变化，请核实地址后解除旧绑定", "Node key changed; verify the address before disconnecting")
                    null -> null
                    else -> mb("节点不支持接入或响应无效", "Node join is unsupported or returned an invalid response")
                }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (binding != null) {
                TextButton(enabled = !busy && valid && canPublish, onClick = publish) {
                    Text(mb("同步当前平台资料", "Sync Current Platform Profile"))
                }
                when (profileSync) {
                    NodeProfileSyncState.Idle -> Unit
                    NodeProfileSyncState.Publishing -> Text(mb("正在同步…", "Syncing…"))
                    NodeProfileSyncState.Published -> Text(mb("平台资料已同步", "Platform profile synced"))
                    is NodeProfileSyncState.Failed -> Text(profileFailure(profileSync.failure), color = MaterialTheme.colorScheme.error)
                }
                TextButton(enabled = !busy, onClick = {
                    app.settings.setNodeBinding(null)
                    password = ""
                    error = null
                }) { Text(mb("取消本机绑定", "Disconnect This Device")) }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && address.isNotBlank() && password.isNotEmpty(), onClick = {
            val origin = runCatching { nodeOrigin(address) }.getOrElse { invalidAddress = true; return@TextButton }
            loading = true
            error = null
            val entered = password
            password = ""
            scope.launch {
                try {
                    val joined = app.nodeClient.join(origin, entered, Build.MODEL.take(64))
                    val previous = app.settings.nodeBinding.value
                    if (previous?.origin == origin && previous.catalogPublicKey != joined.catalogPublicKey) {
                        throw NodeException(NodeFailure.KeyChanged)
                    }
                    app.settings.setNodeBinding(joined)
                    close()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: NodeException) { error = failure.failure }
                finally { loading = false }
            }
        }) { Text(if (loading) mb("正在接入…", "Connecting…") else if (binding != null) mb("重新接入", "Reconnect") else mb("接入", "Connect")) }
    }, dismissButton = { TextButton(onClick = close) { Text(mb("关闭", "Close")) } })
}

@Composable
private fun profileFailure(failure: NodeFailure): String = when (failure) {
    NodeFailure.OtherAccount -> mb("云端资料属于另一个账号，未覆盖", "The profile belongs to another account; nothing was overwritten")
    NodeFailure.Deleted -> mb("云端资料已删除，未重新创建", "The profile was deleted; it was not recreated")
    NodeFailure.Conflict -> mb("云端资料已变更，未覆盖", "The profile changed; nothing was overwritten")
    NodeFailure.Unauthorized -> mb("授权已失效，请重新接入", "Authorization expired; reconnect")
    NodeFailure.Retryable -> mb("节点暂不可达，请稍后重试", "Node temporarily unavailable; try again later")
    else -> mb("节点不支持同步或资料无效，未覆盖本地资料", "Sync is unsupported or the profile is invalid; local data was not overwritten")
}
