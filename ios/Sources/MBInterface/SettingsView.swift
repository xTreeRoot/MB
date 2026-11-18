import MBFoundation
import SwiftUI

struct SettingsView: View {
    @Bindable var preferences: PreferencesModel
    let network: NetworkSettingsModel
    let plugins: PluginModel
    var body: some View {
        NavigationStack {
            Form {
                Section("显示") {
                    Picker("语言", selection: $preferences.values.language) {
                        ForEach(AppLanguage.allCases) { language in Text(LocalizedStringKey(language.title)).tag(language) }
                    }
                    Picker("外观", selection: $preferences.values.appearance) {
                        ForEach(AppAppearance.allCases) { mode in Text(LocalizedStringKey(mode.title)).tag(mode) }
                    }
                }
                Section("播放") {
                    Picker("音质", selection: $preferences.values.quality) {
                        ForEach(AudioQuality.allCases) { quality in Text(LocalizedStringKey(quality.title)).tag(quality) }
                    }
                    Picker("响度标准化", selection: $preferences.values.normalization) {
                        ForEach(AudioNormalization.allCases) { mode in Text(LocalizedStringKey(mode.title)).tag(mode) }
                    }
                    Slider(value: $preferences.values.volume, in: 0...1) { Text("应用音量") }
                    HStack { Text("应用音量"); Spacer(); Text("\(Int(preferences.values.volume * 100))%").monospacedDigit().foregroundStyle(.secondary) }
                }
                Section("缓存") {
                    Picker("缓存上限", selection: $preferences.values.cacheMegabytes) {
                        Text("不缓存").tag(0)
                        ForEach([256, 512, 1_024, 2_048, 4_096, 8_192], id: \.self) { size in Text("\(size) MB").tag(size) }
                    }
                    Text("播放缓存按最近使用清理，已下载和导入的音频单独保留。").font(.caption).foregroundStyle(.secondary)
                }
                Section("播放界面") {
                    Toggle("默认打开歌词", isOn: $preferences.values.lyricsStage)
                    Toggle("间奏显示热评", isOn: $preferences.values.hotComments)
                    Toggle("进度条烟雾", isOn: $preferences.values.smoke)
                    Toggle("低音雾气背景", isOn: $preferences.values.fog)
                    Toggle("从封面展开", isOn: $preferences.values.growFromCover)
                    Toggle("歌词火花", isOn: $preferences.values.stageSparks)
                    Picker("播放器样式", selection: $preferences.values.playerStyle) {
                        ForEach(PlayerVisualStyle.allCases) { style in Text(LocalizedStringKey(style.title)).tag(style) }
                    }
                    Picker("歌词舞台", selection: $preferences.values.lyricStyle) {
                        ForEach(LyricVisualStyle.allCases) { style in Text(LocalizedStringKey(style.title)).tag(style) }
                    }
                }
                Section {
                    Toggle("启用内置音源", isOn: $preferences.values.builtInEnabled)
                    NavigationLink("插件") { PluginsView(model: plugins) }
                    NavigationLink("网络与系统代理") { NetworkSettingsView(model: network) }
                    Button("清空搜索记录", role: .destructive) { preferences.values.searchHistory = [] }
                }
                if let error = preferences.errorMessage { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red) }
            }
            .navigationTitle("设置")
        }.id(preferences.values.language)
    }
}
