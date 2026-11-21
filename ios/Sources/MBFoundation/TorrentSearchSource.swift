import Foundation

/// Knaben 开源服务 v2：仅搜索元数据与链接，不把种子当成可播放音频。
/// 合同：https://knaben.org/api/v2/；上游：https://github.com/knaben-database/knaben-api
public struct TorrentSearchSource: MusicSource {
    public let displayName = "Knaben"
    public init() {}
    public func search(query: String, page: Int) async throws -> TrackPage {
        var url = URLComponents(string: "https://api.knaben.org/v2/search")!
        url.queryItems = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "o", value: "seeders"),
                          URLQueryItem(name: "s", value: "30"), URLQueryItem(name: "f", value: String(max(0, page - 1) * 30))]
        var request = URLRequest(url: url.url!); request.setValue("MB/1", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 4 << 20,
            acceptingBody: { $0 == 200 }, permitting: { $0.scheme == "https" && $0.host == "api.knaben.org" })
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw NetworkFailure.response }
        return try Self.decode(data, page: page)
    }
    static func decode(_ data: Data, page: Int) throws -> TrackPage {
        struct Hit: Decodable {
            let id: String; let title: String; let tracker: String?; let category: String?
            let magnetUrl: String?; let link: String?; let details: String?; let bytes: Int64?; let seeders: Int?
        }
        struct Response: Decodable { struct Total: Decodable { let value: Int }; let hits: [Hit]; let total: Total }
        let response = try JSONDecoder().decode(Response.self, from: data)
        let tracks = response.hits.compactMap { hit -> Track? in
            let resource = [hit.magnetUrl, hit.link].compactMap { $0.flatMap(URL.init(string:)) }.first {
                ($0.scheme == "magnet" && URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.contains(where: { $0.name == "xt" && $0.value?.hasPrefix("urn:btih:") == true }) == true) || ($0.scheme == "https" && $0.host != nil)
            }
            guard let resource, !hit.title.isEmpty else { return nil }
            var track = Track(sourceID: "knaben", sourceTrackID: hit.id, title: hit.title,
                artist: [hit.tracker ?? "Knaben", hit.bytes.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) }, hit.seeders.map { "\($0) seeders" }].compactMap { $0 }.joined(separator: " · "),
                pageURL: hit.details.flatMap(URL.init(string:)).flatMap { $0.scheme == "https" ? $0 : nil })
            let category = (hit.category ?? "").lowercased()
            track.resourceType = category.contains("audio") ? "music" : ["video", "movie", "tv", "anime"].contains(where: category.contains) ? "video" : category.contains("book") ? "book" : ["pc", "console", "software"].contains(where: category.contains) ? "software" : "other"
            track.resourceURL = resource; return track
        }
        return TrackPage(tracks: tracks, hasMore: !response.hits.isEmpty && page * 30 < response.total.value)
    }
}
