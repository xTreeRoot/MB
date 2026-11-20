import Foundation
import MBFoundation

/// 平台请求、签名、分页与解析全部留在此模块，UI 不拼接平台 URL。
public actor BilibiliSource: DiscoverySource, PlaybackSource, VideoSource, AccountSource, LibrarySource, InteractionSource {
    public nonisolated let displayName = "哔哩哔哩"
    let transport: @Sendable (URLRequest) async throws -> (Data, URLResponse)
    let now: @Sendable () -> Date
    private var signingKey: String?
    let sessionStore: (any BilibiliSessionStore)?
    let pollDelay: @Sendable () async throws -> Void
    var session: BilibiliSession?
    var sessionLoaded = false
    var sessionLoading: Task<BilibiliSession?, any Error>?
    var accountSnapshot: SourceAccount?
    var accountRevision = 0
    let libraryCache: ResponseCache
    var visitorTask: Task<String, any Error>?
    var details: [String: DetailDTO] = [:]
    static let origin = "https://api.bilibili.com"
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/134.0.0.0 Safari/537.36"
    static var headers: [String: String] { ["Referer": "https://www.bilibili.com/", "User-Agent": userAgent] }

    public init() {
        libraryCache = ResponseCache(directory: ResponseCache.directory("bilibili-library"))
        transport = { request in try await NetworkClient.shared.data(for: request) }
        now = { Date() }
        sessionStore = BilibiliFileSessionStore()
        pollDelay = { try await Task.sleep(for: .seconds(2)) }
    }

    init(transport: @escaping @Sendable (URLRequest) async throws -> (Data, URLResponse),
         now: @escaping @Sendable () -> Date, sessionStore: (any BilibiliSessionStore)? = nil,
         pollDelay: @escaping @Sendable () async throws -> Void = { try await Task.sleep(for: .seconds(2)) }) {
        self.libraryCache = ResponseCache()
        self.transport = transport
        self.now = now
        self.sessionStore = sessionStore
        self.pollDelay = pollDelay
    }

    public func search(query: String, page: Int) async throws -> TrackPage {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return TrackPage(tracks: [], hasMore: false) }
        try Task.checkCancellation()
        let page = max(1, page)
        let key = try await getSigningKey()
        let signed = WBISigner.query(parameters: [
            "keyword": query, "search_type": "video", "page": String(page),
            "page_size": "20", "order": "totalrank",
        ], key: key, timestamp: Int64(now().timeIntervalSince1970))
        let result: APIEnvelope<SearchDTO> = try await request(path: "/x/web-interface/wbi/search/type", query: signed)
        guard result.code == 0 else { throw BilibiliError.api(result.code) }
        guard let data = result.data else { throw BilibiliError.invalidResponse }
        return TrackPage(tracks: Self.tracks(data.result ?? []), hasMore: page < (data.numPages ?? 0))
    }

    func getSigningKey() async throws -> String {
        if let signingKey { return signingKey }
        let result: APIEnvelope<NavigationDTO> = try await request(path: "/x/web-interface/nav")
        // 游客 nav 的 -101 仍可能携带签名密钥，与桌面策略一致。
        guard result.code == 0 || result.code == -101 else { throw BilibiliError.api(result.code) }
        guard let images = result.data?.wbi_img else { throw BilibiliError.invalidSigningKey }
        let key = try WBISigner.mixinKey(imageURL: images.img_url, subURL: images.sub_url)
        signingKey = key
        return key
    }

    func fetch<T: Decodable>(path: String, parameters: [String: String] = [:], wbi: Bool = false,
                             headers: [String: String] = [:]) async throws -> T {
        let query: String
        if wbi {
            let key = try await getSigningKey()
            query = WBISigner.query(parameters: parameters, key: key, timestamp: Int64(now().timeIntervalSince1970))
        } else {
            var components = URLComponents()
            components.queryItems = parameters.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
            query = components.percentEncodedQuery ?? ""
        }
        let result: APIEnvelope<T> = try await request(path: path, query: query.isEmpty ? nil : query, headers: headers)
        if result.code == -101 {
            if session?.active == true { try await forgetExpiredCookie() }
            throw BilibiliError.signInRequired
        }
        guard result.code == 0 else { throw BilibiliError.api(result.code) }
        guard let data = result.data else { throw BilibiliError.invalidResponse }
        return data
    }

    func request<T: Decodable>(path: String, query: String? = nil, headers: [String: String] = [:],
                              origin: String = BilibiliSource.origin, body: [String: String]? = nil,
                              cookieOverride: String? = nil) async throws -> T {
        try await loadSession()
        let revision = accountRevision
        let cookie = cookieOverride ?? (session?.active == true ? session?.cookie ?? "" : "")
        guard var components = URLComponents(string: origin + path) else { throw BilibiliError.invalidResponse }
        components.percentEncodedQuery = query
        guard let url = components.url else { throw BilibiliError.invalidResponse }
        var request = URLRequest(url: url)
        request.allHTTPHeaderFields = Self.headers.merging(headers) { _, new in new }
        let combined = [headers["Cookie"] ?? "", cookie].filter { !$0.isEmpty }.joined(separator: "; ")
        if !combined.isEmpty { request.setValue(combined, forHTTPHeaderField: "Cookie") }
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = Self.form(body).data(using: .utf8)
        }
        let (data, response) = try await transport(request)
        try Task.checkCancellation()
        guard revision == accountRevision else { throw CancellationError() }
        guard let response = response as? HTTPURLResponse else { throw BilibiliError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else { throw BilibiliError.http(response.statusCode) }
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw BilibiliError.invalidResponse }
    }

    static func form(_ values: [String: String]) -> String {
        var components = URLComponents()
        components.queryItems = values.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return (components.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B")
    }

    func clearAccountCaches() {
        signingKey = nil
        details.removeAll()
        accountSnapshot = nil
    }
}

