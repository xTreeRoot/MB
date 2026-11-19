import Foundation
import Observation

@MainActor @Observable
public final class AccountModel {
    public private(set) var account: SourceAccount?
    public private(set) var prompt: SignInPrompt?
    public private(set) var isSigningIn = false
    public private(set) var isSigningOut = false
    public private(set) var errorMessage: String?
    public private(set) var revision = 0
    public let available: Bool
    public let displayName: String
    @ObservationIgnored private let source: (any AccountSource)?
    @ObservationIgnored private var attempt: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    public init(source: (any AccountSource)?) { self.source = source; available = source != nil; displayName = source?.displayName ?? "账号" }

    public func restore() async {
        let current = generation
        do {
            let account = try await source?.account()
            guard current == generation, !Task.isCancelled else { return }
            self.account = account; errorMessage = nil; revision += 1
        } catch {
            if current == generation && !Task.isCancelled { errorMessage = error.localizedDescription }
        }
    }

    public func signIn() {
        guard let source else { return }
        cancelSignIn()
        let current = generation
        isSigningIn = true; errorMessage = nil
        attempt = Task { [self] in
            do {
                let found = try await source.signIn { [weak self] prompt in
                    await self?.receive(prompt, generation: current)
                }
                guard current == generation, !Task.isCancelled else { return }
                account = found; revision += 1
                prompt = nil; isSigningIn = false; attempt = nil
            } catch {
                guard current == generation, !Task.isCancelled else { return }
                errorMessage = error.localizedDescription; isSigningIn = false; prompt = nil; attempt = nil
            }
        }
    }

    public func cancelSignIn() {
        generation += 1
        attempt?.cancel(); attempt = nil
        isSigningIn = false; prompt = nil
    }

    public func signOut() async {
        cancelSignIn()
        isSigningOut = true
        defer { isSigningOut = false }
        let current = generation
        do {
            try await source?.signOut()
            guard current == generation else { return }
            account = nil; revision += 1; errorMessage = nil
        } catch { if current == generation { errorMessage = error.localizedDescription } }
    }

    private func receive(_ prompt: SignInPrompt, generation: Int) {
        guard generation == self.generation, isSigningIn, !Task.isCancelled else { return }
        self.prompt = prompt
    }
    public func waitForSignIn() async { await attempt?.value }
}
