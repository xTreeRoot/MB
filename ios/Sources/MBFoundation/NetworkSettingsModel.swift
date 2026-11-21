import Foundation
import Observation

@MainActor @Observable
public final class NetworkSettingsModel {
    public var draft = ProxyPolicy.defaults.joined(separator: "\n")
    public private(set) var errorMessage: String?
    public private(set) var savedMessage: String?
    private let client: NetworkClient
    private let store: AppPersistence

    public init(client: NetworkClient = .shared, store: AppPersistence = .shared) {
        self.client = client
        self.store = store
    }

    public func restore() async {
        do {
            if let hosts = try await store.loadProxyHosts() {
                let value = hosts.joined(separator: "\n")
                try await client.setProxyHosts(value)
                draft = value
            }
        } catch { errorMessage = "代理设置读取失败，正在使用默认白名单" }
    }

    public func save() async {
        errorMessage = nil
        savedMessage = nil
        do {
            let policy = try ProxyPolicy(draft)
            try await store.saveProxyHosts(policy.hosts)
            try await client.setProxyHosts(policy.hosts.joined(separator: "\n"))
            draft = policy.hosts.joined(separator: "\n")
            savedMessage = "已保存，后续请求立即生效"
        } catch { errorMessage = error.localizedDescription }
    }
}
