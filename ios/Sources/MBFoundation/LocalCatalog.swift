import Foundation

public struct LocalAudioMetadata: Codable, Equatable, Sendable {
    public var album: String?
    public var albumArtist: String?
    public var year: String?
    public var trackNumber: Int?
    public var discNumber: Int?
    /// 无专辑作者时按导入文件夹分组；只保存摘要，不保存沙盒外路径。
    public var folderID: String?
    public var lyrics: String?
    public init(album: String? = nil, albumArtist: String? = nil, year: String? = nil,
                trackNumber: Int? = nil, discNumber: Int? = nil, folderID: String? = nil, lyrics: String? = nil) {
        self.album = album; self.albumArtist = albumArtist; self.year = year
        self.trackNumber = trackNumber; self.discNumber = discNumber; self.folderID = folderID; self.lyrics = lyrics
    }
}

public struct LocalCollection: Identifiable, Sendable {
    public let id: String
    public let title: String
    public let subtitle: String
    public let tracks: [Track]
    public var artwork: Track? { tracks.first { $0.artworkURL != nil } ?? tracks.first }
}

/// 专辑和作者由已导入的标签构建；逗号保留在作者名里，与桌面 main 一致。
public struct LocalCatalog: Sendable {
    public let tracks: [Track]
    public let albums: [LocalCollection]
    public let artists: [LocalCollection]
    public init(tracks: [Track]) {
        self.tracks = tracks.sorted { Self.nameOrder($0.title, $1.title, tie: ($0.id, $1.id)) }
        let byAlbum = Dictionary(grouping: tracks.filter { !($0.localMetadata?.album ?? "").isEmpty }) { track in
            let metadata = track.localMetadata
            let owner = metadata?.albumArtist.flatMap { $0.isEmpty ? nil : $0 } ?? metadata?.folderID ?? track.artist
            return (metadata?.album ?? "").lowercased() + "\u{0}" + owner.lowercased()
        }
        albums = byAlbum.map { key, tracks in
            let sorted = tracks.sorted(by: Self.albumOrder)
            let first = sorted[0]
            let credits = Set(sorted.map { $0.artist.lowercased() })
            let artist = first.localMetadata?.albumArtist ?? (credits.count > 1 ? "群星" : first.artist)
            return LocalCollection(id: key, title: first.localMetadata?.album ?? "", subtitle: artist, tracks: sorted)
        }.sorted { Self.nameOrder($0.title, $1.title, tie: ($0.id, $1.id)) }
        var groups: [String: (String, [Track])] = [:]
        for track in self.tracks {
            for artist in Self.artistNames(track.artist) {
                let key = artist.lowercased()
                var entry = groups[key] ?? (artist, [])
                entry.1.append(track); groups[key] = entry
            }
        }
        // 专辑作者也有入口；合辑的每首仍保留实际作者。
        for album in albums {
            for artist in Self.artistNames(album.tracks.first?.localMetadata?.albumArtist ?? "") {
                let key = artist.lowercased()
                var entry = groups[key] ?? (artist, [])
                let known = Set(entry.1.map(\.id))
                entry.1.append(contentsOf: album.tracks.filter { !known.contains($0.id) })
                groups[key] = entry
            }
        }
        artists = groups.map { key, entry in
            LocalCollection(id: key, title: entry.0, subtitle: "\(entry.1.count) 首", tracks: entry.1.sorted {
                let left = $0.localMetadata?.album ?? "", right = $1.localMetadata?.album ?? ""
                if left != right { return Self.nameOrder(left, right, tie: ($0.id, $1.id)) }
                return Self.albumOrder($0, $1)
            })
        }.sorted { Self.nameOrder($0.title, $1.title, tie: ($0.id, $1.id)) }
    }
    public static func matches(_ text: String, query: String) -> Bool {
        query.split(whereSeparator: \.isWhitespace).allSatisfy { text.localizedStandardContains($0) }
    }
    public func search(_ query: String) -> [Track] {
        tracks.filter { Self.matches($0.title + "\n" + $0.artist + "\n" + ($0.localMetadata?.album ?? ""), query: query) }
    }
    public static func artistNames(_ text: String) -> [String] {
        guard let separators = try? NSRegularExpression(pattern: " / |;|、| feat\\. | ft\\. ", options: .caseInsensitive) else { return [text] }
        let parts = text.components(separatedBy: separators)
        var seen = Set<String>()
        return parts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
    }
    private static func albumOrder(_ left: Track, _ right: Track) -> Bool {
        let leftDisc = left.localMetadata?.discNumber ?? 0, rightDisc = right.localMetadata?.discNumber ?? 0
        if leftDisc != rightDisc { return leftDisc < rightDisc }
        let leftTrack = left.localMetadata?.trackNumber ?? 0, rightTrack = right.localMetadata?.trackNumber ?? 0
        if leftTrack != rightTrack { return leftTrack < rightTrack }
        return nameOrder(left.title, right.title, tie: (left.id, right.id))
    }
    private static func nameOrder(_ left: String, _ right: String, tie: (String, String)) -> Bool {
        let order = left.localizedStandardCompare(right)
        return order == .orderedSame ? tie.0 < tie.1 : order == .orderedAscending
    }
}

private extension String {
    func components(separatedBy expression: NSRegularExpression) -> [String] {
        var end = startIndex
        var values: [String] = []
        for match in expression.matches(in: self, range: NSRange(startIndex..., in: self)) {
            guard let range = Range(match.range, in: self) else { continue }
            values.append(String(self[end..<range.lowerBound])); end = range.upperBound
        }
        values.append(String(self[end...])); return values
    }
}
