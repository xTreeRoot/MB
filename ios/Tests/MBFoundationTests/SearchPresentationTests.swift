import Foundation
import Testing
@testable import MBFoundation

private actor CombinedSearchFixture: MusicSource {
    nonisolated let displayName = "Fixture"
    let id: String
    let fail: Bool
    private var pages: [Int] = []
    init(_ id: String, fail: Bool = false) { self.id = id; self.fail = fail }
    func search(query: String, page: Int) async throws -> TrackPage {
        pages.append(page)
        if fail { throw PluginFailure.execution("offline") }
        return TrackPage(tracks: [Track(sourceID: id, sourceTrackID: String(page), title: query, artist: id)], hasMore: page < 2)
    }
    func requested() -> [Int] { pages }
}

@Test func combinedSearchKeepsPerSourcePaginationTypesAndPartialResults() async throws {
    let a = CombinedSearchFixture("a"), b = CombinedSearchFixture("b"), broken = CombinedSearchFixture("offline", fail: true)
    let source = UnifiedSearchSource(providers: [SearchProvider(id: "a", name: "本地名称", source: a),
        SearchProvider(id: "b", name: "视频站", type: "video", source: b), SearchProvider(id: "broken", name: "离线站", source: broken)])
    let first = try await source.search(query: "test", page: 1)
    #expect(first.tracks.map(\.sourceID) == ["a", "b"] && first.hasMore)
    #expect(first.tracks.map(\.resourceType) == ["music", "video"])
    #expect(first.warnings.count == 1 && first.warnings[0].contains("离线站"))
    let next = try await source.search(query: "test", page: 2)
    #expect(next.tracks.map(\.sourceTrackID) == ["2", "2"] && !next.hasMore)
    #expect(await broken.requested() == [1])
    #expect(await a.requested() == [1, 2])
}

@Test func torrentSearchParsesRealContractRejectsExecutableURLsAndClassifies() throws {
    let data = Data("""
    {"total":{"value":31},"hits":[
      {"id":"linux","title":"Ubuntu","category":"PC / Unix","tracker":"Example","magnetUrl":"magnet:?xt=urn:btih:DAFC8C076CA2F3ED376EEAE7C76A0D6BE2415C45","bytes":1234,"seeders":10},
      {"id":"bad","title":"Bad","link":"javascript:alert(1)"},
      {"id":"song","title":"Song","category":"Audio / MP3","link":"https://knaben.org/live/dl/example"}]}
    """.utf8)
    let page = try TorrentSearchSource.decode(data, page: 1)
    #expect(page.tracks.map(\.sourceTrackID) == ["linux", "song"] && page.hasMore)
    #expect(page.tracks.map(\.resourceType) == ["software", "music"])
    #expect(page.tracks.first?.resourceURL?.scheme == "magnet")
}

@Test func resourceBookmarksPersistAndReplaceWithoutTouchingPlaylists() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = LocalLibraryStore(directory: root)
    _ = try await store.createPlaylist("保留歌单")
    var track = Track(sourceID: "knaben", sourceTrackID: "a", title: "Ubuntu", artist: "Knaben")
    track.resourceURL = URL(string: "magnet:?xt=urn:btih:abc"); track.resourceType = "software"
    _ = try await store.saveResource(track); _ = try await store.saveResource(track)
    let restored = try await LocalLibraryStore(directory: root).snapshot()
    #expect(restored.resources == [track] && restored.playlists.first?.name == "保留歌单")
    #expect(try await store.removeResource(track.id).resources?.isEmpty == true)
}

@Test func pluginPresentationPersistsOutsideInstalledCodeAndCanRestoreDefault() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let installer = PluginInstaller(directory: root)
    var value = PluginPresentation(); try value.rename("a", to: "  我的平台  "); value.order = ["b", "a"]
    try await installer.savePresentation(value)
    let restored = try await PluginInstaller(directory: root).presentation()
    #expect(restored.names["a"] == "我的平台" && restored.order == ["b", "a"])
    try value.rename("a", to: " "); #expect(value.name(id: "a", fallback: "原名") == "原名")
    #expect(throws: PluginFailure.self) { try value.rename("a", to: String(repeating: "a", count: 61)) }
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_LIVE_RESOURCE_SEARCH"] == "1"))
func torrentSearchOfficialV2ReturnsUbuntuMetadata() async throws {
    let page = try await TorrentSearchSource().search(query: "ubuntu", page: 1)
    #expect(!page.tracks.isEmpty)
    #expect(page.tracks.allSatisfy { $0.resourceURL != nil && $0.sourceID == "knaben" })
}

@MainActor @Test func pluginRenameAndOrderSurviveCodeUpdateAndModelReload() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let installer = PluginInstaller(directory: root.appendingPathComponent("store"))
    func input(_ id: String, version: String) throws -> URL {
        let folder = root.appendingPathComponent(id)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("{\"id\":\"\(id)\",\"name\":\"\(id)\",\"version\":\"\(version)\"}".utf8).write(to: folder.appendingPathComponent("plugin.json"))
        try Data("export function search(){return {tracks:[]};}".utf8).write(to: folder.appendingPathComponent("index.js"))
        return folder
    }
    for id in ["a", "b"] { _ = try await installer.install(installer.prepare(input(id, version: "1.0.0"))) }
    let model = PluginModel(installer: installer); await model.restore()
    await model.rename(try #require(model.installed.first), to: "我的平台")
    await model.move(from: [1], to: 0)
    _ = try await installer.install(installer.prepare(input("a", version: "1.1.0")))
    let restored = PluginModel(installer: installer); await restored.restore()
    #expect(restored.installed.map(\.id) == ["b", "a"])
    #expect(restored.displayName(try #require(restored.installed.last)) == "我的平台")
    #expect(restored.installed.last?.manifest.version == "1.1.0")
}
