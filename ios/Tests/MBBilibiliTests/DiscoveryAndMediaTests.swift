import Foundation
@testable import MBBilibili
import MBFoundation
import Testing

private actor Routes {
    var bodies: [String: String]
    private var calls: [URLRequest] = []
    init(_ bodies: [String: String]) { self.bodies = bodies }
    func respond(_ request: URLRequest) throws -> (Data, URLResponse) {
        calls.append(request)
        let url = try #require(request.url)
        let page = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "display_id" }?.value ?? ""
        let body = bodies[url.path + "?" + page] ?? bodies[url.path] ?? #"{"code":-404,"data":[]}"#
        return (Data(body.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)))
    }
    func recorded() -> [URLRequest] { calls }
}

private let navigation = #"{"code":-101,"data":{"wbi_img":{"img_url":"https://i.example/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i.example/4932caff0ff746eab6f01bf08b70ac45.png"}}}"#
private let detailPath = "/x/web-interface/view"
private let playPath = "/x/player/wbi/playurl"
private let periodPath = "/x/copyright-music-publicity/toplist/all_period"
private let chartPath = "/x/copyright-music-publicity/toplist/music_list"
private let freshPath = "/x/web-interface/region/feed/rcmd"
private let fingerPath = "/x/frontend/finger/spi"
private let recommendedPath = "/x/web-interface/wbi/index/top/feed/rcmd"

private func makeSource(_ routes: Routes) -> BilibiliSource {
    BilibiliSource(transport: { try await routes.respond($0) }, now: { Date(timeIntervalSince1970: 1_702_204_169) })
}
private func query(_ request: URLRequest) -> [String: String] {
    Dictionary(uniqueKeysWithValues: (URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
}

@Test func latestChartUsesNumericIDAndCreationFields() async throws {
    let routes = Routes([
        periodPath: #"{"code":0,"data":{"list":{"2025":[{"ID":"99","priod":52}],"2026":[{"ID":120,"priod":"18"},{"ID":110,"priod":17}]}}}"#,
        chartPath: #"{"code":0,"data":{"list":[{"creation_bvid":"BV123","creation_title":"榜单曲目","creation_cover":"//i0.hdslb.com/a.jpg","creation_nickname":"榜单UP","creation_duration":187}]}}"#,
    ])
    let section = try await makeSource(routes).discovery(.chart)
    #expect(section.subtitle == "第 18 周")
    #expect(section.tracks.first?.title == "榜单曲目")
    #expect(section.tracks.first?.artist == "榜单UP")
    #expect(section.tracks.first?.durationMilliseconds == 187_000)
    #expect(section.tracks.first?.artworkHeaders["Referer"] == "https://www.bilibili.com/")
    let calls = await routes.recorded()
    #expect(query(calls.last!)["list_id"] == "120")
}

@Test func freshPagesUseVisitorCookieAndDeduplicateVideos() async throws {
    let routes = Routes([
        fingerPath: #"{"code":0,"data":{"b_3":"guest-id","b_4":"a+b=/"}}"#,
        freshPath + "?1": #"{"code":0,"data":{"archives":[{"bvid":"BV123","title":"First","author":{"name":"Fresh UP"},"cover":"https://i0.hdslb.com/a.jpg","duration":123},{"bvid":"BV456","goto":"bangumi"}]}}"#,
        freshPath + "?2": #"{"code":0,"data":{"archives":[{"bvid":"BV123","title":"Duplicate"},{"bvid":"BV789","goto":"av"}]}}"#,
    ])
    let source = makeSource(routes)
    let first = try await source.discovery(.fresh)
    #expect(first.tracks.map(\.sourceTrackID) == ["BV123", "BV789"])
    #expect(first.tracks.first?.artist == "Fresh UP")
    #expect(first.tracks.first?.artworkURL != nil)
    _ = try await source.discovery(.fresh)
    let calls = await routes.recorded()
    #expect(calls.filter { $0.url?.path == fingerPath }.count == 1)
    for call in calls where call.url?.path == freshPath {
        #expect(call.value(forHTTPHeaderField: "Origin") == "https://www.bilibili.com")
        #expect(call.value(forHTTPHeaderField: "Cookie") == "buvid3=guest-id; buvid4=a%2Bb%3D%2F; b_nut=1702204169")
        #expect(query(call)["from_region"] == "1003")
        #expect(query(call)["request_cnt"] == "20")
    }
}

@Test func homeLimitsPreviewsAndKeepsFailedSectionsInOrder() async throws {
    let videos = (1...8).map { #"{"bvid":"BV\#($0)","goto":"av","title":"Video"}"# }.joined(separator: ",")
    let routes = Routes([
        "/x/web-interface/nav": navigation,
        recommendedPath: "{\"code\":0,\"data\":{\"item\":[\(videos),{\"bvid\":\"BV999\",\"goto\":\"ad\"}]}}",
    ])
    let source = makeSource(routes)
    let sections = try await source.home()
    #expect(sections.map(\.category) == DiscoveryCategory.allCases)
    #expect(sections[0].tracks.count == 6)
    #expect(sections[1].errorMessage != nil)
    #expect(try await source.discovery(.recommended).tracks.count == 8)
    let calls = await routes.recorded()
    let signed = try #require(calls.first { $0.url?.path == recommendedPath })
    #expect(query(signed)["w_rid"]?.count == 32)
}

@Test func allHomeSectionsFailAsOneRetryableFailure() async {
    let source = makeSource(Routes([:]))
    await #expect(throws: BilibiliError.noContent) { try await source.home() }
}

private let mediaFixture: [String: String] = [
    "/x/web-interface/nav": navigation,
    detailPath: #"{"code":0,"data":{"aid":123,"cid":"456"}}"#,
    playPath: #"{"code":0,"data":{"dash":{"audio":[{"baseUrl":"https://media.example/audio","mimeType":"audio/mp4"}],"video":[{"base_url":"https://media.example/av1","codecid":13,"height":720,"bandwidth":1},{"base_url":"https://media.example/1080","codecid":7,"height":1080,"bandwidth":20},{"baseUrl":"https://media.example/720high","codecs":"avc1.4d","height":720,"bandwidth":10},{"base_url":"https://media.example/720","codecid":7,"height":720,"bandwidth":5}]}}}"#,
]

@Test func audioAndVideoUseSignedParametersAndPrefer720AVC() async throws {
    let routes = Routes(mediaFixture)
    let source = makeSource(routes)
    let audio = try await source.audio(trackID: "BV123")
    let video = try await source.video(trackID: "BV123")
    #expect(audio.fileExtension == "m4a")
    #expect(video.url.absoluteString == "https://media.example/720")
    #expect(video.fileExtension == "mp4")
    #expect(video.headers["Referer"] == "https://www.bilibili.com/")
    let calls = await routes.recorded()
    #expect(calls.filter { $0.url?.path == detailPath }.count == 1)
    for call in calls where call.url?.path == playPath {
        let values = query(call)
        #expect(values["bvid"] == "BV123")
        #expect(values["cid"] == "456")
        #expect(values["fnval"] == "4048")
        #expect(values["isGaiaAvoided"] == "true")
        #expect(values["w_rid"]?.count == 32)
    }
}

@Test func captionsPreferChineseConvertMillisecondsAndFilterInvalidIntervals() async throws {
    var bodies = mediaFixture
    bodies["/x/player/wbi/v2"] = #"{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"en","subtitle_url":"https://subtitle.hdslb.com/en"},{"lan":"zh-CN","subtitle_url":"//subtitle.hdslb.com/zh"}]}}}"#
    bodies["/zh"] = #"{"body":[{"from":1.2345,"to":2.5,"content":"中文"},{"from":-1,"to":1,"content":"bad"},{"from":5,"to":4,"content":"bad"}]}"#
    let timeline = try await makeSource(Routes(bodies)).captions(trackID: "BV123")
    #expect(timeline.lines == [CaptionLine(startMilliseconds: 1235, endMilliseconds: 2500, text: "中文")])
    #expect(timeline.activeIndex(at: 2500) == nil)
}

@Test(arguments: ["http://subtitle.hdslb.com/zh", "https://subtitle.hdslb.com.evil.test/zh"])
func captionsRejectUntrustedOrInsecureURL(_ url: String) async {
    var bodies = mediaFixture
    bodies["/x/player/wbi/v2"] = "{\"code\":0,\"data\":{\"subtitle\":{\"subtitles\":[{\"lan\":\"zh\",\"subtitle_url\":\"\(url)\"}]}}}"
    await #expect(throws: BilibiliError.invalidResponse) { try await makeSource(Routes(bodies)).captions(trackID: "BV123") }
}

@Test func captionsDistinguishLoginRequiredFromNoSubtitles() async throws {
    var bodies = mediaFixture
    bodies["/x/player/wbi/v2"] = #"{"code":0,"data":{"need_login_subtitle":true}}"#
    await #expect(throws: BilibiliError.signInForCaptions) { try await makeSource(Routes(bodies)).captions(trackID: "BV123") }
    bodies["/x/player/wbi/v2"] = #"{"code":0,"data":{"subtitle":{"subtitles":[]}}}"#
    #expect(try await makeSource(Routes(bodies)).captions(trackID: "BV123").lines.isEmpty)
}
