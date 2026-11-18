import Foundation
import Observation

/// 搜索状态只在主线程更新；取消或被替换的请求不能改写新结果。
@MainActor @Observable
public final class SearchModel {
    public private(set) var tracks: [Track] = []
    public private(set) var query = ""
    public private(set) var isLoading = false
    public private(set) var hasMore = false
    public private(set) var errorMessage: String?

    @ObservationIgnored private let source: any MusicSource
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var nextPage = 1

    public init(source: any MusicSource) { self.source = source }

    public func search(_ value: String) async {
        generation += 1
        let current = generation
        query = value.trimmingCharacters(in: .whitespacesAndNewlines)
        tracks = []
        nextPage = 1
        hasMore = false
        errorMessage = nil
        isLoading = !query.isEmpty
        guard !query.isEmpty else { return }
        await load(page: 1, generation: current)
    }

    public func loadMore() async {
        guard hasMore, !isLoading else { return }
        isLoading = true
        errorMessage = nil
        await load(page: nextPage, generation: generation)
    }

    public func cancel() {
        generation += 1
        isLoading = false
    }

    private func load(page: Int, generation current: Int) async {
        do {
            let result = try await source.search(query: query, page: page)
            guard current == generation else { return }
            guard !Task.isCancelled else { isLoading = false; return }
            var seen = Set(tracks.map(\.id))
            tracks += result.tracks.filter { seen.insert($0.id).inserted }
            hasMore = result.hasMore
            nextPage = page + 1
            isLoading = false
        } catch {
            guard current == generation else { return }
            isLoading = false
            if Task.isCancelled || error is CancellationError { return }
            errorMessage = error.localizedDescription
        }
    }
}

