import Foundation
import MBFoundation

struct DetailDTO: Decodable, Sendable { let aid: ScalarText; let cid: ScalarText }

extension BilibiliSource {
    public func audio(trackID: String) async throws -> MediaResource {
        let result = try await playbackInfo(trackID)
        let audio = (result.dash?.audio ?? []).compactMap { item in
            [item.base_url, item.baseUrl].compactMap { $0 }.first(where: { !$0.isEmpty }).map { (item, $0) }
        }
        guard !audio.isEmpty else { throw TrackProblem.noSource }
        guard let (url, stream) = audio.lazy.compactMap({ item, address -> (URL, PlaybackDTO.Dash.Audio)? in
            guard let url = URL(string: address), url.scheme == "https", url.host != nil else { return nil }
            return (url, item)
        }).first else { throw BilibiliError.invalidResponse }
        return MediaResource(url: url, headers: Self.headers,
                             fileExtension: (stream.mime_type ?? stream.mimeType ?? "").hasPrefix("audio/mp4") ? "m4a" : "aac")
    }

    public func video(trackID: String) async throws -> MediaResource {
        let result = try await playbackInfo(trackID)
        let available = (result.dash?.video ?? []).filter { $0.base_url != nil || $0.baseUrl != nil }
        let avc = available.filter { $0.codecid == 7 || ($0.codecs ?? "").hasPrefix("avc1") }
        let selected = (avc.isEmpty ? available : avc).sorted {
            let left = abs(($0.height ?? 0) - 720), right = abs(($1.height ?? 0) - 720)
            return left == right ? ($0.bandwidth ?? 0) < ($1.bandwidth ?? 0) : left < right
        }.first
        guard let selected, let address = selected.base_url ?? selected.baseUrl,
              let url = URL(string: address), url.scheme == "https" else { throw BilibiliError.noContent }
        return MediaResource(url: url, headers: Self.headers, fileExtension: "mp4")
    }

    private func playbackInfo(_ trackID: String) async throws -> PlaybackDTO {
        let detail = try await detail(trackID)
        let result: PlaybackDTO = try await fetch(path: "/x/player/wbi/playurl", parameters: [
            "bvid": trackID, "cid": detail.cid.value, "qn": "80", "fnval": "4048", "fnver": "0", "fourk": "1",
            "gaia_source": "view-card", "from_client": "BROWSER", "is_main_page": "false", "need_fragment": "false",
            "isGaiaAvoided": "true", "voice_balance": "1", "web_location": "1315873",
        ], wbi: true)
        return result
    }

    public func captions(trackID: String) async throws -> CaptionTimeline {
        let detail = try await detail(trackID)
        let result: SubtitleIndexDTO = try await fetch(path: "/x/player/wbi/v2", parameters: [
            "aid": detail.aid.value, "cid": detail.cid.value, "isGaiaAvoided": "false", "web_location": "1315873",
        ], wbi: true)
        let subtitles = result.subtitle?.subtitles ?? []
        guard let selected = subtitles.first(where: { ($0.lan ?? "").lowercased().hasPrefix("zh") }) ?? subtitles.first else {
            if result.need_login_subtitle == true { throw BilibiliError.signInForCaptions }
            return CaptionTimeline(lines: [])
        }
        let raw = selected.subtitle_url
        guard let url = URL(string: raw.hasPrefix("//") ? "https:\(raw)" : raw),
              url.scheme == "https", (url.host ?? "").hasSuffix(".hdslb.com") else { throw BilibiliError.invalidResponse }
        var request = URLRequest(url: url)
        request.allHTTPHeaderFields = Self.headers
        let (data, response) = try await transport(request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw BilibiliError.invalidResponse }
        return try JSONDecoder().decode(SubtitleBodyDTO.self, from: data).timeline
    }

    func detail(_ id: String) async throws -> DetailDTO {
        guard id.range(of: "^BV[0-9A-Za-z]+$", options: .regularExpression) != nil else { throw BilibiliError.noContent }
        if let cached = details[id] { return cached }
        let detail: DetailDTO = try await fetch(path: "/x/web-interface/view", parameters: ["bvid": id])
        guard detail.aid.number > 0, detail.cid.number > 0 else { throw BilibiliError.invalidResponse }
        details[id] = detail
        return detail
    }
}

private struct PlaybackDTO: Decodable {
    struct Dash: Decodable {
        struct Audio: Decodable { let base_url: String?; let baseUrl: String?; let mime_type: String?; let mimeType: String? }
        struct Video: Decodable {
            let base_url: String?; let baseUrl: String?; let codecid: Int?; let codecs: String?
            let height: Int?; let bandwidth: Int?
        }
        let audio: [Audio]?
        let video: [Video]?
    }
    let dash: Dash?
}
private struct SubtitleIndexDTO: Decodable {
    struct Subtitle: Decodable {
        struct Entry: Decodable { let lan: String?; let subtitle_url: String }
        let subtitles: [Entry]?
    }
    let subtitle: Subtitle?
    let need_login_subtitle: Bool?
}
struct SubtitleBodyDTO: Decodable {
    struct Line: Decodable { let from: Double; let to: Double; let content: String }
    let body: [Line]?
    var timeline: CaptionTimeline {
        CaptionTimeline(lines: (body ?? []).compactMap { line in
            guard line.from.isFinite, line.to.isFinite, line.from >= 0, line.to >= line.from,
                  line.to * 1_000 < Double(Int64.max), !line.content.isEmpty else { return nil }
            return CaptionLine(startMilliseconds: Int64((line.from * 1_000).rounded()),
                               endMilliseconds: Int64((line.to * 1_000).rounded()), text: line.content)
        })
    }
}
