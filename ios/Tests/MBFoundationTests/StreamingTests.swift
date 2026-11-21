import AVFoundation
import Foundation
@testable import MBFoundation
import Testing

@Test func byteRangesRejectWholeResponsesAndMismatchedOffsets() throws {
    let url = URL(string: "https://media.example/video.mp4")!
    func response(_ code: Int, _ range: String) -> HTTPURLResponse {
        HTTPURLResponse(url: url, statusCode: code, httpVersion: nil, headerFields: ["Content-Range": range])!
    }
    let good = try MediaByteRange(response: response(206, "bytes 10-19/100"), offset: 10, count: 10)
    #expect(good.total == 100)
    #expect(throws: (any Error).self) { try MediaByteRange(response: response(200, "bytes 10-19/100"), offset: 10, count: 10) }
    #expect(throws: (any Error).self) { try MediaByteRange(response: response(206, "bytes 0-9/100"), offset: 10, count: 10) }
    #expect(throws: (any Error).self) { try MediaByteRange(response: response(206, "bytes 10-19/19"), offset: 10, count: 10) }
    #expect(throws: (any Error).self) { try MediaByteRange(response: response(206, "bytes 10-19/100"), offset: 10, count: 9) }
}

private actor RangeFixture {
    let bytes: Data
    var hold: Bool
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    private var requests: [URLRequest] = []
    init(hold: Bool = false) throws {
        self.hold = hold
        bytes = try Data(contentsOf: Bundle.module.url(forResource: "video", withExtension: "mp4", subdirectory: "Fixtures")!)
    }
    func respond(_ request: URLRequest) async throws -> (Data, URLResponse) {
        requests.append(request)
        if hold {
            hold = false
            await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        }
        let text = try #require(request.value(forHTTPHeaderField: "Range"))
        let bounds = text.dropFirst(6).split(separator: "-")
        let start = try #require(Int(bounds[0]))
        let end = min(try #require(Int(bounds[1])), bytes.count - 1)
        let body = bytes.subdata(in: start..<(end + 1))
        let response = try #require(HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                                                   headerFields: ["Content-Range": "bytes \(start)-\(end)/\(bytes.count)"]))
        return (body, response)
    }
    func calls() -> [URLRequest] { requests }
    func waitBlocked() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { continuation?.resume(); continuation = nil }
}
private let streamingResource = MediaResource(url: URL(string: "https://media.example/video.mp4")!, headers: ["Referer": "https://media.example/"], fileExtension: "mp4")

@Test @MainActor func nativeAssetLoadsMetadataThroughRangeTransport() async throws {
    let fixture = try RangeFixture()
    let media = try RemoteMediaAsset(resource: streamingResource, transport: { try await fixture.respond($0) })
    defer { media.cancel() }
    #expect(try await media.asset.load(.isPlayable))
    #expect(try await media.asset.loadTracks(withMediaType: .video).count == 1)
    #expect(try await media.asset.load(.duration).seconds > 0)
    let calls = await fixture.calls()
    #expect(!calls.isEmpty)
    #expect(calls.allSatisfy { $0.value(forHTTPHeaderField: "Range") != nil && $0.value(forHTTPHeaderField: "Referer") == "https://media.example/" })
}

@Test @MainActor func cancellingSlowRemoteMetadataDoesNotWaitForNetwork() async throws {
    let fixture = try RangeFixture(hold: true)
    let media = try RemoteMediaAsset(resource: streamingResource, transport: { try await fixture.respond($0) })
    let loading = Task { try await media.asset.load(.isPlayable) }
    await fixture.waitBlocked()
    media.cancel()
    // 故意让测试传输无视取消并迟到返回，原生资产也必须保持已取消状态。
    await fixture.release()
    await #expect(throws: (any Error).self) { try await loading.value }
}
