import Foundation
import ZIPFoundation

public struct InstalledPlugin: Identifiable, Sendable {
    public let manifest: PluginManifest
    public let directory: URL
    public let dataDirectory: URL
    public var repository: String? = nil
    var revision: String? = nil
    public var id: String { manifest.id }
}
public struct PendingPlugin: Identifiable, Sendable {
    public let id: UUID
    public let manifest: PluginManifest
    let directory: URL
    var repository: String? = nil
    var revision: String? = nil
}

struct PluginUpdateCheck: Codable, Sendable {
    let checked: Date
    let versions: [String: String]
    let failed: [String]
}

/// 安装先在临时目录检查，确认后原子替换代码，账号与设置留在独立数据目录。
public actor PluginInstaller {
    private struct Origin: Codable { let repository: String?; let revision: String? }
    private struct Release: Sendable {
        let revision: String
        let address: URL
        let isCommit: Bool
        var label: String { isCommit ? String(revision.prefix(12)) : revision }
    }
    private let root: URL
    public init(directory: URL? = nil) {
        root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MBMobile/plugins")
    }
    public func installed() throws -> [InstalledPlugin] {
        let folder = root.appendingPathComponent("installed")
        guard FileManager.default.fileExists(atPath: folder.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { !$0.lastPathComponent.hasPrefix(".") }.map { directory in
            let manifest = try readManifest(directory)
            guard directory.lastPathComponent == manifest.id else { throw PluginFailure.invalid("Plugin folder ID mismatch") }
            let data = root.appendingPathComponent("data/" + manifest.id)
            let originFile = directory.appendingPathComponent(".mb-origin.json")
            let origin: Origin
            if let bytes = try? Data(contentsOf: originFile) { origin = try JSONDecoder().decode(Origin.self, from: bytes) }
            else { origin = Origin(repository: try? String(contentsOf: data.appendingPathComponent("repository.txt"), encoding: .utf8), revision: nil) }
            return InstalledPlugin(manifest: manifest, directory: directory, dataDirectory: data, repository: origin.repository, revision: origin.revision)
        }.sorted { $0.manifest.displayName.localizedStandardCompare($1.manifest.displayName) == .orderedAscending }
    }
    public func prepare(_ input: URL) throws -> PendingPlugin {
        let id = UUID()
        let staging = root.appendingPathComponent("pending/" + id.uuidString)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            let values = try input.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw PluginFailure.invalid("Symbolic links are not supported") }
            if values.isDirectory == true { try copyDirectory(input, to: staging) }
            else {
                guard (try input.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 64 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin archive exceeds 64 MB") }
                try unpack(input, into: staging)
            }
            let directory = try findRoot(staging)
            let manifest = try readManifest(directory)
            return PendingPlugin(id: id, manifest: manifest, directory: directory)
        } catch { try? FileManager.default.removeItem(at: staging); throw error }
    }
    public static func repository(fromInstallURL url: URL) -> String? {
        guard ["mb", "mygo-music"].contains(url.scheme?.lowercased() ?? ""), url.host == "install-plugin",
              let values = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              values.filter({ $0.name == "repo" }).count == 1,
              let repo = values.first(where: { $0.name == "repo" })?.value,
              repo.range(of: "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", options: .regularExpression) != nil,
              !repo.contains("..") else { return nil }
        return repo
    }
    public func prepareGitHub(_ input: String) async throws -> PendingPlugin {
        let repo: String
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("https://github.com/"), let url = URL(string: text) { repo = url.path.split(separator: "/").prefix(2).joined(separator: "/") }
        else { repo = text }
        guard repo.range(of: "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", options: .regularExpression) != nil, !repo.contains("..") else { throw PluginFailure.invalid("Use owner/repository or a GitHub repository URL") }
        return try await prepareGitHub(repo, release: latest(repo))
    }

    private func latest(_ repo: String) async throws -> Release {
        guard repo.range(of: "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", options: .regularExpression) != nil, !repo.contains("..") else { throw NetworkFailure.response }
        func github(_ address: String) async throws -> (Data, HTTPURLResponse) {
            guard let url = URL(string: address) else { throw NetworkFailure.response }
            var request = URLRequest(url: url)
            request.setValue("MB-iOS", forHTTPHeaderField: "User-Agent")
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            let (data, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 2 * 1_024 * 1_024)
            guard let response = response as? HTTPURLResponse else { throw NetworkFailure.response }
            return (data, response)
        }
        let (data, response) = try await github("https://api.github.com/repos/" + repo + "/releases/latest")
        if response.statusCode == 404 {
            let (data, response) = try await github("https://api.github.com/repos/" + repo + "/commits/HEAD")
            guard response.statusCode == 200 else { throw NetworkFailure.http(response.statusCode) }
            let value = try JSONDecoder().decode(PluginJSON.self, from: data)
            guard let sha = value["sha"].string, sha.range(of: "^[0-9a-f]{40}$", options: .regularExpression) != nil,
                  let url = URL(string: "https://api.github.com/repos/" + repo + "/zipball/" + sha) else { throw NetworkFailure.response }
            return Release(revision: sha, address: url, isCommit: true)
        }
        guard response.statusCode == 200 else { throw NetworkFailure.http(response.statusCode) }
        let value = try JSONDecoder().decode(PluginJSON.self, from: data)
        guard let tag = value["tag_name"].string, !tag.isEmpty, let zip = value["zipball_url"].string,
              let archive = URL(string: zip), archive.scheme == "https", archive.host == "api.github.com" else { throw NetworkFailure.response }
        let assets = value["assets"].array
        if let index = Self.assetIndex(assets.map { $0["name"].string ?? "" }), let asset = assets[index]["browser_download_url"].string,
           let url = URL(string: asset), url.scheme == "https", url.host == "github.com" {
            return Release(revision: tag, address: url, isCommit: false)
        }
        return Release(revision: tag, address: archive, isCommit: false)
    }

    private func prepareGitHub(_ repo: String, release: Release) async throws -> PendingPlugin {
        var request = URLRequest(url: release.address)
        request.setValue("MB-iOS", forHTTPHeaderField: "User-Agent")
        let (bytes, result) = try await NetworkClient.shared.data(for: request, maximumBytes: 64 * 1_024 * 1_024)
        guard let response = result as? HTTPURLResponse, response.statusCode == 200 else { throw NetworkFailure.response }
        guard bytes.count <= 64 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin archive exceeds 64 MB") }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip")
        defer { try? FileManager.default.removeItem(at: file) }
        try bytes.write(to: file)
        var pending = try prepare(file); pending.repository = repo; pending.revision = release.revision; return pending
    }

    private func offersUpdate(_ release: Release, for plugin: InstalledPlugin) -> Bool {
        if release.isCommit { return release.revision != plugin.revision }
        if let revision = plugin.revision, revision.range(of: "^[0-9a-f]{40}$", options: .regularExpression) != nil { return true }
        return Self.isNewer(release.revision, than: plugin.revision ?? plugin.manifest.version ?? "0.0.0")
    }

    func prepareUpdate(_ plugin: InstalledPlugin) async throws -> PendingPlugin? {
        guard let repo = plugin.repository else { return nil }
        let release = try await latest(repo)
        guard offersUpdate(release, for: plugin) else { return nil }
        let pending = try await prepareGitHub(repo, release: release)
        guard pending.manifest.id == plugin.id else {
            discard(pending)
            throw PluginFailure.invalid(AppLocalization.text("更新包的插件 ID 不匹配"))
        }
        return pending
    }
    static func assetIndex(_ names: [String]) -> Int? {
        var general: Int?
        for (index, name) in names.enumerated() {
            let name = name.lowercased()
            guard name.hasSuffix(".zip") else { continue }
            if name.contains("ios") && name.contains("arm64") { return index }
            if !["darwin", "macos", "windows", "linux", "android", "ios"].contains(where: name.contains), general == nil { general = index }
        }
        return general
    }
    public func install(_ pending: PendingPlugin) throws -> InstalledPlugin {
        try install(pending, requireSameRepository: false)
    }

    func install(_ pending: PendingPlugin, requireSameRepository: Bool) throws -> InstalledPlugin {
        let manifest = try readManifest(pending.directory)
        guard manifest.id == pending.manifest.id else { throw PluginFailure.invalid("Plugin changed during installation") }
        let folder = root.appendingPathComponent("installed"), destination = folder.appendingPathComponent(manifest.id)
        let data = root.appendingPathComponent("data/" + manifest.id)
        if requireSameRepository, let previous = try installed().first(where: { $0.id.caseInsensitiveCompare(manifest.id) == .orderedSame }) {
            guard previous.id == manifest.id, let repository = pending.repository,
                  previous.repository?.caseInsensitiveCompare(repository) == .orderedSame else {
                throw PluginFailure.invalid(AppLocalization.text("该插件 ID 已被其他来源占用"))
            }
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        let origin = Origin(repository: pending.repository, revision: pending.revision)
        try JSONEncoder().encode(origin).write(to: pending.directory.appendingPathComponent(".mb-origin.json"), options: .atomic)
        if FileManager.default.fileExists(atPath: destination.path) { _ = try FileManager.default.replaceItemAt(destination, withItemAt: pending.directory) }
        else { try FileManager.default.moveItem(at: pending.directory, to: destination) }
        discard(pending)
        return InstalledPlugin(manifest: manifest, directory: destination, dataDirectory: data, repository: origin.repository, revision: origin.revision)
    }
    public func discard(_ pending: PendingPlugin) { try? FileManager.default.removeItem(at: root.appendingPathComponent("pending/" + pending.id.uuidString)) }
    public func remove(_ plugin: InstalledPlugin) throws { try FileManager.default.removeItem(at: plugin.directory) }
    func checkUpdates(force: Bool, now: Date = Date()) async throws -> PluginUpdateCheck {
        let file = root.appendingPathComponent("updates.json")
        if !force, let data = try? Data(contentsOf: file), let state = try? JSONDecoder().decode(PluginUpdateCheck.self, from: data), now.timeIntervalSince(state.checked) < 86_400 { return state }
        var found: [String: String] = [:]
        var failed: [String] = []
        for plugin in try installed() {
            guard let repo = plugin.repository else { continue }
            do {
                let release = try await latest(repo)
                if offersUpdate(release, for: plugin) { found[plugin.id] = release.label }
            } catch { try Task.checkCancellation(); failed.append(plugin.id) }
        }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let result = PluginUpdateCheck(checked: now, versions: found, failed: failed)
        try JSONEncoder().encode(result).write(to: file, options: .atomic)
        return result
    }
    static func isNewer(_ version: String, than current: String) -> Bool {
        let version = version.hasPrefix("v") ? String(version.dropFirst()) : version
        let current = current.hasPrefix("v") ? String(current.dropFirst()) : current
        return version.compare(current, options: .numeric) == .orderedDescending
    }
    public func disabled() throws -> Set<String> {
        let file = root.appendingPathComponent("disabled.json")
        return FileManager.default.fileExists(atPath: file.path) ? try JSONDecoder().decode(Set<String>.self, from: Data(contentsOf: file)) : []
    }
    public func setEnabled(_ id: String, enabled: Bool) throws {
        var values = try disabled()
        if enabled { values.remove(id) } else { values.insert(id) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try JSONEncoder().encode(values).write(to: root.appendingPathComponent("disabled.json"), options: .atomic)
    }
    public func settings(for plugin: InstalledPlugin) throws -> [String: String] {
        let file = plugin.dataDirectory.appendingPathComponent("settings.json")
        return FileManager.default.fileExists(atPath: file.path) ? try JSONDecoder().decode([String: String].self, from: Data(contentsOf: file)) : [:]
    }
    public func saveSettings(_ settings: [String: String], for plugin: InstalledPlugin) throws {
        try FileManager.default.createDirectory(at: plugin.dataDirectory, withIntermediateDirectories: true)
        let bytes = try JSONEncoder().encode(settings), file = plugin.dataDirectory.appendingPathComponent("settings.json")
        #if os(iOS)
        try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try bytes.write(to: file, options: .atomic)
        #endif
    }
    private func readManifest(_ directory: URL) throws -> PluginManifest {
        let bytes = try Data(contentsOf: directory.appendingPathComponent("plugin.json"))
        guard bytes.count <= 1_024 * 1_024 else { throw PluginFailure.invalid("Manifest too large") }
        let manifest = try JSONDecoder().decode(PluginManifest.self, from: bytes)
        try manifest.validate()
        let entry = directory.appendingPathComponent(manifest.entry).resolvingSymlinksInPath()
        guard entry.path.hasPrefix(directory.resolvingSymlinksInPath().path + "/"), FileManager.default.fileExists(atPath: entry.path) else { throw PluginFailure.invalid("Missing plugin entry") }
        return manifest
    }
    private func findRoot(_ folder: URL) throws -> URL {
        if FileManager.default.fileExists(atPath: folder.appendingPathComponent("plugin.json").path) { return folder }
        let folders = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { !$0.lastPathComponent.hasPrefix(".") && $0.lastPathComponent != "__MACOSX" }
        guard folders.count == 1, FileManager.default.fileExists(atPath: folders[0].appendingPathComponent("plugin.json").path) else { throw PluginFailure.invalid("Archive must contain one plugin folder with plugin.json") }
        return folders[0]
    }
    private func copyDirectory(_ input: URL, to folder: URL) throws {
        let input = input.resolvingSymlinksInPath()
        guard let enumerator = FileManager.default.enumerator(at: input, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey], options: [.skipsHiddenFiles]) else { throw CocoaError(.fileReadNoPermission) }
        var count = 0, bytes = 0
        for case let file as URL in enumerator {
            try Task.checkCancellation(); count += 1
            let values = try file.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey])
            guard values.isSymbolicLink != true else { throw PluginFailure.invalid("Symbolic links are not supported") }
            bytes += values.fileSize ?? 0
            guard count <= 10_000, bytes <= 128 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin directory exceeds limits") }
            let relative = String(file.resolvingSymlinksInPath().path.dropFirst(input.path.count + 1)), target = folder.appendingPathComponent(relative)
            if values.isDirectory == true { try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true) }
            else { try FileManager.default.copyItem(at: file, to: target) }
        }
    }
    private func unpack(_ input: URL, into folder: URL) throws {
        let archive = try Archive(url: input, accessMode: .read)
        var count = 0, bytes: UInt64 = 0
        for entry in archive {
            try Task.checkCancellation(); count += 1
            let path = entry.path, components = path.split(separator: "/", omittingEmptySubsequences: false)
            guard !path.hasPrefix("/"), !path.contains("\\"), !components.contains(".."), !path.contains("\0"), entry.type != .symlink else { throw PluginFailure.invalid("Unsafe archive entry") }
            bytes += entry.uncompressedSize
            guard count <= 10_000, bytes <= 128 * 1_024 * 1_024 else { throw PluginFailure.invalid("Plugin archive exceeds limits") }
            if components.contains(where: { $0.hasPrefix(".") || $0 == "__MACOSX" }) { continue }
            let target = folder.appendingPathComponent(path)
            guard target.standardizedFileURL.path.hasPrefix(folder.standardizedFileURL.path + "/") else { throw PluginFailure.invalid("Archive path outside destination") }
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            let checksum = try archive.extract(entry, to: target)
            guard checksum == entry.checksum else { throw PluginFailure.invalid("Archive checksum mismatch") }
        }
    }
}
