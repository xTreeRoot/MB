import Foundation

public struct PlaybackStation: Codable, Equatable, Sendable {
    public let sourceID: String
    public let id: String
    public let title: String
    public init(sourceID: String, id: String, title: String) { self.sourceID = sourceID; self.id = id; self.title = title }
}

public protocol StationPlaybackSource: Sendable {
    func stationNext(_ station: PlaybackStation) async throws -> [Track]
}

extension PluginSource: StationPlaybackSource {
    public func stationNext(_ station: PlaybackStation) async throws -> [Track] {
        let value = try await runtime.invoke("stationNext", arguments: .object(["id": .string(station.id)]))
        return try await tracks(value)
    }
}

extension SourceRouter: StationPlaybackSource {
    public func stationNext(_ station: PlaybackStation) async throws -> [Track] {
        guard let source = source(id: station.sourceID) as? any StationPlaybackSource else { throw PluginFailure.unavailable }
        return try await source.stationNext(station)
    }
}
