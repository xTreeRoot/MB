import Foundation

public actor PluginSource: PlaybackSource, VideoSource, AccountSource, InteractionSource, SuggestionSource, PlaybackRecorder {
    public nonisolated let manifest: PluginManifest
    public let runtime: PluginRuntime
    public nonisolated var displayName: String { manifest.displayName }
    public init(manifest: PluginManifest, runtime: PluginRuntime) { self.manifest = manifest; self.runtime = runtime }
    public func supports(_ name: String) async throws -> Bool { try await runtime.supports(name) }
    private var offsets: [String: [Int: Int]] = [:]
    private var commentOffsets: [String: [Int: Int]] = [:]
    public func suggestions(_ query: String) async throws -> [String] {
        guard try await supports("suggest") else { return [] }
        return try await runtime.invoke("suggest", arguments: .object(["query": .string(query)])).array.compactMap(\.string)
    }
    public func played(_ track: Track) async {
        guard (try? await supports("played")) == true else { return }
        _ = try? await runtime.invoke("played", arguments: .object(["id": .string(track.sourceTrackID)]))
    }
    public func search(query: String, page: Int) async throws -> TrackPage {
        if page == 1 { offsets[query] = [1: 0] }
        guard let offset = offsets[query]?[page] else { throw PluginFailure.invalid("Search page has no previous cursor") }
        let result = try await runtime.invoke("search", arguments: .object(["query": .string(query), "kind": .string("tracks"), "offset": .number(Double(offset))]))
        let tracks = try await tracks(result["tracks"])
        offsets[query]?[page + 1] = offset + result["tracks"].array.count
        return TrackPage(tracks: tracks, hasMore: result["more"] == .bool(true) && !tracks.isEmpty)
    }
    public func searchVideos(query: String, offset: Int) async throws -> (page: TrackPage, nextOffset: Int) {
        let result = try await runtime.invoke("searchVideos", arguments: .object(["query": .string(query), "offset": .number(Double(offset))]))
        let rawTracks = result["tracks"]
        let count = rawTracks.array.count
        let page = TrackPage(tracks: try await tracks(rawTracks), hasMore: result["more"] == .bool(true) && count > 0)
        return (page, offset + count)
    }
    public func home() async throws -> PluginBrowse {
        guard try await supports("home") else { return PluginBrowse(title: displayName) }
        return try await browseResult(try await runtime.invoke("home"))
    }
    public func browse(_ page: String) async throws -> PluginBrowse {
        try await browseResult(try await runtime.invoke("browse", arguments: .object(["page": .string(page)])))
    }
    public func audio(track: Track, quality: AudioQuality) async throws -> MediaResource {
        let result = try await runtime.invoke("stream", arguments: .object(["id": .string(track.sourceTrackID), "quality": .string(quality.rawValue)]))
        return try await resource(result)
    }
    public func video(track: Track, quality: AudioQuality) async throws -> MediaResource {
        let result = try await runtime.invoke("video", arguments: .object(["id": .string(track.sourceTrackID), "quality": .string(quality.rawValue)]))
        return try await resource(result)
    }
    public func audio(trackID: String) async throws -> MediaResource {
        let result = try await runtime.invoke("stream", arguments: .object(["id": .string(trackID), "quality": .string("")]))
        return try await resource(result)
    }
    public func supportsVideo(track: Track) async -> Bool { (try? await supports("video")) == true }
    public func video(trackID: String) async throws -> MediaResource {
        let result = try await runtime.invoke("video", arguments: .object(["id": .string(trackID), "quality": .string("")]))
        return try await resource(result)
    }
    public func captions(trackID: String) async throws -> CaptionTimeline {
        guard try await supports("lyrics") else { return CaptionTimeline(lines: []) }
        let result = try await runtime.invoke("lyrics", arguments: .object(["id": .string(trackID)]))
        if !result["lines"].array.isEmpty {
            return .pluginLines(result["lines"].array)
        }
        return .lrc(result["lrc"].string ?? "", translation: result["translation"].string ?? "", durationMilliseconds: 0)
    }
    public func account() async throws -> SourceAccount? {
        guard try await supports("account") else { return nil }
        return account(try await runtime.invoke("account"))
    }
    public func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount {
        await runtime.setPrompt { value in Task { await prompt(value) } }
        do {
            let value = try await runtime.invoke("signIn", timeout: .seconds(300))
            await runtime.setPrompt(nil)
            guard let account = account(value) else { throw PluginFailure.execution("Sign-in returned no account") }
            return account
        } catch { await runtime.setPrompt(nil); throw error }
    }
    public func signOut() async throws { _ = try await runtime.invoke("signOut") }
    public func capabilities() async -> InteractionCapabilities {
        InteractionCapabilities(likes: (try? await supports("like")) == true,
                                comments: (try? await supports("comments")) == true,
                                posting: (try? await supports("postComment")) == true)
    }
    public func supportsCommentLikes() async -> Bool { (try? await supports("likeComment")) == true }
    public func likeComment(trackID: String, commentID: String, enabled: Bool) async throws {
        _ = try await runtime.invoke("likeComment", arguments: .object(["trackId": .string(trackID), "commentId": .string(commentID), "on": .bool(enabled)]))
    }
    public func isLiked(trackID: String) async throws -> Bool {
        try await runtime.invoke("isLiked", arguments: .object(["ids": .array([.string(trackID)])]))[trackID] == .bool(true)
    }
    public func like(trackID: String, enabled: Bool) async throws {
        _ = try await runtime.invoke("like", arguments: .object(["id": .string(trackID), "on": .bool(enabled)]))
    }
    public func comments(trackID: String, page: Int) async throws -> CommentPage {
        if page == 1 { commentOffsets[trackID] = [1: 0] }
        guard let offset = commentOffsets[trackID]?[page] else { throw PluginFailure.invalid("Comment page has no previous cursor") }
        let result = try await runtime.invoke("comments", arguments: .object(["trackId": .string(trackID), "offset": .number(Double(offset))]))
        commentOffsets[trackID]?[page + 1] = offset + result["comments"].array.count
        return CommentPage(comments: result["comments"].array.compactMap(comment), total: result["total"].integer ?? 0, hasMore: result["more"] == .bool(true) && !result["comments"].array.isEmpty)
    }
    public func postComment(trackID: String, text: String) async throws -> TrackComment {
        let value = try await runtime.invoke("postComment", arguments: .object(["trackId": .string(trackID), "text": .string(text)]))
        guard let result = comment(value) else { throw PluginFailure.execution("Invalid comment response") }
        return result
    }
    private func account(_ value: PluginJSON) -> SourceAccount? {
        guard let id = value["id"].string, let name = value["name"].string else { return nil }
        return SourceAccount(id: id, name: name, avatarURL: value["imageUrl"].string.flatMap(URL.init(string:)), page: value["page"].string)
    }
    private func comment(_ value: PluginJSON) -> TrackComment? {
        guard let id = value["id"].string, let text = value["text"].string else { return nil }
        var comment = TrackComment(id: id, author: value["author"].string ?? "", avatarURL: value["avatarUrl"].string.flatMap(URL.init(string:)), text: text, date: Date(timeIntervalSince1970: Double(value["time"].integer ?? 0) / 1000), likes: value["likes"].integer ?? 0)
        comment.liked = value["liked"] == .bool(true); return comment
    }
    func tracks(_ values: PluginJSON) async throws -> [Track] {
        var result: [Track] = []
        for value in values.array {
            guard let id = value["id"].string, let title = value["name"].string else { continue }
            let artists = value["artists"].array
            var track = Track(sourceID: manifest.id, sourceTrackID: id, title: title, artist: artists.compactMap { $0["name"].string }.joined(separator: ", "), artworkURL: value["album"]["imageUrl"].string.flatMap(URL.init(string:)), durationMilliseconds: Int64(value["duration"].integer ?? 0), pageURL: value["url"].string.flatMap(URL.init(string:)))
            track.unavailable = value["unavailable"] == .bool(true)
            track.unavailableReason = TrackProblem.declared(value["unavailableReason"].string)
            track.artistID = artists.first?["id"].string
            if let url = track.artworkURL { track.artworkHeaders = try await runtime.headers(for: url, kind: "image") }
            result.append(track)
        }
        return result
    }
    private func resource(_ value: PluginJSON) async throws -> MediaResource {
        guard let address = value["url"].string, !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw TrackProblem.noSource }
        guard let url = URL(string: address), ["https", "http"].contains(url.scheme ?? ""), url.host != nil, url.user == nil else { throw PluginFailure.execution("Invalid media URL") }
        var headers = try await runtime.headers(for: url, kind: "audio")
        if case .object(let own) = value["headers"] {
            for (name, value) in own { if let text = value.string { headers = headers.filter { $0.key.lowercased() != name.lowercased() }; headers[name] = text } }
        }
        return MediaResource(url: url, headers: headers, fileExtension: value["type"].string ?? "m4a")
    }
    func browseResult(_ value: PluginJSON) async throws -> PluginBrowse {
        var sections: [PluginBrowse.Section] = []
        for (index, section) in value["sections"].array.enumerated() {
            sections.append(PluginBrowse.Section(id: String(index), entities: entities(section), empty: section["empty"].string ?? "", trackLayout: section["trackLayout"].string ?? "", title: section["title"].string ?? "", tracks: try await tracks(section["tracks"]), links: section["links"].array.compactMap { link in
                guard let title = link["title"].string, let page = link["page"].string else { return nil }; return PluginBrowse.Link(title: title, subtitle: link["subtitle"].string ?? "", page: page)
            }, morePage: section["morePage"].string))
        }
        return PluginBrowse(title: value["title"].string ?? displayName, subtitle: value["subtitle"].string ?? "", description: value["description"].string ?? "", sections: sections)
    }
}

public struct PluginBrowse: Sendable {
    public struct Link: Identifiable, Sendable { public let title: String; public let subtitle: String; public let page: String; public var id: String { page } }
    public struct Section: Identifiable, Sendable { public let id: String; public var entities: [PluginEntity] = []; public var empty = ""; public var trackLayout = ""; public let title: String; public let tracks: [Track]; public let links: [Link]; public let morePage: String? }
    public let title: String
    public var subtitle = ""
    public var description = ""
    public var sections: [Section] = []
}

public extension PluginBrowse.Section {
    func reidentified(_ value: String) -> Self { Self(id: value, entities: entities, empty: empty, trackLayout: trackLayout, title: title, tracks: tracks, links: links, morePage: morePage) }
}
