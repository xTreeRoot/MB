import Foundation
import Observation

public struct PluginWebLink: Identifiable, Sendable {
    public let id = UUID()
    public let title: String
    public let url: URL
}

public struct PluginWebSignIn: Identifiable, Sendable {
    public let id = UUID()
    public let title: String
    public let url: URL
    public let cookiesOf: URL
    public let until: [String]
    public let userAgent: String?
}

@MainActor @Observable public final class PluginUIService {
    @ObservationIgnored private let oauth = PluginOAuthSession()
    public func authorize(_ request: PluginOAuthRequest) async throws -> PluginJSON { try await oauth.signIn(request) }
    public var signIn: PluginWebSignIn?
    public var link: PluginWebLink?
    public func open(_ url: URL, title: String) { link = PluginWebLink(title: title, url: url) }
    @ObservationIgnored private var continuation: CheckedContinuation<PluginJSON, any Error>?
    public init() {}
    public func show(_ request: PluginWebSignIn) async throws -> PluginJSON {
        guard continuation == nil else { throw PluginFailure.execution("Another sign-in page is already open") }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                if Task.isCancelled { continuation.resume(throwing: CancellationError()); return }
                self.continuation = continuation; signIn = request
            }
        } onCancel: { Task { @MainActor [weak self] in self?.cancel(request.id) } }
    }
    public func finish(_ id: UUID, cookie: String) {
        guard signIn?.id == id else { return }
        let pending = continuation; continuation = nil; signIn = nil
        pending?.resume(returning: .object(["cookie": .string(cookie)]))
    }
    public func cancel(_ id: UUID) {
        guard signIn?.id == id else { return }
        let pending = continuation; continuation = nil; signIn = nil
        pending?.resume(throwing: CancellationError())
    }
}
