import AVFoundation
import Foundation
import Observation

@MainActor @Observable
public final class DownloadModel {
    public private(set) var items: [SavedMedia] = []
    public private(set) var downloading: [String: String] = [:]
    public private(set) var errorMessage: String?
    public private(set) var cacheBytes: Int64 = 0
    public private(set) var importProgress: String?
    @ObservationIgnored private let source: (any PlaybackSource)?
    @ObservationIgnored public let library: MediaLibrary
    @ObservationIgnored private var tasks: [String: Task<Void, Never>] = [:]
    public init(source: (any PlaybackSource)?, library: MediaLibrary = .shared) { self.source = source; self.library = library }
    public func refresh() async {
        do { items = try await library.list(); cacheBytes = try await library.cacheBytes() }
        catch { errorMessage = error.localizedDescription }
    }
    public func download(_ track: Track) {
        guard tasks[track.id] == nil, let source else { return }
        downloading[track.id] = track.title; errorMessage = nil
        tasks[track.id] = Task {
            defer { downloading[track.id] = nil; tasks[track.id] = nil }
            do {
                let resource = try await source.audio(track: track)
                let file = try await NetworkClient.shared.download(resource)
                defer { try? FileManager.default.removeItem(at: file) }
                try Task.checkCancellation()
                try await library.saveDownload(file: file, track: track)
                await refresh()
            } catch { if !Task.isCancelled { errorMessage = AppLocalization.text("下载失败：%@", arguments: [error.localizedDescription]) } }
        }
    }
    public func cancel(_ id: String) { tasks[id]?.cancel() }
    public func remove(_ item: SavedMedia) async {
        do { try await library.remove(item); await refresh() }
        catch { errorMessage = error.localizedDescription }
    }
    public func clearCache() async {
        do { try await library.clearCache(); await refresh() }
        catch { errorMessage = error.localizedDescription }
    }
    public func export(_ item: SavedMedia) async -> URL? {
        do { return try await library.export(item) }
        catch { errorMessage = error.localizedDescription; return nil }
    }
    public func importFiles(_ urls: [URL]) async {
        guard importProgress == nil else { return }
        errorMessage = nil
        let importer = LocalAudioImporter(library: library)
        defer { importProgress = nil }
        for url in urls {
            if Task.isCancelled { break }
            importProgress = url.lastPathComponent
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            do {
                _ = try await importer.importFile(url)
            } catch { errorMessage = AppLocalization.text("导入失败：%@", arguments: [error.localizedDescription]) }
        }
        await refresh()
    }
    public func reportImportError(_ error: any Error) { errorMessage = AppLocalization.text("导入失败：%@", arguments: [error.localizedDescription]) }
    public func importFolder(_ url: URL) async {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let urls = try await LocalAudioImporter(library: library).audioFiles(in: url)
            guard !urls.isEmpty else { errorMessage = "文件夹里没有支持的音频文件"; return }
            await importFiles(urls)
        } catch { reportImportError(error) }
    }
    public func attachLyrics(_ url: URL, to track: Track) async {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do { try await LocalAudioImporter(library: library).attachLyrics(url, to: track); errorMessage = nil; await refresh() }
        catch { errorMessage = AppLocalization.text("歌词导入失败：%@", arguments: [error.localizedDescription]) }
    }
}
