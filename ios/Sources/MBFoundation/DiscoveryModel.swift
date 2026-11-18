import Foundation
import Observation

/// 刷新不清空已有页面；单个分区失败时保留该分区旧内容并显示错误。
@MainActor @Observable
public final class DiscoveryModel {
    public private(set) var sections: [DiscoverySection] = []
    public private(set) var isLoading = false
    public private(set) var errorMessage: String?
    @ObservationIgnored private let source: any DiscoverySource

    public init(source: any DiscoverySource) { self.source = source }

    public func refresh() async {
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = try await source.home()
            try Task.checkCancellation()
            sections = result.map { section in
                guard section.errorMessage != nil, section.tracks.isEmpty,
                      let old = sections.first(where: { $0.id == section.id }) else { return section }
                return DiscoverySection(category: section.category, tracks: old.tracks,
                                        subtitle: old.subtitle, errorMessage: section.errorMessage)
            }
        } catch {
            if !Task.isCancelled { errorMessage = error.localizedDescription }
        }
    }
}

@MainActor @Observable
public final class DiscoveryPageModel {
    public private(set) var section: DiscoverySection?
    public private(set) var isLoading = false
    public private(set) var errorMessage: String?
    @ObservationIgnored private let source: any DiscoverySource
    public let category: DiscoveryCategory

    public init(source: any DiscoverySource, category: DiscoveryCategory) {
        self.source = source
        self.category = category
    }

    public func refresh() async {
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = try await source.discovery(category)
            try Task.checkCancellation()
            section = result
        } catch {
            if !Task.isCancelled { errorMessage = error.localizedDescription }
        }
    }
}
