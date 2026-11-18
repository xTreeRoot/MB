import Foundation
@testable import MBBilibili
import Testing

private let image = "https://i.example/7cd084941338484aae1ad9425b84077c.png"
private let sub = "https://i.example/4932caff0ff746eab6f01bf08b70ac45.png"

@Test func signatureMatchesIndependentKnownVector() throws {
    let key = try WBISigner.mixinKey(imageURL: image, subURL: sub)
    #expect(key == "ea1db124af3c7062474693fa704f4ff8")
    let query = WBISigner.query(parameters: ["foo": "114", "bar": "514", "baz": "1919810"],
                                key: key, timestamp: 1_702_204_169)
    #expect(query == "bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee")
}

@Test func signatureFiltersReservedCharactersAndEncodesUnicodeSpaces() {
    let signed = WBISigner.query(parameters: ["keyword": "中文 !'()*+&", "page": "1"], key: "key", timestamp: 1)
    #expect(signed.hasPrefix("keyword=%E4%B8%AD%E6%96%87%20%2B%26&page=1&wts=1&w_rid="))
}

@Test func invalidSigningKeyIsRejected() {
    #expect(throws: BilibiliError.invalidSigningKey) {
        try WBISigner.mixinKey(imageURL: "https://i.example/short.png", subURL: sub)
    }
}

@Test func mapsSearchHighlightDurationAndSchemeRelativeCover() throws {
    let json = #"{"bvid":"BV1Q9YX6QEn2","title":"<em class=\"keyword\">夜曲</em> &amp; 现场","author":"UP","pic":"//i0.hdslb.com/cover.jpg","duration":"3:07"}"#
    let track = try #require(JSONDecoder().decode(VideoDTO.self, from: Data(json.utf8)).track())
    #expect(track.title == "夜曲 & 现场")
    #expect(track.durationMilliseconds == 187_000)
    #expect(track.artworkURL?.absoluteString == "https://i0.hdslb.com/cover.jpg")
    #expect(track.sourceTrackID == "BV1Q9YX6QEn2")
}

@Test(arguments: ["61.125", "-2", "\"bad\"", "\"1::2\""])
func durationVariantsAreHandled(value: String) throws {
    let dto = try JSONDecoder().decode(VideoDTO.self, from: Data("{\"bvid\":\"BV123\",\"duration\":\(value)}".utf8))
    #expect(dto.track()?.durationMilliseconds == (value == "61.125" ? 61_125 : 0))
}

private actor HTTPFixture {
    private var requests: [URLRequest] = []
    let searchCode: Int
    let status: Int
    init(searchCode: Int = 0, status: Int = 200) { self.searchCode = searchCode; self.status = status }

    func respond(_ request: URLRequest) throws -> (Data, URLResponse) {
        requests.append(request)
        let url = try #require(request.url)
        let body: String
        switch url.path {
        case "/x/web-interface/nav":
            body = "{\"code\":-101,\"data\":{\"wbi_img\":{\"img_url\":\"\(image)\",\"sub_url\":\"\(sub)\"}}}"
        case "/x/web-interface/wbi/search/type":
            body = "{\"code\":\(searchCode),\"data\":{\"numPages\":2,\"result\":[{\"bvid\":\"BV123\",\"title\":\"Test\"}]}}"
        default: throw BilibiliError.invalidResponse
        }
        let response = try #require(HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil))
        return (Data(body.utf8), response)
    }

    func recorded() -> [URLRequest] { requests }
}

@Test func guestSearchSignsPaginatesAndCachesKeyWithoutCookie() async throws {
    let fixture = HTTPFixture()
    let source = BilibiliSource(transport: { try await fixture.respond($0) }, now: { Date(timeIntervalSince1970: 1_702_204_169) })
    let first = try await source.search(query: "  中文 music  ", page: 1)
    let second = try await source.search(query: "中文 music", page: 2)
    #expect(first.tracks.count == 1)
    #expect(first.hasMore)
    #expect(!second.hasMore)
    let calls = await fixture.recorded()
    #expect(calls.count == 3)
    for call in calls {
        #expect(call.value(forHTTPHeaderField: "Cookie") == nil)
        #expect(call.value(forHTTPHeaderField: "Referer") == "https://www.bilibili.com/")
    }
    let url = try #require(calls.last?.url)
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(query["page"] == "2")
    #expect(query["page_size"] == "20")
    #expect(query["keyword"] == "中文 music")
    #expect(query["search_type"] == "video")
    #expect(query["w_rid"]?.count == 32)
}

@Test func blankQueryDoesNotAccessNetwork() async throws {
    let fixture = HTTPFixture()
    let source = BilibiliSource(transport: { try await fixture.respond($0) }, now: { Date() })
    let page = try await source.search(query: " \n", page: 1)
    #expect(page.tracks.isEmpty)
    #expect(await fixture.recorded().isEmpty)
}

@Test func apiErrorDoesNotBecomeEmptySearchSuccess() async throws {
    let fixture = HTTPFixture(searchCode: -352)
    let source = BilibiliSource(transport: { try await fixture.respond($0) }, now: { Date() })
    await #expect(throws: BilibiliError.api(-352)) { try await source.search(query: "music", page: 1) }
}

@Test func httpErrorsAreReported() async throws {
    let fixture = HTTPFixture(status: 403)
    let source = BilibiliSource(transport: { try await fixture.respond($0) }, now: { Date() })
    await #expect(throws: BilibiliError.http(403)) { try await source.search(query: "music", page: 1) }
}
