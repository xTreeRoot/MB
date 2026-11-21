import Foundation
import libxml2

public struct PluginStarsEntry: Identifiable, Sendable {
    public let repository: String
    public let issue: String?
    let pending: PendingPlugin?
    public var id: String { repository }
    public var manifest: PluginManifest? { pending?.manifest }
}

enum PluginStars {
    static func address(_ input: String) throws -> URL {
        guard let address = URLComponents(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              address.scheme == "https", address.host?.lowercased() == "github.com", address.port == nil,
              address.user == nil, address.password == nil else { throw invalidAddress }
        var path = address.path
        if path.hasSuffix("/") { path.removeLast() }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 5, parts[0].isEmpty, parts[1] == "stars", parts[3] == "lists",
              validRepository(String(parts[2]) + "/list"), !parts[4].isEmpty,
              parts[4] != ".", parts[4] != "..", !parts[4].contains("\\") else { throw invalidAddress }
        var clean = URLComponents()
        clean.scheme = "https"; clean.host = "github.com"; clean.path = path
        guard let url = clean.url else { throw invalidAddress }
        return url
    }

    private static var invalidAddress: PluginFailure { .invalid(AppLocalization.text("请输入公开的 GitHub 星标列表地址")) }

    static func repositories(_ input: String) async throws -> [String] {
        if let repository = PluginInstaller.normalizedRepository(input) { return [repository] }
        let url = try address(input)
        var request = URLRequest(url: url)
        request.setValue("text/html", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await NetworkClient.shared.data(for: request, maximumBytes: 4 * 1_024 * 1_024, permitting: { $0 == url })
        guard let response = response as? HTTPURLResponse else { throw NetworkFailure.response }
        guard response.statusCode == 200 else { throw NetworkFailure.http(response.statusCode) }
        return try parse(bytes)
    }

    static func parse(_ bytes: Data) throws -> [String] {
        let options = HTML_PARSE_NONET.rawValue | HTML_PARSE_NOERROR.rawValue | HTML_PARSE_NOWARNING.rawValue
        guard !bytes.isEmpty, bytes.count <= 4 * 1_024 * 1_024,
              let document = bytes.withUnsafeBytes({ htmlReadMemory($0.bindMemory(to: CChar.self).baseAddress, Int32($0.count), nil, "UTF-8", Int32(options)) }) else { throw NetworkFailure.response }
        defer { xmlFreeDoc(document) }

        var list: xmlNodePtr?
        visit(xmlDocGetRootElement(document)) { node in
            if name(node) == "div", attribute(node, "id") == "user-list-repositories" { list = node; return false }
            return true
        }
        guard let list else { throw PluginFailure.invalid(AppLocalization.text("没有找到 GitHub 星标仓库列表")) }
        var result: [String] = [], seen: Set<String> = ["xtreeroot/mb", "xtreeroot/mb-plugin-template"]
        visit(list.pointee.children) { node in
            guard name(node) == "a", let parent = node.pointee.parent, name(parent) == "h2",
                  attribute(parent, "class")?.split(whereSeparator: \.isWhitespace).contains("h3") == true,
                  let path = attribute(node, "href"), path.hasPrefix("/") else { return true }
            let repo = String(path.dropFirst())
            if validRepository(repo), seen.insert(repo.lowercased()).inserted { result.append(repo) }
            return true
        }
        return result
    }

    private static func validRepository(_ repo: String) -> Bool {
        !repo.contains("..") && repo.range(of: #"\A[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\z"#, options: .regularExpression) != nil
    }

    private static func name(_ node: xmlNodePtr) -> String? {
        guard node.pointee.type == XML_ELEMENT_NODE, let value = node.pointee.name else { return nil }
        return String(cString: value)
    }

    private static func attribute(_ node: xmlNodePtr, _ key: String) -> String? {
        guard let attribute = xmlHasProp(node, key), let text = attribute.pointee.children,
              text.pointee.type == XML_TEXT_NODE, text.pointee.next == nil, let value = text.pointee.content else { return nil }
        return String(cString: value)
    }

    @discardableResult private static func visit(_ head: xmlNodePtr?, _ body: (xmlNodePtr) -> Bool) -> Bool {
        var next = head
        while let node = next {
            guard body(node), visit(node.pointee.children, body) else { return false }
            next = node.pointee.next
        }
        return true
    }
}
