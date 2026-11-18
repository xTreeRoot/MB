import MBFoundation
import SwiftUI
import UniformTypeIdentifiers

struct PluginsView: View {
    let model: PluginModel
    @State private var importing = false
    @State private var folder = false
    @State private var github = false
    @State private var repo = ""
    var body: some View {
        List {
            ForEach(model.installed) { plugin in
                NavigationLink {
                    PluginSettingsView(model: model, plugin: plugin)
                } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(plugin.manifest.displayName).font(.headline)
                        Text(plugin.manifest.version ?? "").font(.caption).foregroundStyle(.secondary)
                        if let version = model.updates[plugin.id] { Text("可更新至 \(version)").font(.caption).foregroundStyle(.tint) }
                    }
                }
                .swipeActions { Button("卸载", role: .destructive) { Task { await model.remove(plugin) } } }
            }
            Section {
                Button("从 ZIP 安装", systemImage: "doc.zipper") { importing = true }
                Button("从文件夹安装", systemImage: "folder") { folder = true }
                Button("从 GitHub 安装", systemImage: "arrow.down.circle") { github = true }
            }.disabled(model.busy)
            if model.busy { ProgressView("正在检查插件") }
            if let error = model.errorMessage { Text(error).font(.caption).foregroundStyle(.red) }
        }
        .navigationTitle("插件")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip]) { result in
            switch result { case .success(let url): Task { await model.prepare(url) }; case .failure(let error): model.reportError(error) }
        }
        .fileImporter(isPresented: $folder, allowedContentTypes: [.folder]) { result in
            switch result { case .success(let url): Task { await model.prepare(url) }; case .failure(let error): model.reportError(error) }
        }
        .alert("从 GitHub 安装", isPresented: $github) {
            TextField("owner/repository", text: $repo).autocorrectionDisabled()
            Button("取消", role: .cancel) {}
            Button("检查") { Task { await model.prepareGitHub(repo) } }.disabled(repo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .sheet(item: Binding(get: { model.pending }, set: { value in if value == nil { Task { await model.discard() } } })) { pending in
            NavigationStack {
                Form {
                    Section {
                        LabeledContent("名称", value: pending.manifest.displayName)
                        LabeledContent("版本", value: pending.manifest.version ?? "")
                        LabeledContent("宿主兼容版本", value: pending.manifest.minAppVersion ?? "0.1.0")
                    }
                    Section("网络权限") {
                        ForEach(pending.manifest.permissions?.network ?? [], id: \.self) { Text($0).font(.body.monospaced()) }
                    }
                    if let error = model.errorMessage { Text(error).foregroundStyle(.red) }
                }
                .navigationTitle("安装插件")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { Task { await model.discard() } } }
                    ToolbarItem(placement: .confirmationAction) { Button("安装") { Task { await model.install() } }.disabled(model.busy) }
                }
            }
        }
    }
}

private struct PluginSettingsView: View {
    let model: PluginModel
    let plugin: InstalledPlugin
    @FocusState private var editing: Bool
    @State private var values: [String: String] = [:]
    @State private var loaded = false
    @State private var error: String?
    var body: some View {
        Form {
            ForEach(plugin.manifest.settings ?? []) { setting in
                Section {
                    switch setting.type {
                    case "password": SecureField(setting.label, text: binding(setting.key))
                    case "boolean": Toggle(setting.label, isOn: Binding(get: { values[setting.key] == "true" }, set: { values[setting.key] = $0 ? "true" : "false" }))
                    case "select":
                        Picker(setting.label, selection: binding(setting.key)) {
                            Text("未设置").tag("")
                            ForEach(setting.options ?? [], id: \.value) { Text($0.label).tag($0.value) }
                        }
                    default: TextField(setting.label, text: binding(setting.key)).autocorrectionDisabled().focused($editing)
                    }
                    if let link = setting.link, let url = URL(string: link), ["http", "https"].contains(url.scheme ?? "") { Link("查看说明", destination: url) }
                } footer: { if let help = setting.help { Text(help) } }
            }
            Toggle("启用音源", isOn: Binding(get: { !model.disabled.contains(plugin.id) }, set: { enabled in Task { await model.setEnabled(plugin, enabled: enabled) } }))
            if plugin.repository != nil { Button("检查更新") { Task { await model.update(plugin) } }.disabled(model.busy) }
            Section("网络权限") { ForEach(plugin.manifest.permissions?.network ?? [], id: \.self) { Text($0).font(.caption.monospaced()) } }
            Button("保存") { editing = false; Task { await model.saveSettings(values, for: plugin) } }.disabled(!loaded)
            if let error = error ?? model.errorMessage { Text(error).foregroundStyle(.red) }
        }
        .navigationTitle(plugin.manifest.displayName)
        .task {
            do { values = try await model.settings(for: plugin); loaded = true }
            catch { self.error = error.localizedDescription }
        }
    }
    private func binding(_ key: String) -> Binding<String> { Binding(get: { values[key] ?? "" }, set: { values[key] = $0 }) }
}

struct PluginHomeView: View {
    let source: PluginSource
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var body: some View {
        PluginBrowseView(source: source, page: nil, select: select, menu: menu)
    }
}

struct PluginBrowseView: View {
    let source: PluginSource
    let page: String?
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var body: some View { PluginCatalogPage(source: source, request: page.map(PluginPageRequest.browse) ?? .home, select: select, menu: menu) }
}
