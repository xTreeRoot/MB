import Foundation
import MBFoundation
import Testing

private actor HomeFixture: DiscoverySource {
    nonisolated let displayName = "fixture"
    private var next: Result<[DiscoverySection], any Error>
    private var calls = 0
    init(_ sections: [DiscoverySection]) { next = .success(sections) }
    func search(query: String, page: Int) async throws -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func home() async throws -> [DiscoverySection] { calls += 1; return try next.get() }
    func discovery(_ category: DiscoveryCategory) async throws -> DiscoverySection { try next.get().first { $0.category == category }! }
    func respond(_ result: Result<[DiscoverySection], any Error>) { next = result }
}

@Test @MainActor func homeRefreshFailureKeepsVisibleSectionsAndCanRetry() async {
    let track = Track(sourceID: "t", sourceTrackID: "a", title: "a", artist: "")
    let source = HomeFixture([DiscoverySection(category: .chart, tracks: [track])])
    let model = DiscoveryModel(source: source)
    await model.refresh()
    await source.respond(.failure(URLError(.notConnectedToInternet)))
    await model.refresh()
    #expect(model.sections.first?.tracks == [track])
    #expect(model.errorMessage != nil)
    #expect(!model.isLoading)
    await source.respond(.success([DiscoverySection(category: .fresh, tracks: [track])]))
    await model.refresh()
    #expect(model.errorMessage == nil)
    #expect(model.sections.first?.category == .fresh)
}

@Test @MainActor func partiallyFailedRefreshRetainsOnlyFailedSectionOldTracks() async {
    let track = Track(sourceID: "t", sourceTrackID: "a", title: "a", artist: "")
    let source = HomeFixture([DiscoverySection(category: .chart, tracks: [track])])
    let model = DiscoveryModel(source: source)
    await model.refresh()
    await source.respond(.success([
        DiscoverySection(category: .chart, tracks: [], errorMessage: "offline"),
        DiscoverySection(category: .fresh, tracks: [track]),
    ]))
    await model.refresh()
    #expect(model.sections.first?.tracks == [track])
    #expect(model.sections.first?.errorMessage == "offline")
    #expect(model.sections.count == 2)
}

@Test @MainActor func seeAllLoadsCategoryWithoutChangingOtherSections() async {
    let source = HomeFixture([DiscoverySection(category: .chart, tracks: [], subtitle: "第 231 周")])
    let model = DiscoveryPageModel(source: source, category: .chart)
    await model.refresh()
    #expect(model.section?.subtitle == "第 231 周")
    #expect(model.category == .chart)
}
