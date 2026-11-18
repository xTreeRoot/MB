import Foundation

public struct PlaybackSession: Codable, Sendable {
    public let queue: [Track]
    public let index: Int
    public let positionMilliseconds: Int64
    public let captions: [CaptionLine]?
    public let shuffled: Bool?
    public let repeatMode: RepeatMode?
    public let playOrder: [Int]?
    public let station: PlaybackStation?
    public init(queue: [Track], index: Int, positionMilliseconds: Int64, captions: [CaptionLine]? = nil,
                shuffled: Bool? = nil, repeatMode: RepeatMode? = nil, playOrder: [Int]? = nil, station: PlaybackStation? = nil) {
        self.queue = queue
        self.index = index
        self.positionMilliseconds = positionMilliseconds
        self.captions = captions
        self.shuffled = shuffled; self.repeatMode = repeatMode; self.playOrder = playOrder
        self.station = station
    }
}

public protocol PlaybackSessionStore: Sendable {
    func loadSession() async throws -> PlaybackSession?
    func saveSession(_ session: PlaybackSession) async throws
}

/// 数据仅在本 App 的沙盒，不读取桌面会话，也不保存临时播放地址。
public actor AppPersistence: PlaybackSessionStore, PlaybackModeStore {
    public static let shared = AppPersistence()
    private let directory: URL
    public init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MBMobile", isDirectory: true)
    }

    public func loadSession() throws -> PlaybackSession? { try load(PlaybackSession.self, name: "playback.json") }
    public func saveSession(_ session: PlaybackSession) throws { try save(session, name: "playback.json") }
    public func loadProxyHosts() throws -> [String]? { try load([String].self, name: "proxy-hosts.json") }
    public func saveProxyHosts(_ hosts: [String]) throws { try save(hosts, name: "proxy-hosts.json") }
    public func loadPlaybackMode() throws -> PlaybackMode? { try load(PlaybackMode.self, name: "playback-mode.json") }
    public func savePlaybackMode(_ mode: PlaybackMode) throws { try save(mode, name: "playback-mode.json") }
    public func loadPreferences() throws -> AppPreferences? { try load(AppPreferences.self, name: "preferences.json") }
    public func savePreferences(_ settings: AppPreferences) throws { try save(settings, name: "preferences.json") }

    private func load<T: Decodable>(_ type: T.Type, name: String) throws -> T? {
        let url = directory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: url))
    }

    private func save<T: Encodable>(_ value: T, name: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(name)
        #if os(iOS)
        try JSONEncoder().encode(value).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
        #endif
        var resourceURL = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try resourceURL.setResourceValues(values)
    }
}
