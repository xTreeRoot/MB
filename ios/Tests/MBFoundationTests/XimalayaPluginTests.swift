import Foundation
@testable import MBFoundation
import Testing

private func ximalayaFixture() throws -> (URL, PluginManifest) {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/XimalayaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    return (root, manifest)
}

@Test(arguments: ["high", "standard"])
func ximalayaPluginDowngradesLosslessAndReportsAvailableQuality(quality: String) async throws {
    let (root, manifest) = try ximalayaFixture()
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        #expect(url.path.hasPrefix("/mobile-playpage/track/v3/baseInfo/"))
        #expect(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "trackQualityLevel" }?.value == "3")
        let body: PluginJSON = .object(["ret": .number(0), "trackInfo": .object([
            "trackId": .number(123), "isPaid": .bool(false), "isAuthorized": .bool(true), "isPublic": .bool(true),
            "playUrlList": .array([.object([
                "type": .string(quality == "high" ? "MP3_64" : "MP3_32"),
                "qualityLevel": .number(quality == "high" ? 1 : 0),
                "url": .string("https://audio.xmcdn.com/fixture.mp3"), "fileSize": .number(1024)
            ])])
        ])])
        return (Data(try body.encoded().utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("123"), "quality": .string("lossless")]))
    #expect(audio["quality"] == .string(quality))
    #expect(audio["type"] == .string("mp3"))
    #expect(audio["headers"]["Cookie"] == .null)
}

/// 仅验收免费公开音频，不加载手机会话，不输出签名地址或请求头。
@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_XIMALAYA_LIVE_PLAYBACK"] == "1"))
func liveXimalayaLosslessRequestFallsBackAndReadsAudioRange() async throws {
    let (root, manifest) = try ximalayaFixture()
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest)
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("47740352"), "quality": .string("lossless")]))
    #expect(["high", "standard"].contains(audio["quality"].string ?? ""))
    let address = try #require(audio["url"].string)
    let url = try #require(URL(string: address))
    var request = URLRequest(url: url)
    if case .object(let headers) = audio["headers"] {
        for (key, value) in headers { request.setValue(value.string, forHTTPHeaderField: key) }
    }
    request.setValue("bytes=0-255", forHTTPHeaderField: "Range")
    do {
        let (data, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 512 * 1024)
        #expect((response as? HTTPURLResponse)?.statusCode == 206)
        #expect(!data.isEmpty)
    } catch {
        let failure = error as NSError
        Issue.record("音频 Range 请求失败：\(failure.domain) \(failure.code)")
    }
}
