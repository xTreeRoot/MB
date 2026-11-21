import AVFoundation
import CryptoKit
import Foundation

/// 文件读取、内容摘要和标签解析在 actor 内执行，避免导入大文件阻塞主线程。
public actor LocalAudioImporter {
    private let library: MediaLibrary
    public init(library: MediaLibrary) { self.library = library }
    public func importFile(_ url: URL) async throws -> Track {
        let asset = AVURLAsset(url: url)
        guard try await asset.load(.isPlayable) else { throw MediaStreamingFailure.unplayable }
        let duration = try await asset.load(.duration).seconds
        let common = try await asset.load(.commonMetadata)
        let all = try await asset.load(.metadata)
        let identifier = try contentID(url)
        let title = await string(common, key: .commonKeyTitle) ?? url.deletingPathExtension().lastPathComponent
        let artist = await string(common, key: .commonKeyArtist) ?? "未知作者"
        let album = await string(common, key: .commonKeyAlbumName)
        let albumArtist = await string(all, identifiers: [.iTunesMetadataAlbumArtist, .id3MetadataBand])
        let year = await string(common, key: .commonKeyCreationDate)
        let embeddedLyrics = await string(all, identifiers: [.iTunesMetadataLyrics, .id3MetadataUnsynchronizedLyric])
        let sidecar = url.deletingPathExtension().appendingPathExtension("lrc")
        let lyrics = (try? readLyrics(sidecar)) ?? embeddedLyrics
        let trackNumber = await number(all, identifiers: [.iTunesMetadataTrackNumber, .id3MetadataTrackNumber])
        let discNumber = await number(all, identifiers: [.iTunesMetadataDiscNumber, .id3MetadataPartOfASet])
        var artwork: URL?
        if let item = common.first(where: { $0.commonKey == .commonKeyArtwork }), let data = try await item.load(.dataValue), !data.isEmpty {
            artwork = try await library.saveArtwork(data)
        }
        var track = Track(sourceID: "local", sourceTrackID: identifier, title: title, artist: artist, artworkURL: artwork,
            durationMilliseconds: duration.isFinite && duration > 0 && duration < Double(Int64.max) / 1_000 ? Int64(duration * 1_000) : 0)
        let folderID = SHA256.hash(data: Data(url.deletingLastPathComponent().resolvingSymlinksInPath().path.utf8)).map { String(format: "%02x", $0) }.joined()
        track.localFileID = identifier
        track.localMetadata = LocalAudioMetadata(album: album, albumArtist: albumArtist, year: year,
            trackNumber: trackNumber, discNumber: discNumber, folderID: folderID, lyrics: lyrics)
        try Task.checkCancellation()
        try await library.saveDownload(file: url, track: track)
        return track
    }
    public func audioFiles(in folder: URL) throws -> [URL] {
        guard let files = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey], options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { throw CocoaError(.fileReadNoPermission) }
        let extensions = Set(["m4a", "aac", "mp3", "mp4", "flac", "wav", "aiff", "aif", "caf", "ogg"])
        var result: [URL] = []
        for case let file as URL in files {
            try Task.checkCancellation()
            let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            if values.isSymbolicLink == true { continue }
            if values.isRegularFile == true, extensions.contains(file.pathExtension.lowercased()) { result.append(file) }
        }
        return result.sorted { $0.path < $1.path }
    }
    public func attachLyrics(_ url: URL, to track: Track) async throws {
        var updated = track
        var metadata = track.localMetadata ?? LocalAudioMetadata()
        metadata.lyrics = try readLyrics(url)
        updated.localMetadata = metadata
        try await library.updateTrack(updated)
    }
    private func readLyrics(_ url: URL) throws -> String {
        let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard bytes > 0, bytes <= 2 * 1_024 * 1_024 else { throw NetworkFailure.response }
        let data = try Data(contentsOf: url)
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16),
              !CaptionTimeline.lrc(text).lines.isEmpty else { throw LocalAudioImportFailure.untimedLyrics }
        return text
    }
    private func contentID(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hash = SHA256()
        while let data = try handle.read(upToCount: 1_024 * 1_024), !data.isEmpty {
            try Task.checkCancellation(); hash.update(data: data)
        }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    private func string(_ items: [AVMetadataItem], key: AVMetadataKey) async -> String? {
        guard let item = items.first(where: { $0.commonKey == key }) else { return nil }
        return await text(item)
    }
    private func string(_ items: [AVMetadataItem], identifiers: [AVMetadataIdentifier]) async -> String? {
        for identifier in identifiers {
            if let item = items.first(where: { $0.identifier == identifier }), let value = await text(item) { return value }
        }
        return nil
    }
    private func text(_ item: AVMetadataItem) async -> String? {
        guard let value = try? await item.load(.stringValue)?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value
    }
    private func number(_ items: [AVMetadataItem], identifiers: [AVMetadataIdentifier]) async -> Int? {
        if let text = await string(items, identifiers: identifiers), let prefix = text.split(separator: "/").first, let value = Int(prefix), value > 0 { return value }
        for item in items where item.identifier.map(identifiers.contains) == true {
            if let value = try? await item.load(.numberValue)?.intValue, value > 0 { return value }
            // iTunes trkn/disk 使用大端整数，前两个字节保留、接着两个字节为序号。
            if let bytes = try? await item.load(.dataValue), bytes.count >= 4 {
                let value = Int(bytes[2]) << 8 | Int(bytes[3]); if value > 0 { return value }
            }
        }
        return nil
    }
}

private enum LocalAudioImportFailure: LocalizedError {
    case untimedLyrics
    var errorDescription: String? { AppLocalization.text("歌词文件需要包含 [00:00.00] 格式的时间标签") }
}
