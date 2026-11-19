import Foundation
@testable import MBFoundation
import Testing

private actor SegmentFixture {
    private(set) var ranges: [String] = []
    var changed = false
    let total = MediaSegmentReader.block * 8
    func mutate() { changed = true }
    func read(_ request: URLRequest) throws -> (Data, URLResponse) {
        let header = try #require(request.value(forHTTPHeaderField: "Range"))
        ranges.append(header)
        let interval = header.dropFirst(6).split(separator: "-").compactMap { Int64($0) }
        #expect(request.value(forHTTPHeaderField: "Accept-Encoding") == "identity")
        if ranges.count > 1 { #expect(request.value(forHTTPHeaderField: "If-Range") == "\"first\"") }
        let status = changed ? 200 : 206
        let url = try #require(request.url)
        let response = try #require(HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: [
            "Content-Range": "bytes \(interval[0])-\(interval[1])/\(total)", "ETag": "\"first\""
        ]))
        return (Data(repeating: 0x37, count: Int(interval[1] - interval[0] + 1)), response)
    }
}

@Test @MainActor func mediaSegmentsGoToTargetWithoutFetchingPrefixAndDiscardChangedResponse() async throws {
    let fixture = SegmentFixture()
    let url = try #require(URL(string: "https://fixture.test/movie.mp4"))
    let reader = MediaSegmentReader(resource: MediaResource(url: url, headers: [:], fileExtension: "mp4"), transport: { try await fixture.read($0) })
    #expect(try await reader.read(offset: 0, end: 4) == Data(repeating: 0x37, count: 4))
    let target = 6 * MediaSegmentReader.block
    #expect(try await reader.read(offset: target + 1, end: target + 5).count == 4)
    #expect(try await reader.read(offset: target + 2, end: target + 8).count == 6)
    #expect(await fixture.ranges == ["bytes=0-262143", "bytes=1572864-1835007"])
    await fixture.mutate()
    await #expect(throws: (any Error).self) { try await reader.read(offset: 5 * MediaSegmentReader.block, end: target) }
}

@Test @MainActor func preloadedPrefixIsReusedWithoutAnotherRangeRequest() async throws {
    let fixture = SegmentFixture()
    let resource = MediaResource(url: URL(string: "https://fixture.test/next.m4a")!, headers: [:], fileExtension: "m4a")
    let reader = MediaSegmentReader(resource: resource, transport: { try await fixture.read($0) })
    #expect(try await reader.read(offset: 0, end: MediaSegmentReader.block).count == 262_144)
    #expect(try await reader.read(offset: 0, end: 2).count == 2)
    #expect(try await reader.read(offset: 90_000, end: 100_000).count == 10_000)
    #expect(await fixture.ranges == ["bytes=0-262143"])
}
