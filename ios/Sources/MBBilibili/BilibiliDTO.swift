import Foundation
import MBFoundation

enum BilibiliError: LocalizedError, Equatable {
    case http(Int)
    case api(Int)
    case invalidSigningKey
    case invalidResponse
    case noContent
    case signInForCaptions
    case signInRequired
    case invalidLogin

    var errorDescription: String? {
        switch self {
        case .http(let code): AppLocalization.text("哔哩哔哩请求失败（HTTP %lld）", arguments: [code])
        case .api(let code): AppLocalization.text("哔哩哔哩接口返回错误（%lld），请稍后重试", arguments: [code])
        case .invalidSigningKey: AppLocalization.text("未取得有效的哔哩哔哩签名密钥")
        case .invalidResponse: AppLocalization.text("哔哩哔哩响应格式不符合预期")
        case .noContent: AppLocalization.text("暂无可用内容，请稍后重试")
        case .signInForCaptions: AppLocalization.text("此视频的字幕需要登录后获取")
        case .signInRequired: AppLocalization.text("请先登录哔哩哔哩")
        case .invalidLogin: AppLocalization.text("登录未成功或二维码已超时，请重新登录")
        }
    }
}

struct APIEnvelope<Payload: Decodable>: Decodable {
    let code: Int
    let data: Payload?

    private enum CodingKeys: String, CodingKey { case code, data }

    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        code = try values.decode(Int.self, forKey: .code)
        // 错误响应可能携带不同形状的 data，不让它遮住真实平台错误码。
        if code == 0 { data = try values.decodeIfPresent(Payload.self, forKey: .data) }
        else if code == -101 { data = try? values.decodeIfPresent(Payload.self, forKey: .data) }
        else { data = nil }
    }
}

struct NavigationDTO: Decodable {
    struct Images: Decodable {
        let img_url: String
        let sub_url: String
    }
    let wbi_img: Images?
}

struct SearchDTO: Decodable {
    let result: [VideoDTO]?
    let numPages: Int?
}

/// 搜索与推荐使用的 duration 类型不同，使用显式联合解析保持边界完整。
struct VideoDTO: Decodable {
    struct Duration: Decodable {
        let milliseconds: Int64
        init(milliseconds: Int64) { self.milliseconds = milliseconds }
        init(from decoder: any Decoder) throws {
            let value = try decoder.singleValueContainer()
            if let seconds = try? value.decode(Double.self) {
                milliseconds = Self.convert(seconds)
            } else {
                let string = try value.decode(String.self)
                let parts = string.split(separator: ":", omittingEmptySubsequences: false)
                guard !parts.isEmpty, parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }),
                      let seconds = parts.reduce(Optional(0.0), { total, part in
                          guard let total, let number = Double(part) else { return nil }
                          return total * 60 + number
                      }) else { milliseconds = 0; return }
                milliseconds = Self.convert(seconds)
            }
        }

        private static func convert(_ seconds: Double) -> Int64 {
            guard seconds.isFinite, seconds > 0, seconds * 1_000 < Double(Int64.max) else { return 0 }
            return Int64((seconds * 1_000).rounded())
        }
    }

    let bvid: String?
    let title: String?
    let author: String?
    let pic: String?
    let duration: Duration?
    let owner: Owner?
    let goto: String?
    var mid: ScalarText? = nil
    var author_id: ScalarText? = nil

    struct Owner: Decodable { let name: String?; let mid: ScalarText? }

    func track() -> Track? {
        guard let bvid, bvid.range(of: "^BV[0-9A-Za-z]+$", options: .regularExpression) != nil else { return nil }
        let name = PlainText.title(title ?? "")
        var track = Track(sourceID: "bilibili", sourceTrackID: bvid,
                     title: name.isEmpty ? bvid : name, artist: owner?.name ?? author ?? "",
                     artworkURL: Self.coverURL(pic), durationMilliseconds: duration?.milliseconds ?? 0,
                     pageURL: URL(string: "https://www.bilibili.com/video/\(bvid)/"))
        track.artworkHeaders = BilibiliSource.headers
        track.artistID = owner?.mid?.value ?? mid?.value ?? author_id?.value
        return track
    }

    static func coverURL(_ value: String?) -> URL? {
        guard let value, !value.isEmpty else { return nil }
        let normalized = value.hasPrefix("//") ? "https:\(value)" : value
        guard let url = URL(string: normalized), url.scheme == "https" else { return nil }
        return url
    }
}

/// 搜索标题的高亮标签不进入 UI；只解析文本实体，不执行或渲染 HTML。
enum PlainText {
    static func title(_ value: String) -> String {
        var result = value.replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
        for (entity, text) in [("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'"), ("&lt;", "<"), ("&gt;", ">"), ("&nbsp;", " "), ("&amp;", "&")] {
            result = result.replacingOccurrences(of: entity, with: text)
        }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

