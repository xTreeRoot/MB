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
        #expect(request.url?.absoluteString == "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer synthetic-access")
        #expect(request.value(forHTTPHeaderField: "Cookie") == nil)
        let text = #"{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Fixture"}]},"channelHandle":{"runs":[{"text":"@fixture"}]}}}"#
        return (Data(text.utf8), try #require(HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: [:])))
    })
    #expect(try await runtime.invoke("signIn")["name"] == .string("Fixture"))
    _ = try await runtime.invoke("signOut")
    #expect(try await runtime.invoke("account") == .null)
    let stored = try JSONDecoder().decode(PluginJSON.self, from: Data(contentsOf: folder.appendingPathComponent("storage.json")))
    #expect(stored["oauthSession"]["active"] == .bool(false))
    #expect(stored["oauthSession"]["refresh"] == .string("synthetic-refresh"))
    #expect(try await runtime.invoke("signIn")["id"] == .string("@fixture"))
}

@Test func sodaIOSPluginReturnsNativeAudioAndDoesNotOfferUnimplementedLogin() async throws {
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
    #expect(try await runtime.supports("signIn") == false)
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("123"), "quality": .string("lossless")]))
    #expect(audio["type"] == .string("m4a"))
    #expect(audio["quality"] == .string("high"))
}
