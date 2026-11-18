import Foundation
import MBFoundation

extension BilibiliSource {
    public func home() async throws -> [DiscoverySection] {
        let sections = await withTaskGroup(of: DiscoverySection.self) { group in
            for category in DiscoveryCategory.allCases {
                group.addTask {
                    do {
                        let result = try await self.loadDiscovery(category, freshPages: 1)
                        return DiscoverySection(category: category, tracks: Array(result.tracks.prefix(6)), subtitle: result.subtitle)
                    } catch {
                        return DiscoverySection(category: category, tracks: [], errorMessage: "此分区加载失败，请刷新重试")
                    }
                }
            }
            var results: [DiscoverySection] = []
            for await section in group { results.append(section) }
            return DiscoveryCategory.allCases.compactMap { category in results.first { $0.category == category } }
        }
        try Task.checkCancellation()
        guard sections.contains(where: { !$0.tracks.isEmpty }) else { throw BilibiliError.noContent }
        return sections
    }

    public func discovery(_ category: DiscoveryCategory) async throws -> DiscoverySection {
        try await loadDiscovery(category, freshPages: 2)
    }

    private func loadDiscovery(_ category: DiscoveryCategory, freshPages: Int) async throws -> DiscoverySection {
        switch category {
        case .recommended:
            let data: RecommendedDTO = try await fetch(path: "/x/web-interface/wbi/index/top/feed/rcmd", wbi: true)
            return DiscoverySection(category: category, tracks: Self.tracks((data.item ?? []).filter { $0.goto == "av" }))
        case .chart:
            let data: ChartPeriodsDTO = try await fetch(path: "/x/copyright-music-publicity/toplist/all_period", parameters: ["list_type": "1"])
            guard let latest = data.list?.values.flatMap({ $0 }).max(by: { $0.ID.number < $1.ID.number }) else {
                return DiscoverySection(category: category, tracks: [])
            }
            let songs: ChartSongsDTO = try await fetch(path: "/x/copyright-music-publicity/toplist/music_list", parameters: ["list_id": latest.ID.value])
            return DiscoverySection(category: category, tracks: Self.tracks((songs.list ?? []).map { $0.video }),
                                    subtitle: latest.priod.map { "第 \($0.number) 周" })
        case .trending:
            let data: RankingDTO = try await fetch(path: "/x/web-interface/ranking/v2", parameters: ["rid": "1003", "type": "all"])
            return DiscoverySection(category: category, tracks: Self.tracks(data.list ?? []))
        case .fresh:
            let headers = try await discoveryHeaders()
            var videos: [VideoDTO] = []
            for page in 1...max(1, freshPages) {
                let data: FreshDTO = try await fetch(path: "/x/web-interface/region/feed/rcmd", parameters: [
                    "display_id": String(page), "request_cnt": "20", "from_region": "1003",
                ], headers: headers)
                videos += (data.archives ?? []).filter { $0.goto == nil || $0.goto == "av" }.map(\.video)
            }
            return DiscoverySection(category: category, tracks: Self.tracks(videos))
        }
    }

    private func discoveryHeaders() async throws -> [String: String] {
        if visitorTask == nil {
            visitorTask = Task {
                let visitor: VisitorDTO = try await self.fetch(path: "/x/frontend/finger/spi")
                guard let id = visitor.b_3, !id.isEmpty else { throw BilibiliError.invalidResponse }
                let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
                let fourth = (visitor.b_4 ?? "").addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
                return "buvid3=\(id); buvid4=\(fourth); b_nut=\(Int64(self.now().timeIntervalSince1970))"
            }
        }
        do {
            let cookie = try await visitorTask!.value
            try Task.checkCancellation()
            return ["Origin": "https://www.bilibili.com", "Cookie": cookie]
        } catch {
            if !Task.isCancelled { visitorTask = nil }
            throw error
        }
    }

    static func tracks(_ videos: [VideoDTO]) -> [Track] {
        var seen = Set<String>()
        return videos.compactMap { $0.track() }.filter { seen.insert($0.id).inserted }
    }
}

private struct RecommendedDTO: Decodable { let item: [VideoDTO]? }
private struct RankingDTO: Decodable { let list: [VideoDTO]? }
private struct FreshDTO: Decodable {
    struct Archive: Decodable {
        let bvid: String?; let title: String?; let cover: String?; let duration: VideoDTO.Duration?
        let author: VideoDTO.Owner?; let goto: String?
        var video: VideoDTO {
            VideoDTO(bvid: bvid, title: title, author: nil, pic: cover, duration: duration, owner: author, goto: goto)
        }
    }
    let archives: [Archive]?
}
private struct VisitorDTO: Decodable { let b_3: String?; let b_4: String? }

struct ScalarText: Codable, Sendable {
    let value: String
    var number: Int64 { Int64(value) ?? 0 }
    init(from decoder: any Decoder) throws {
        let single = try decoder.singleValueContainer()
        if let string = try? single.decode(String.self) { value = string }
        else { value = String(try single.decode(Int64.self)) }
    }
    func encode(to encoder: any Encoder) throws {
        var single = encoder.singleValueContainer()
        try single.encode(value)
    }
}

private struct ChartPeriodsDTO: Decodable {
    struct Period: Decodable { let ID: ScalarText; let priod: ScalarText? }
    let list: [String: [Period]]?
}
private struct ChartSongsDTO: Decodable {
    struct Song: Decodable {
        let creation_bvid: String?
        let creation_title: String?
        let creation_cover: String?
        let creation_nickname: String?
        let creation_duration: VideoDTO.Duration?
        var video: VideoDTO {
            VideoDTO(bvid: creation_bvid, title: creation_title, author: creation_nickname, pic: creation_cover,
                     duration: creation_duration, owner: nil, goto: nil)
        }
    }
    let list: [Song]?
}
