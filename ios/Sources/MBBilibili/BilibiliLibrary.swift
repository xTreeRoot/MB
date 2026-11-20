import Foundation
import MBFoundation

extension BilibiliSource {
    public func invalidateLibraryCache() async { await libraryCache.invalidate() }
    public func library(_ route: LibraryRoute, cursor: String?) async throws -> LibraryPage {
        try await loadSession()
        let scope = session?.active == true ? "user:" + (try await requireAccount()).id : "guest"
        let revision = accountRevision
        let routeKey = String(decoding: try JSONEncoder().encode(route), as: UTF8.self)
        let data = try await libraryCache.data(key: ResponseCache.key([scope, routeKey, cursor ?? ""]), lifetime: 6 * 3600) { [self] in
            try JSONEncoder().encode(try await uncachedLibrary(route, cursor: cursor))
        }
        guard revision == accountRevision else { throw CancellationError() }
        return try JSONDecoder().decode(LibraryPage.self, from: data)
    }
    private func uncachedLibrary(_ route: LibraryRoute, cursor: String?) async throws -> LibraryPage {
        switch route {
        case .favorites:
            let account = try await requireAccount()
            let data: FoldersDTO = try await fetch(path: "/x/v3/fav/folder/created/list-all", parameters: ["up_mid": account.id])
            guard data.list != nil || data.count == 0 else { throw BilibiliError.invalidResponse }
            var links: [LibraryLink] = []
            for folder in data.list ?? [] {
                try Task.checkCancellation()
                // 列表不含封面时，元数据失败仍保留可用的收藏夹入口。
                let metadata: FolderDTO?
                do { metadata = try await fetch(path: "/x/v3/fav/folder/info", parameters: ["media_id": folder.id.value]) }
                catch BilibiliError.signInRequired { throw BilibiliError.signInRequired }
                catch is CancellationError { throw CancellationError() }
                catch { metadata = nil }
                links.append(LibraryLink(id: folder.id.value, title: metadata?.title ?? folder.title ?? folder.id.value,
                    subtitle: "\(metadata?.media_count ?? folder.media_count ?? 0) 个视频", imageURL: VideoDTO.coverURL(metadata?.cover ?? folder.cover),
                    route: .folder(folder.id.value)))
            }
            return LibraryPage(title: route.title, links: links)
        case .folder(let id):
            try Self.requireNumericID(id)
            let page = try Self.pageNumber(cursor)
            let data: FolderContentsDTO = try await fetch(path: "/x/v3/fav/resource/list", parameters: [
                "media_id": id, "pn": String(page), "ps": "20", "keyword": "", "order": "mtime", "type": "0", "tid": "0", "platform": "web",
            ])
            return LibraryPage(title: data.info?.title ?? route.title, tracks: Self.tracks((data.medias ?? []).map(\.video)),
                nextCursor: data.has_more?.value == true ? String(page + 1) : nil)
        case .liked:
            let account = try await requireAccount()
            let data: VideoListDTO = try await fetch(path: "/x/space/like/video", parameters: ["vmid": account.id])
            return LibraryPage(title: route.title, tracks: Self.tracks(data.list))
        case .history:
            _ = try await requireAccount()
            var parameters = ["type": "all", "ps": "20"]
            if let cursor {
                guard let raw = Data(base64Encoded: cursor), let fields = try? JSONDecoder().decode(HistoryCursorDTO.self, from: raw) else {
                    throw BilibiliError.invalidResponse
                }
                parameters.merge(["max": fields.max.value, "view_at": fields.view_at.value, "business": fields.business]) { _, new in new }
            }
            let data: HistoryDTO = try await fetch(path: "/x/web-interface/history/cursor", parameters: parameters)
            let tracks = Self.tracks(data.list.compactMap(\.video))
            let encoder = JSONEncoder()
            encoder.outputFormatting = .sortedKeys
            let next = !data.list.isEmpty && data.cursor.max.number != 0 ? try encoder.encode(data.cursor).base64EncodedString() : nil
            // 同一游标意味着服务端没有前进，避免无限加载同一页。
            return LibraryPage(title: route.title, tracks: tracks, nextCursor: next == cursor ? nil : next)
        case .following:
            let account = try await requireAccount()
            let page = try Self.pageNumber(cursor)
            let data: FollowingDTO = try await fetch(path: "/x/relation/followings", parameters: [
                "vmid": account.id, "ps": "20", "pn": String(page), "order": "desc", "order_type": "",
            ])
            return LibraryPage(title: route.title, links: data.list.map {
                LibraryLink(id: $0.mid.value, title: $0.uname ?? $0.mid.value, subtitle: $0.sign ?? "",
                    imageURL: VideoDTO.coverURL($0.face), route: .creator($0.mid.value))
            }, nextCursor: page * 20 < data.total && !data.list.isEmpty ? String(page + 1) : nil)
        case .creator(let id):
            try Self.requireNumericID(id)
            let page = try Self.pageNumber(cursor)
            let profile: CreatorDTO = try await fetch(path: "/x/space/wbi/acc/info", parameters: ["mid": id], wbi: true)
            let works: CreatorWorksDTO = try await fetch(path: "/x/space/wbi/arc/search", parameters: [
                "mid": id, "order": "pubdate", "ps": "25", "pn": String(page), "index": "1", "order_avoided": "true",
                "platform": "web", "web_location": "333.1387", "dm_img_list": "[]",
                "dm_img_str": "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ", "dm_cover_img_str": "", "dm_img_inter": "{}",
            ], wbi: true)
            return LibraryPage(title: profile.name ?? route.title, tracks: Self.tracks(works.list.vlist),
                nextCursor: page * 25 < works.page.count && !works.list.vlist.isEmpty ? String(page + 1) : nil)
        }
    }

    static func pageNumber(_ cursor: String?) throws -> Int {
        guard let page = Int(cursor ?? "1"), (1...1_000_000).contains(page) else { throw BilibiliError.invalidResponse }
        return page
    }
    static func requireNumericID(_ id: String) throws {
        guard !id.isEmpty, id.utf8.allSatisfy({ (48...57).contains($0) }) else { throw BilibiliError.noContent }
    }
}

private struct FoldersDTO: Decodable { let count: Int?; let list: [FolderDTO]? }
private struct FolderDTO: Decodable { let id: ScalarText; let title: String?; let cover: String?; let media_count: Int? }
private struct FolderContentsDTO: Decodable {
    struct Item: Decodable {
        struct Upper: Decodable { let mid: ScalarText?; let name: String? }
        let bvid: String?; let bv_id: String?; let title: String?; let cover: String?; let duration: VideoDTO.Duration?; let upper: Upper?
        var video: VideoDTO {
            var value = VideoDTO(bvid: bvid ?? bv_id, title: title, author: upper?.name, pic: cover, duration: duration, owner: nil, goto: nil)
            value.mid = upper?.mid
            return value
        }
    }
    let info: FolderDTO?; let medias: [Item]?; let has_more: BoolFlag?
}
private struct BoolFlag: Decodable {
    let value: Bool
    init(from decoder: any Decoder) throws {
        let single = try decoder.singleValueContainer()
        if let boolean = try? single.decode(Bool.self) { value = boolean }
        else { value = try single.decode(Int.self) == 1 }
    }
}
private struct VideoListDTO: Decodable { let list: [VideoDTO] }
private struct HistoryCursorDTO: Codable {
    let max: ScalarText; let view_at: ScalarText; let business: String
}
private struct HistoryDTO: Decodable {
    struct Item: Decodable {
        struct History: Decodable { let bvid: String?; let business: String? }
        let history: History; let title: String?; let cover: String?; let author_name: String?; let author_mid: ScalarText?; let duration: VideoDTO.Duration?
        var video: VideoDTO? {
            guard history.business == nil || history.business == "archive" else { return nil }
            var value = VideoDTO(bvid: history.bvid, title: title, author: author_name, pic: cover, duration: duration, owner: nil, goto: nil)
            value.mid = author_mid
            return value
        }
    }
    let list: [Item]; let cursor: HistoryCursorDTO
}
private struct FollowingDTO: Decodable {
    struct Person: Decodable { let mid: ScalarText; let uname: String?; let face: String?; let sign: String? }
    let list: [Person]; let total: Int
}
private struct CreatorDTO: Decodable { let name: String? }
private struct CreatorWorksDTO: Decodable {
    struct List: Decodable { let vlist: [VideoDTO] }
    struct Page: Decodable { let count: Int }
    let list: List; let page: Page
}
