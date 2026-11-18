import Foundation

public struct DisabledMusicSource: MusicSource {
    public let displayName = "MB"
    public init() {}
    public func search(query: String, page: Int) async throws -> TrackPage { throw PluginFailure.invalid(AppLocalization.text("请在设置启用音源")) }
}
