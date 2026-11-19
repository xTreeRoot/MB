import Foundation
import Observation

public enum PluginPreparation: Equatable {
    case checking
    case failed(String)
}

@MainActor @Observable public final class PluginModel {
    public let ui = PluginUIService()
    public private(set) var installed: [InstalledPlugin] = []
    public private(set) var sources: [String: PluginSource] = [:]
    public private(set) var pending: PendingPlugin?
    public private(set) var preparation: PluginPreparation?
    public var busy: Bool { installing || preparation == .checking }
    public private(set) var errorMessage: String?
    public private(set) var revision = 0
    public private(set) var disabled: Set<String> = []
    public private(set) var updates: [String: String] = [:]
    @ObservationIgnored private var checking = false
    @ObservationIgnored private let installer: PluginInstaller
    @ObservationIgnored private let githubPreparation: (PluginInstaller, String) async throws -> PendingPlugin
    private var installing = false
    public convenience init(installer: PluginInstaller = PluginInstaller()) {
        self.init(installer: installer) { try await $0.prepareGitHub($1) }
    }
    package init(installer: PluginInstaller, githubPreparation: @escaping (PluginInstaller, String) async throws -> PendingPlugin) {
        self.installer = installer; self.githubPreparation = githubPreparation
    }
    public func reportError(_ error: any Error) { errorMessage = error.localizedDescription }
    public func restore() async {
        do {
            installed = try await installer.installed()
            disabled = try await installer.disabled()
            sources = sources.filter { !disabled.contains($0.key) }
            for plugin in installed where sources[plugin.id] == nil && !disabled.contains(plugin.id) { sources[plugin.id] = try await source(plugin) }
            revision += 1
        } catch { errorMessage = error.localizedDescription }
    }
    public func prepare(_ url: URL) async {
        await prepare {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            return try await installer.prepare(url)
        }
    }
    public func prepareGitHub(_ repo: String) async {
        await prepare { try await githubPreparation(installer, repo) }
    }
    private func prepare(using operation: () async throws -> PendingPlugin) async {
        guard !busy, pending == nil else { return }
        preparation = .checking; errorMessage = nil
        do {
            pending = try await operation()
            preparation = nil
        } catch {
            preparation = .failed(error.localizedDescription)
        }
    }
    public func dismissPreparationError() {
        if case .failed = preparation { preparation = nil }
    }
    public func install() async {
        guard let pending, !busy else { return }; installing = true; errorMessage = nil
        defer { installing = false }
        do { let plugin = try await installer.install(pending); if !disabled.contains(plugin.id) { sources[plugin.id] = try await source(plugin) }; self.pending = nil; await restore() }
        catch { errorMessage = error.localizedDescription }
    }
    public func discard() async { if let pending { await installer.discard(pending) }; pending = nil }
    public func setEnabled(_ plugin: InstalledPlugin, enabled: Bool) async {
        do { try await installer.setEnabled(plugin.id, enabled: enabled); await restore() }
        catch { errorMessage = error.localizedDescription }
    }
    public func update(_ plugin: InstalledPlugin) async { if let repo = plugin.repository { await prepareGitHub(repo) } }
    public func checkUpdates() async {
        guard !checking else { return }; checking = true; defer { checking = false }
        if let values = try? await installer.checkUpdates() {
            updates = values.filter { id, version in
                installed.first(where: { $0.id == id }).map { PluginInstaller.isNewer(version, than: $0.manifest.version ?? "0.0.0") } == true
            }
        }
    }
    public func remove(_ plugin: InstalledPlugin) async {
        do { try await installer.remove(plugin); sources[plugin.id] = nil; installed.removeAll { $0.id == plugin.id }; revision += 1 }
        catch { errorMessage = error.localizedDescription }
    }
    public func settings(for plugin: InstalledPlugin) async throws -> [String: String] { try await installer.settings(for: plugin) }
    public func saveSettings(_ settings: [String: String], for plugin: InstalledPlugin) async {
        do { try await installer.saveSettings(settings, for: plugin); await sources[plugin.id]?.runtime.updateSettings(settings); errorMessage = nil }
        catch { errorMessage = error.localizedDescription }
    }
    private func source(_ plugin: InstalledPlugin) async throws -> PluginSource {
        let settings = try await installer.settings(for: plugin)
        return PluginSource(manifest: plugin.manifest, runtime: try PluginRuntime(directory: plugin.directory, dataDirectory: plugin.dataDirectory, manifest: plugin.manifest, settings: settings, webSignIn: { [ui] request in try await ui.show(request) }, openURL: { [ui] url, title in await ui.open(url, title: title) }))
    }
}
