package com.xtreeroot.mb.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.local.OfflineMediaStore
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.preferences.AppAppearance
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.AppPreferences
import com.xtreeroot.mb.preferences.AudioNormalization
import com.xtreeroot.mb.preferences.LyricStyle
import com.xtreeroot.mb.preferences.PlayerStyle
import com.xtreeroot.mb.preferences.ProxyPolicy
import kotlin.math.roundToInt

internal val LocalMBEnglish = staticCompositionLocalOf { false }

@Composable
internal fun AppLanguage.usesEnglish(): Boolean = when (this) {
    AppLanguage.System -> LocalConfiguration.current.locales[0]?.language == "en"
    AppLanguage.Chinese -> false
    AppLanguage.English -> true
}

@Composable
internal fun mb(zh: String, en: String): String = if (LocalMBEnglish.current) en else zh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsView(values: AppPreferences, update: ((AppPreferences) -> AppPreferences) -> Unit,
                          saved: SavedLibraryModel, browser: BrowseModel, backgroundState: AppearanceBackgroundState,
                          background: AppearanceBackgroundModel, surface: Color, profileSync: NodeProfileSyncState,
                          canPublishProfile: Boolean, publishProfile: () -> Unit, openPlugins: () -> Unit, close: () -> Unit) {
    var network by rememberSaveable { mutableStateOf(false) }
    var node by rememberSaveable { mutableStateOf(false) }
    var clearHistory by rememberSaveable { mutableStateOf(false) }
    val history by browser.history.collectAsStateWithLifecycle()
    val offline by saved.offline.collectAsStateWithLifecycle()
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background.select(uri)
    }
    BackHandler(network) { network = false }
    Scaffold(containerColor = surface, contentColor = MaterialTheme.colorScheme.onBackground, topBar = {
        TopAppBar(title = { Text(if (network) mb("网络与系统代理", "Network & System Proxy") else mb("设置", "Settings")) }, actions = {
            TextButton(onClick = { if (network) network = false else close() }) { Text(mb("完成", "Done")) }
        })
    }) { padding ->
        if (network) NetworkSettings(values.proxy, { policy -> update { it.copy(proxy = policy) } }, Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SettingHeader(mb("显示", "Display")) }
            item {
                Choice(mb("语言", "Language"), values.language, listOf(AppLanguage.System to mb("跟随系统", "System"),
                    AppLanguage.Chinese to "简体中文", AppLanguage.English to "English")) { option -> update { it.copy(language = option) } }
            }
            item {
                Choice(mb("外观", "Appearance"), values.appearance, listOf(AppAppearance.System to mb("跟随系统", "System"),
                    AppAppearance.Light to mb("浅色", "Light"), AppAppearance.Dark to mb("深色", "Dark"))) { option ->
                    update { it.copy(appearance = option) }
                }
            }
            item { AppearanceBackgroundSetting(backgroundState, { pickBackground.launch(arrayOf("image/*")) }, background::clear) }
            item { SettingHeader(mb("音源", "Sources")) }
            item { TextButton(onClick = openPlugins) { Text(mb("插件", "Plugins")) } }
            item { SettingSwitch(mb("启用内置音源", "Enable Built-in Source"), values.builtInEnabled) { value -> update { it.copy(builtInEnabled = value) } } }
            item { SettingHeader(mb("播放", "Playback")) }
            item {
                Choice(mb("响度标准化", "Loudness Normalization"), values.normalization,
                    listOf(AudioNormalization.Standard to mb("标准", "Standard"), AudioNormalization.Dynamic to mb("动态", "Dynamic"),
                        AudioNormalization.Off to mb("关闭", "Off"))) { option -> update { it.copy(normalization = option) } }
            }
            item {
                Choice(mb("音质", "Audio Quality"), values.quality, listOf(AudioQuality.Standard to mb("标准", "Standard"),
                    AudioQuality.High to mb("高品质", "High"), AudioQuality.Lossless to mb("无损", "Lossless")),
                    hint = mb("新加载时生效；无损不可用时回退", "Applies on new streams; falls back if lossless is unavailable")) { option ->
                    update { it.copy(quality = option) }
                }
            }
            item {
                val label = mb("应用音量", "App Volume")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("$label · ${(values.volume * 100).roundToInt()}%")
                    Slider(values.volume, { volume -> update { it.copy(volume = volume) } },
                        modifier = Modifier.semantics { contentDescription = label })
                }
            }
            item { SettingHeader(mb("缓存", "Cache")) }
            item {
                Choice(mb("缓存上限", "Cache Limit"), offline.limitMb, OfflineMediaStore.limitsMb.map { size ->
                    size to if (size == 0) mb("不缓存", "Off") else "$size MB"
                }, changed = saved::setLimit)
            }
            item { SettingHeader(mb("锁屏与媒体控制", "Lock Screen & Media Controls")) }
            item {
                SettingSwitch(mb("在主标题显示当前歌词", "Show Current Lyric in Media Title"), values.nowPlayingLyrics) { enabled ->
                    update { it.copy(nowPlayingLyrics = enabled) }
                }
            }
            item { SettingHeader(mb("播放界面", "Player")) }
            item { SettingSwitch(mb("默认打开歌词", "Open Lyrics by Default"), values.lyricsStage) { value -> update { it.copy(lyricsStage = value) } } }
            item { SettingSwitch(mb("间奏显示热评", "Show Comments During Instrumentals"), values.hotComments) { value -> update { it.copy(hotComments = value) } } }
            item { SettingSwitch(mb("进度条烟雾", "Progress Bar Smoke"), values.smoke) { value -> update { it.copy(smoke = value) } } }
            item { SettingSwitch(mb("低音雾气背景", "Bass Fog Background"), values.fog) { value -> update { it.copy(fog = value) } } }
            item { SettingSwitch(mb("从封面展开", "Expand from Cover"), values.growFromCover) { value -> update { it.copy(growFromCover = value) } } }
            item { SettingSwitch(mb("歌词火花", "Lyric Sparks"), values.stageSparks) { value -> update { it.copy(stageSparks = value) } } }
            item {
                Choice(mb("播放器样式", "Player Style"), values.playerStyle, PlayerStyle.entries.map { it to it.label() }) { option ->
                    update { it.copy(playerStyle = option) }
                }
            }
            item {
                Choice(mb("歌词舞台", "Lyric Stage"), values.lyricStyle, LyricStyle.entries.map { it to it.label() }) { option ->
                    update { it.copy(lyricStyle = option) }
                }
            }
            item { SettingHeader(mb("网络", "Network")) }
            item { TextButton(onClick = { network = true }) { Text(mb("网络与系统代理", "Network & System Proxy")) } }
            item { TextButton(onClick = { node = true }) { Text(mb("接入节点", "Connect to Node")) } }
            item { TextButton(onClick = { clearHistory = true }, enabled = history.isNotEmpty()) { Text(mb("清空搜索记录", "Clear Search History")) } }
        }
    }
    if (node) NodeSettingsDialog(LocalContext.current.applicationContext as MBApplication, profileSync,
        canPublishProfile, publishProfile) { node = false }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text(mb("清空搜索记录？", "Clear search history?")) },
        confirmButton = { TextButton(onClick = { browser.clearHistory(); clearHistory = false }) { Text(mb("清空", "Clear")) } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text(mb("取消", "Cancel")) } })
}

@Composable
private fun NetworkSettings(policy: ProxyPolicy, save: (ProxyPolicy) -> Unit, modifier: Modifier = Modifier) {
    var draft by rememberSaveable(policy.hosts) { mutableStateOf(policy.hosts.joinToString("\n")) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(mb("仅以下域名使用系统代理。其余地址直接连接。", "Only the following domains use the system proxy. Other traffic connects directly."),
                Modifier.padding(vertical = 12.dp))
            OutlinedTextField(draft, { draft = it; invalid = false; saved = false }, Modifier.fillMaxWidth(),
                label = { Text(mb("代理域名", "Proxy Domains")) }, minLines = 5, maxLines = 10, isError = invalid)
            if (invalid) Text(mb("只允许域名或 *.域名，不包含 URL、端口或账号。", "Enter domain names or *.domain, without a URL, port, or credentials."),
                color = MaterialTheme.colorScheme.error)
            if (saved) Text(mb("已保存，后续请求立即生效", "Saved. Applies to new requests."))
            TextButton(onClick = {
                val parsed = runCatching { ProxyPolicy.parse(draft) }.getOrNull()
                if (parsed == null) invalid = true else { save(parsed); draft = parsed.hosts.joinToString("\n"); saved = true }
            }) { Text(mb("保存", "Save")) }
            TextButton(onClick = { draft = ProxyPolicy().hosts.joinToString("\n"); invalid = false; saved = false }) {
                Text(mb("恢复默认域名", "Restore Default Domains"))
            }
        }
    }
}

@Composable
private fun SettingHeader(label: String) {
    Text(label, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun <Value> Choice(label: String, value: Value, options: List<Pair<Value, String>>, hint: String? = null,
                           changed: (Value) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(label) }, supportingContent = hint?.let { { Text(it) } },
        modifier = Modifier.clickable { expanded = true }, trailingContent = {
            Box {
                Text(options.firstOrNull { it.first == value }?.second.orEmpty(), color = MaterialTheme.colorScheme.primary)
                DropdownMenu(expanded, { expanded = false }) {
                    options.forEach { (choice, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; changed(choice) }) }
                }
            }
        })
}

@Composable
private fun SettingSwitch(label: String, enabled: Boolean, toggle: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { toggle(!enabled) },
        trailingContent = { Switch(enabled, toggle, modifier = Modifier.semantics { contentDescription = label }) })
}
