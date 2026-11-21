import Foundation

/// 标准磁力链直接成为本地资源；关键词搜索委托外部插件。
public struct TorrentSearchSource: MusicSource {
    public let displayName = "Magnet"
    private let provider: PluginSource?
    public init(provider: PluginSource? = nil) { self.provider = provider }
    public func search(query: String, page: Int) async throws -> TrackPage {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("magnet:") {
            guard let track = Self.directMagnet(query) else { throw PluginFailure.invalid(AppLocalization.text("磁力链接格式无效，请检查完整链接")) }
            return TrackPage(tracks: page <= 1 ? [track] : [], hasMore: false)
        }
        guard let provider else { throw PluginFailure.invalid(AppLocalization.text("请先安装并启用磁力链搜索插件")) }
        return try await provider.searchResources(query: query, page: page)
    }
    public static func directMagnet(_ input: String) -> Track? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.utf8.count <= 16_384, !value.unicodeScalars.contains(where: { $0.value < 32 }), value.lowercased().hasPrefix("magnet:?"),
              let url = URL(string: value), let parts = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let xt = parts.first(where: { $0.name.lowercased() == "xt" && $0.value?.range(of: #"\A(?:urn:btih:(?:[a-f0-9]{40}|[a-z2-7]{32})|urn:btmh:1220[a-f0-9]{64})\z"#, options: [.regularExpression, .caseInsensitive]) != nil })?.value else { return nil }
        let hash = String(xt.split(separator: ":").last ?? "")
        let name = parts.first { $0.name == "dn" }?.value.flatMap { $0.isEmpty ? nil : String($0.prefix(500)) } ?? "磁力资源 · \(hash.prefix(12))…"
        var track = Track(sourceID: "magnet", sourceTrackID: "magnet:" + hash.lowercased(), title: name, artist: "磁力链接")
        track.resourceType = "other"; track.resourceURL = url
        return track
    }
}
