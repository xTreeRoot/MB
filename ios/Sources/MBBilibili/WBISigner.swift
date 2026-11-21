import CryptoKit
import Foundation

/// 对照桌面 bilibili/index.js 和 SocialCrawlerHub 的参数过滤、排序和混排规则。
enum WBISigner {
    private static let order = [
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    ]
    private static let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    static func mixinKey(imageURL: String, subURL: String) throws -> String {
        let source = Array([imageURL, subURL].map {
            URL(string: $0)?.deletingPathExtension().lastPathComponent ?? ""
        }.joined())
        guard source.count == 64 else { throw BilibiliError.invalidSigningKey }
        return String(order.prefix(32).map { source[$0] })
    }

    static func query(parameters: [String: String], key: String, timestamp: Int64) -> String {
        var values = parameters
        values["wts"] = String(timestamp)
        values.removeValue(forKey: "w_rid")
        let query = values.keys.sorted().map { name in
            let value = (values[name] ?? "").filter { !"!'()*".contains($0) }
            return "\(encode(name))=\(encode(value))"
        }.joined(separator: "&")
        // MD5 仅用于平台规定的 WBI 请求签名，不用于凭据存储。
        let digest = Insecure.MD5.hash(data: Data((query + key).utf8))
        let signature = digest.map { String(format: "%02x", $0) }.joined()
        return "\(query)&w_rid=\(signature)"
    }

    private static func encode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
}

