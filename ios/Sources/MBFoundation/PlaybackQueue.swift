import Foundation
import Observation

public enum RepeatMode: String, Codable, CaseIterable, Sendable {
    case off, all, one
    public var title: String {
        switch self { case .off: "顺序播放"; case .all: "列表循环"; case .one: "单曲循环" }
    }
}

/// 队列只管理选曲顺序，播放状态由 PlaybackModel 维护。手动上下首首尾相接。
@MainActor @Observable
public final class PlaybackQueue {
    public private(set) var tracks: [Track] = []
    public private(set) var index: Int?
    public private(set) var shuffled = false
    public private(set) var repeatMode = RepeatMode.off
    public private(set) var playOrder: [Int] = []
    public var current: Track? { index.flatMap { tracks.indices.contains($0) ? tracks[$0] : nil } }

    public init() {}

    public func replace(with tracks: [Track], selecting index: Int) {
        guard tracks.indices.contains(index) else { return }
        self.tracks = tracks
        self.index = index
        rebuildOrder()
    }

    public func select(index: Int) {
        guard tracks.indices.contains(index) else { return }
        self.index = index
    }
    @discardableResult public func update(_ track: Track) -> Bool {
        var changed = false
        for index in tracks.indices where tracks[index].id == track.id && tracks[index] != track { tracks[index] = track; changed = true }
        return changed
    }

    public func next() {
        if let next = nextIndex(automatic: false) { index = next }
    }

    public func previous() {
        if let previous = previousIndex() { index = previous }
    }

    public func nextIndex(automatic: Bool) -> Int? {
        guard let index, let position = playOrder.firstIndex(of: index), !playOrder.isEmpty else { return nil }
        if automatic && repeatMode == .one { return index }
        if position + 1 < playOrder.count { return playOrder[position + 1] }
        return automatic && repeatMode == .off ? nil : playOrder[0]
    }
    public func previousIndex() -> Int? {
        guard let index, let position = playOrder.firstIndex(of: index), !playOrder.isEmpty else { return nil }
        return playOrder[(position + playOrder.count - 1) % playOrder.count]
    }
    public func toggleShuffle() { shuffled.toggle(); rebuildOrder() }
    public func cycleRepeat() {
        repeatMode = repeatMode == .off ? .all : repeatMode == .all ? .one : .off
    }
    public func restorePreferences(shuffled: Bool, repeatMode: RepeatMode, order: [Int]?) {
        self.shuffled = shuffled; self.repeatMode = repeatMode
        if let order, order.sorted() == Array(tracks.indices) { playOrder = order }
        else { rebuildOrder() }
    }
    public func append(_ track: Track, next: Bool = false) {
        tracks.append(track)
        if index == nil { index = 0 }
        if next, let current = index, let position = playOrder.firstIndex(of: current) { playOrder.insert(tracks.count - 1, at: position + 1) }
        else { playOrder.append(tracks.count - 1) }
    }
    @discardableResult public func remove(_ offsets: IndexSet) -> Bool {
        let removedCurrent = index.map { offsets.contains($0) } ?? false
        rearrange(tracks.indices.filter { !offsets.contains($0) })
        return removedCurrent
    }
    public func move(_ offsets: IndexSet, to destination: Int) {
        let moving = tracks.indices.filter { offsets.contains($0) }
        var remaining = tracks.indices.filter { !offsets.contains($0) }
        let insertion = max(0, min(remaining.count, destination - moving.filter { $0 < destination }.count))
        remaining.insert(contentsOf: moving, at: insertion)
        rearrange(remaining)
    }
    private func rearrange(_ oldIndices: [Int]) {
        let mapping = Dictionary(uniqueKeysWithValues: oldIndices.enumerated().map { ($0.element, $0.offset) })
        let previous = index
        tracks = oldIndices.map { tracks[$0] }
        index = previous.flatMap { mapping[$0] } ?? (tracks.isEmpty ? nil : min(previous ?? 0, tracks.count - 1))
        playOrder = shuffled ? playOrder.compactMap { mapping[$0] } : Array(tracks.indices)
    }
    private func rebuildOrder() {
        playOrder = Array(tracks.indices)
        if shuffled {
            playOrder.shuffle()
            if let index, let position = playOrder.firstIndex(of: index) { playOrder.swapAt(0, position) }
        }
    }
}

