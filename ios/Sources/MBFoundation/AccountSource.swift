import Foundation

public struct SourceAccount: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let avatarURL: URL?
    public let page: String?
    public init(id: String, name: String, avatarURL: URL? = nil, page: String? = nil) {
        self.id = id; self.name = name; self.avatarURL = avatarURL; self.page = page
    }
}

public struct SignInPrompt: Equatable, Sendable {
    public let qrURL: URL
    public let message: String
    public init(qrURL: URL, message: String) { self.qrURL = qrURL; self.message = message }
}

public protocol AccountSource: MusicSource {
    func account() async throws -> SourceAccount?
    func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount
    func signOut() async throws
}

public enum LibraryRoute: Hashable, Sendable {
    case favorites, liked, history, following, folder(String), creator(String)
    public var title: String {
        switch self {
        case .favorites: "收藏夹"
        case .liked: "点赞的视频"
        case .history: "观看历史"
        case .following: "我的关注"
        case .folder: "收藏夹内容"
        case .creator: "UP 主投稿"
        }
    }
}

public struct LibraryLink: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let subtitle: String
    public let imageURL: URL?
    public let route: LibraryRoute
    public init(id: String, title: String, subtitle: String = "", imageURL: URL? = nil, route: LibraryRoute) {
        self.id = id; self.title = title; self.subtitle = subtitle; self.imageURL = imageURL; self.route = route
    }
}

public struct LibraryPage: Sendable {
    public let title: String
    public let tracks: [Track]
    public let links: [LibraryLink]
    public let nextCursor: String?
    public init(title: String, tracks: [Track] = [], links: [LibraryLink] = [], nextCursor: String? = nil) {
        self.title = title; self.tracks = tracks; self.links = links; self.nextCursor = nextCursor
    }
}

public protocol LibrarySource: MusicSource {
    func library(_ route: LibraryRoute, cursor: String?) async throws -> LibraryPage
}

public struct TrackComment: Identifiable, Equatable, Sendable {
    public let id: String
    public let author: String
    public let avatarURL: URL?
    public let text: String
    public let date: Date
    public let likes: Int
    public var liked = false
    public init(id: String, author: String, avatarURL: URL? = nil, text: String, date: Date, likes: Int) {
        self.id = id; self.author = author; self.avatarURL = avatarURL; self.text = text; self.date = date; self.likes = likes
    }
}

public struct CommentPage: Sendable {
    public let comments: [TrackComment]
    public let total: Int
    public let hasMore: Bool
    public init(comments: [TrackComment], total: Int, hasMore: Bool) {
        self.comments = comments; self.total = total; self.hasMore = hasMore
    }
}

public protocol InteractionSource: MusicSource {
    func supportsCommentLikes() async -> Bool
    func likeComment(trackID: String, commentID: String, enabled: Bool) async throws
    func capabilities() async -> InteractionCapabilities
    func isLiked(trackID: String) async throws -> Bool
    func like(trackID: String, enabled: Bool) async throws
    func comments(trackID: String, page: Int) async throws -> CommentPage
    func postComment(trackID: String, text: String) async throws -> TrackComment
}

public struct InteractionCapabilities: Sendable {
    public let likes: Bool
    public let comments: Bool
    public let posting: Bool
    public var available: Bool { likes || comments || posting }
    public init(likes: Bool = true, comments: Bool = true, posting: Bool = true) { self.likes = likes; self.comments = comments; self.posting = posting }
}
public extension InteractionSource {
    func supportsCommentLikes() async -> Bool { false }
    func likeComment(trackID: String, commentID: String, enabled: Bool) async throws { throw PluginFailure.unavailable }
    func capabilities() async -> InteractionCapabilities { InteractionCapabilities() }
}
