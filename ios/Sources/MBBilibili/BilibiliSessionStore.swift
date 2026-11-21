import Foundation

struct BilibiliSession: Codable, Equatable, Sendable {
    var cookie: String
    var active: Bool
}

protocol BilibiliSessionStore: Sendable {
    func load() async throws -> BilibiliSession?
    func save(_ session: BilibiliSession) async throws
}

/// Cookie 只在当前 App 数据目录保存；退出仅改 active，不复制桌面凭据，不使用钥匙串。
actor BilibiliFileSessionStore: BilibiliSessionStore {
    private let file: URL
    init(directory: URL? = nil) {
        let root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MBMobile/plugin-data/bilibili", isDirectory: true)
        file = root.appendingPathComponent("session.json")
    }
    func load() throws -> BilibiliSession? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try JSONDecoder().decode(BilibiliSession.self, from: Data(contentsOf: file))
    }
    func save(_ session: BilibiliSession) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try JSONEncoder().encode(session).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try JSONEncoder().encode(session).write(to: file, options: .atomic)
        #endif
        var target = file
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try target.setResourceValues(values)
    }
}
