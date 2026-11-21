import CryptoKit
import Foundation

/// 只诊断一次登录的状态码：不保存 URL 查询、路径原文、请求头、响应正文或账号。
actor PluginSignInDiagnostics {
    struct Entry: Codable, Sendable {
        let host: String
        let pathHash: String
        let status: Int?
        let failureType: String?
        let errorCode: Int?
        let serviceCodes: [Int]?
    }
    private struct Report: Codable {
        let started: Date
        let completed: Bool
        let entries: [Entry]
    }
    private let started = Date()
    private let file: URL
    private var entries: [Entry] = []
    init(directory: URL) { file = directory.appendingPathComponent("sign-in-diagnostics.json") }
    func record(_ event: PluginJSON, result: PluginJSON?, error: (any Error)?) {
        guard event["kind"].string == "fetch", let address = event["url"].string, let url = URL(string: address) else { return }
        let failure = error as NSError?
        let codes = Self.serviceCodes(result?["body"].string)
        let entry = Entry(host: url.host ?? "", pathHash: Self.pathHash(url.path), status: result?["status"].integer,
                          failureType: error.map { String(reflecting: type(of: $0)) }, errorCode: failure?.code,
                          serviceCodes: codes.isEmpty ? nil : codes)
        entries.append(entry)
        if entries.count > 32 { entries.removeFirst(entries.count - 32) }
    }
    func finish(completed: Bool) {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(Report(started: started, completed: completed, entries: entries))
            #if os(iOS)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try data.write(to: file, options: .atomic)
            #endif
        } catch { /* 诊断写入失败不能影响登录结果。 */ }
    }
    nonisolated static func pathHash(_ path: String) -> String {
        SHA256.hash(data: Data(path.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    /// 只提取约定字段里的有界整数错误码，其他响应字段永不写入诊断。
    private nonisolated static func serviceCodes(_ body: String?) -> [Int] {
        guard let body, body.utf8.count <= 2 << 20,
              let value = try? JSONDecoder().decode(PluginJSON.self, from: Data(body.utf8)) else { return [] }
        let fields = [value["code"], value["status_code"], value["error_code"],
                      value["status_info"]["status_code"], value["status_info"]["code"], value["data"]["error_code"]]
        return Array(Set(fields.compactMap { field -> Int? in
            guard let code = field.integer ?? field.string.flatMap(Int.init), (-2_147_483_648...2_147_483_647).contains(code) else { return nil }
            return code
        })).sorted()
    }
}
