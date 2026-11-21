import Foundation
import MBFoundation
import Testing

private actor ControlledSource: MusicSource {
    nonisolated let displayName = "测试音源"
    private var pending: [String: CheckedContinuation<TrackPage, any Error>] = [:]
    private var waiting: [String: CheckedContinuation<Void, Never>] = [:]

    func search(query: String, page: Int) async throws -> TrackPage {
        let key = "\(query):\(page)"
        return try await withCheckedThrowingContinuation { continuation in
            pending[key] = continuation
            waiting.removeValue(forKey: key)?.resume()
        }
    }

    func waitFor(_ key: String) async {
        if pending[key] != nil { return }
        await withCheckedContinuation { waiting[key] = $0 }
    }

    func finish(_ key: String, result: Result<TrackPage, any Error>) {
        pending.removeValue(forKey: key)?.resume(with: result)
    }
}

private func track(_ id: String) -> Track {
    Track(sourceID: "test", sourceTrackID: id, title: id, artist: "")
}

@Test @MainActor func staleSearchCannotReplaceNewResults() async {
    let source = ControlledSource()
    let model = SearchModel(source: source)
    let old = Task { await model.search("旧关键词") }
    await source.waitFor("旧关键词:1")
    let new = Task { await model.search("新关键词") }
    await source.waitFor("新关键词:1")
    await source.finish("新关键词:1", result: .success(TrackPage(tracks: [track("new")], hasMore: false)))
    await new.value
    await source.finish("旧关键词:1", result: .success(TrackPage(tracks: [track("old")], hasMore: true)))
    await old.value
    #expect(model.tracks.map(\.sourceTrackID) == ["new"])
    #expect(model.query == "新关键词")
    #expect(!model.hasMore)
}

@Test @MainActor func cancelledTaskCannotWriteResults() async {
    let source = ControlledSource()
    let model = SearchModel(source: source)
    let task = Task { await model.search("cancel") }
    await source.waitFor("cancel:1")
    task.cancel()
    await source.finish("cancel:1", result: .success(TrackPage(tracks: [track("late")], hasMore: false)))
    await task.value
    #expect(model.tracks.isEmpty)
    #expect(!model.isLoading)
    #expect(model.errorMessage == nil)
}

@Test @MainActor func explicitCancelInvalidatesTransportIgnoringCancellation() async {
    let source = ControlledSource()
    let model = SearchModel(source: source)
    let task = Task { await model.search("cancel") }
    await source.waitFor("cancel:1")
    model.cancel()
    await source.finish("cancel:1", result: .failure(URLError(.timedOut)))
    await task.value
    #expect(model.errorMessage == nil)
    #expect(!model.isLoading)
}

@Test @MainActor func failedPageRetriesSamePageAndDeduplicates() async {
    let source = ControlledSource()
    let model = SearchModel(source: source)
    let first = Task { await model.search("music") }
    await source.waitFor("music:1")
    await source.finish("music:1", result: .success(TrackPage(tracks: [track("a")], hasMore: true)))
    await first.value

    let second = Task { await model.loadMore() }
    await source.waitFor("music:2")
    await source.finish("music:2", result: .failure(URLError(.timedOut)))
    await second.value
    #expect(model.tracks.count == 1)
    #expect(model.hasMore)
    #expect(model.errorMessage != nil)

    let retry = Task { await model.loadMore() }
    await source.waitFor("music:2")
    await source.finish("music:2", result: .success(TrackPage(tracks: [track("a"), track("b")], hasMore: false)))
    await retry.value
    #expect(model.tracks.map(\.sourceTrackID) == ["a", "b"])
    #expect(!model.hasMore)
    #expect(model.errorMessage == nil)
}

@Test @MainActor func clearingQueryInvalidatesInFlightPage() async {
    let source = ControlledSource()
    let model = SearchModel(source: source)
    let task = Task { await model.search("music") }
    await source.waitFor("music:1")
    await model.search("  \n")
    await source.finish("music:1", result: .success(TrackPage(tracks: [track("late")], hasMore: true)))
    await task.value
    #expect(model.query.isEmpty)
    #expect(model.tracks.isEmpty)
    #expect(!model.isLoading)
}

