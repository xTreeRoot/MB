import Foundation
@testable import MBFoundation
import Testing

/// 验证 fork 的公开源码在 iOS QuickJS 中运行，无需桌面进程或个人账号。
@Test func youtubeIOSPluginResolvesAACWithoutDesktopProcesses() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/YouTubeIOSPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil)
        let response: PluginJSON
        if url.path.hasSuffix("/visitor_id") {
            response = .object(["responseContext": .object(["visitorData": .string("fixture-visitor")])])
        } else {
            #expect(url.path.hasSuffix("/player"))
            let body = try JSONDecoder().decode(PluginJSON.self, from: #require(request.httpBody))
            #expect(body["context"]["client"]["clientName"] == .string("VISIONOS"))
            response = .object([
                "playabilityStatus": .object(["status": .string("OK")]),
                "streamingData": .object(["adaptiveFormats": .array([
                    .object(["mimeType": .string("audio/mp4; codecs=\"mp4a.40.2\""), "url": .string("https://r1.googlevideo.com/fixture.m4a"), "bitrate": .number(128000)])
                ])])
            ])
        }
        return (Data(try response.encoded().utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("dQw4w9WgXcQ"), "quality": .string("lossless")]))
    #expect(audio["type"] == .string("m4a"))
    #expect(audio["quality"] == .string("high"))
    #expect(audio["bitRate"] == .number(128000))
}

@Test func youtubeIOSPluginSystemOAuthValidatesBearerAndKeepsSessionDisabledAfterLogout() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/YouTubeIOSPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, oauthSignIn: { request in
        #expect(request.authorizationEndpoint.host == "accounts.google.com")
        #expect(request.scopes == ["https://www.googleapis.com/auth/youtube"])
        return .object(["access_token": .string("synthetic-access"), "refresh_token": .string("synthetic-refresh"),
                        "token_type": .string("Bearer"), "expires_in": .number(3600)])
    }, transport: { request, _ in
        #expect(request.url?.absoluteString == "https://youtube.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer synthetic-access")
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil)
        let text = #"{"items":[{"id":"UCfixture","snippet":{"title":"Fixture"}}]}"#
        return (Data(text.utf8), try #require(HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    #expect(try await runtime.invoke("signIn")["name"] == .string("Fixture"))
    _ = try await runtime.invoke("signOut")
    #expect(try await runtime.invoke("account") == .null)
    let stored = try JSONDecoder().decode(PluginJSON.self, from: Data(contentsOf: folder.appendingPathComponent("storage.json")))
    #expect(stored["oauthSession"]["active"] == .bool(false))
    #expect(stored["oauthSession"]["refresh"] == .string("synthetic-refresh"))
    #expect(try await runtime.invoke("signIn")["id"] == .string("UCfixture"))
}

@Test func sodaIOSPluginReturnsNativeAudioAndOffersAccountLogin() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/SodaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        let model: PluginJSON = .object(["video_duration": .number(180), "video_list": .object([
            "high": .object(["main_url": .string("https://v1.douyinvod.com/fixture.m4a"), "video_meta": .object([
                "codec_type": .string("aac"), "vtype": .string("m4a"), "bitrate": .number(128000)
            ])])
        ])])
        let body: PluginJSON = .object(["track_player": .object(["video_model": .string(try model.encoded())]), "seo_track": .object(["track": .object(["duration": .number(180000)])])])
        return (Data(try body.encoded().utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    #expect(try await runtime.supports("signIn") == true)
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("123"), "quality": .string("lossless")]))
    #expect(audio["type"] == .string("m4a"))
    #expect(audio["quality"] == .string("high"))
}

@Test func sodaIOSPluginReadsVerifiedAccountAndPrivatePlaylistWithNativeQuickJS() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/SodaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: folder) }
    let saved = #"{"qishuiAccount":{"version":1,"id":"9012345678901234561","active":true,"cookies":[{"name":"sessionid","value":"synthetic-session","domain":"api.qishui.com","path":"/"}]}}"#
    try Data(saved.utf8).write(to: folder.appendingPathComponent("storage.json"))
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        let url = try #require(request.url)
        #expect(url.host == "api.qishui.com")
        #expect(request.value(forHTTPHeaderField: "Cookie") == "sessionid=synthetic-session")
        let text: String
        switch url.path {
        case "/luna/pc/me": text = #"{"my_info":{"id":9012345678901234561,"nickname":"汽水测试"}}"#
        case "/luna/pc/me/playlist": text = #"{"playlists":[{"id":9012345678901234562,"title":"私密歌单","type":2,"owner":{"id":9012345678901234561}}]}"#
        case "/luna/pc/playlist/detail": text = #"{"media_resources":[{"entity":{"track":{"id":9012345678901234563,"name":"Private track","vid":"fixture-vid","duration":123456}}}]}"#
        default: throw NetworkFailure.response
        }
        return (Data(text.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    #expect(try await runtime.invoke("account")["name"] == .string("汽水测试"))
    let page = try await runtime.invoke("browse", arguments: .object(["page": .string("my-library")]))
    #expect(page["sections"].array.first?["playlists"].array.first?["id"] == .string("account:9012345678901234562"))
    let list = try await runtime.invoke("playlist", arguments: .object(["id": .string("account:9012345678901234562")]))
    #expect(list["tracks"].array.first?["id"] == .string("9012345678901234563"))
    _ = try await runtime.invoke("signOut")
    #expect(try await runtime.invoke("account") == .null)
}

private actor SodaLoginRecoveryFixture {
    private(set) var polls = 0
    private(set) var profiles = 0
    private(set) var qrRequests = 0
    func response(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        let url = try #require(request.url)
        #expect(url.host == "api.qishui.com")
        let text: String
        var headers: [String: String] = [:]
        switch url.path {
        case "/passport/web/get_qrcode":
            qrRequests += 1
            text = #"{"message":"success","data":{"token":"synthetic-ticket","qrcode_index_url":"https://bff-pc.qishui.com/synthetic"}}"#
            headers["Set-Cookie"] = "passport_csrf_token=synthetic-csrf; Path=/passport; Secure"
        case "/passport/web/check_qrconnect":
            polls += 1
            #expect(String(data: request.httpBody ?? Data(), encoding: .utf8)?.contains("token=synthetic-ticket") == true)
            #expect(request.value(forHTTPHeaderField: "Cookie") == "passport_csrf_token=synthetic-csrf")
            if polls == 1 { throw URLError(.networkConnectionLost) }
            text = #"{"message":"success","data":{"status":"confirmed"}}"#
            headers["Set-Cookie"] = "sessionid=synthetic-session; Path=/; Secure; HttpOnly"
        case "/luna/pc/me":
            profiles += 1
            #expect(request.value(forHTTPHeaderField: "Cookie") == "sessionid=synthetic-session")
            if profiles == 1 { throw URLError(.networkConnectionLost) }
            text = #"{"my_info":{"id":"77","nickname":"汽水回归账号"}}"#
        default: throw NetworkFailure.response
        }
        return (Data(text.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: headers)))
    }
}

@Test func sodaIOSLoginRecoversAppSwitchDisconnectsWithoutRegeneratingQR() async throws {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/SodaPlugin", withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let fixture = SodaLoginRecoveryFixture()
    let runtime = try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest, transport: { request, _ in
        try await fixture.response(request)
    })
    #expect(try await runtime.invoke("signIn")["name"] == .string("汽水回归账号"))
    #expect(await fixture.qrRequests == 1)
    #expect(await fixture.polls == 2)
    #expect(await fixture.profiles == 2)
}
