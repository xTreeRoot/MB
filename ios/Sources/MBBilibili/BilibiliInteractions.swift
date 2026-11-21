import Foundation
import MBFoundation

extension BilibiliSource {
    public func isLiked(trackID: String) async throws -> Bool {
        _ = try await requireAccount()
        let detail = try await detail(trackID)
        let relation: RelationDTO = try await fetch(path: "/x/web-interface/archive/relation", parameters: ["aid": detail.aid.value])
        return relation.like
    }

    public func like(trackID: String, enabled: Bool) async throws {
        _ = try await requireAccount()
        let detail = try await detail(trackID)
        let _: EmptyActionDTO? = try await post(path: "/x/web-interface/archive/like", fields: ["aid": detail.aid.value, "like": enabled ? "1" : "2"])
        await invalidateLibraryCache()
    }

    public func comments(trackID: String, page: Int) async throws -> CommentPage {
        let detail = try await detail(trackID)
        let number = max(1, page)
        let data: CommentsDTO = try await fetch(path: "/x/v2/reply", parameters: [
            "type": "1", "oid": detail.aid.value, "pn": String(number), "ps": "20", "sort": "2",
        ])
        let pinned = number == 1 ? [data.top?.upper, data.top?.admin].compactMap { $0 } : []
        var seen = Set<String>()
        let comments = (pinned + (data.replies ?? [])).map(\.comment).filter { seen.insert($0.id).inserted }
        return CommentPage(comments: comments, total: data.page.count, hasMore: !(data.replies ?? []).isEmpty && number * 20 < data.page.count)
    }

    public func postComment(trackID: String, text: String) async throws -> TrackComment {
        _ = try await requireAccount()
        let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !message.isEmpty else { throw BilibiliError.noContent }
        let detail = try await detail(trackID)
        let data: PostedCommentDTO? = try await post(path: "/x/v2/reply/add", fields: [
            "oid": detail.aid.value, "type": "1", "message": message, "plat": "1",
            "statistics": "{\"appId\":100,\"platform\":5}", "gaia_source": "main_web",
        ], signed: true)
        guard let reply = data?.reply else { throw BilibiliError.invalidResponse }
        return reply.comment
    }

    private func post<T: Decodable>(path: String, fields: [String: String], signed: Bool = false) async throws -> T? {
        try await loadSession()
        guard session?.active == true, let cookie = session?.cookie,
              let csrf = cookie.split(separator: ";").map({ $0.trimmingCharacters(in: .whitespaces) }).first(where: { $0.hasPrefix("bili_jct=") })?.dropFirst(9),
              !csrf.isEmpty else { throw BilibiliError.signInRequired }
        var query: String?
        if signed {
            let key = try await getSigningKey()
            query = WBISigner.query(parameters: ["dm_img_list": "[]", "dm_img_str": Self.dmToken(),
                "dm_cover_img_str": Self.dmToken(), "dm_img_inter": "{\"ds\":[],\"wh\":[0,0,0],\"of\":[0,0,0]}"],
                key: key, timestamp: Int64(now().timeIntervalSince1970))
        }
        var body = fields
        body["csrf"] = String(csrf); body["csrf_token"] = String(csrf)
        let result: APIEnvelope<T> = try await request(path: path, query: query, body: body)
        if result.code == -101 { try await forgetExpiredCookie(); throw BilibiliError.signInRequired }
        guard result.code == 0 else { throw BilibiliError.api(result.code) }
        return result.data
    }

    private static func dmToken() -> String {
        let letters = Array("ABCDEFGHIJK").shuffled()
        return String(letters.prefix(2))
    }
}

private struct EmptyActionDTO: Decodable {}
private struct RelationDTO: Decodable { let like: Bool }
private struct CommentDTO: Decodable {
    struct Member: Decodable { let uname: String?; let avatar: String? }
    struct Content: Decodable { let message: String }
    let rpid: ScalarText?; let rpid_str: String?; let member: Member; let content: Content
    let ctime: Double; let like: Int?
    var comment: TrackComment {
        TrackComment(id: rpid_str ?? rpid?.value ?? "", author: member.uname ?? "", avatarURL: VideoDTO.coverURL(member.avatar),
            text: content.message, date: Date(timeIntervalSince1970: ctime), likes: like ?? 0)
    }
}
private struct CommentsDTO: Decodable {
    struct Page: Decodable { let count: Int }
    struct Top: Decodable { let upper: CommentDTO?; let admin: CommentDTO? }
    let replies: [CommentDTO]?; let top: Top?; let page: Page
}
private struct PostedCommentDTO: Decodable { let reply: CommentDTO? }
