import AVFoundation
import Foundation
@testable import MBFoundation
import Testing

private func pluginAuditRuntime(_ fixture: String, folder: URL) throws -> PluginRuntime {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/" + fixture, withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    return try PluginRuntime(directory: root, dataDirectory: folder, manifest: manifest)
}

private func auditCompleteNativeAudio(_ audio: PluginJSON) async throws {
    let address = try #require(audio["url"].string)
    let url = try #require(URL(string: address))
    var headers: [String: String] = [:]
    if case .object(let values) = audio["headers"] {
        for (key, value) in values { headers[key] = value.string }
    }
    do {
        let file = try await NetworkClient.shared.download(MediaResource(url: url, headers: headers, fileExtension: audio["type"].string ?? "m4a"))
        defer { try? FileManager.default.removeItem(at: file) }
        let asset = AVURLAsset(url: file)
        #expect(try await asset.load(.isPlayable))
        #expect(try await asset.load(.duration).seconds > 0)
    } catch {
        let failure = error as NSError
        Issue.record("公开音频探测失败：\(failure.domain) \(failure.code)")
    }
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_YOUTUBE_LIVE_PLAYBACK"] == "1"))
func liveYouTubeNativeAACCanResolveAndReadAudio() async throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try pluginAuditRuntime("YouTubeIOSPlugin", folder: folder)
    let audio = try await runtime.invoke("stream", arguments: .object(["id": .string("dQw4w9WgXcQ"), "quality": .string("high")]))
    #expect(audio["type"] == .string("m4a"))
    try await auditCompleteNativeAudio(audio)
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_YOUTUBE_LIVE_PLAYBACK"] == "1"))
func liveYouTubeCanBrowseHomeAndSearch() async throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try pluginAuditRuntime("YouTubeIOSPlugin", folder: folder)
    let home = try await runtime.invoke("home")
    #expect(!home["sections"].array.isEmpty)
    let results = try await runtime.invoke("search", arguments: .object(["query": .string("夜曲"), "kind": .string("tracks"), "offset": .number(0)]))
    #expect(!results["tracks"].array.isEmpty)
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_QISHUI_LIVE_PLAYBACK"] == "1"), arguments: ["SodaPlugin"])
func liveQishuiCandidateCanBrowseAndReadAudio(fixture: String) async throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: folder) }
    let runtime = try pluginAuditRuntime(fixture, folder: folder)
    let home = try await runtime.invoke("home")
    let tracks = home["sections"].array.flatMap { $0["tracks"].array }
    #expect(!tracks.isEmpty)
    var failures: [String] = []
    for track in tracks.prefix(5) {
        do {
            let audio = try await runtime.invoke("stream", arguments: .object(["id": track["id"], "quality": .string("high")]))
            try await auditCompleteNativeAudio(audio)
            return
        } catch let failure as PluginFailure { failures.append(failure.localizedDescription) }
        catch { failures.append("网络或解码错误：\((error as NSError).domain) \((error as NSError).code)") }
    }
    Issue.record("候选插件未取得可播放音频：\(failures.joined(separator: "；"))")
}
