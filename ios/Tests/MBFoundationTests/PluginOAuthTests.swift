import CryptoKit
import Foundation
@testable import MBFoundation
import Testing

private func oauthOptions() -> PluginJSON {
    .object([
        "authorizationEndpoint": .string("https://api.example.test/authorize"),
        "tokenEndpoint": .string("https://api.example.test/token"),
        "clientID": .string("public-fixture-client"),
        "redirectURI": .string("mb-fixture:/oauthredirect"),
        "scopes": .array([.string("library")]),
    ])
}

private func oauthPermissions() throws -> PluginManifest.Permissions? {
    try JSONDecoder().decode(PluginManifest.self, from: Data(#"{"id":"fixture","permissions":{"network":["api.example.test"]}}"#.utf8)).permissions
}

@Test func oauthGeneratesIndependentPKCEAndExchangesOnlyMatchingCallback() throws {
    let request = try PluginOAuthRequest(oauthOptions(), permissions: oauthPermissions())
    let flow = try PluginOAuthFlow(request: request)
    let other = try PluginOAuthFlow(request: request)
    #expect(flow.state != other.state && flow.verifier != other.verifier)
    #expect(flow.verifier.count == 43)
    let query = try #require(URLComponents(url: flow.authorizationURL, resolvingAgainstBaseURL: false)?.queryItems)
    let expected = Data(SHA256.hash(data: Data(flow.verifier.utf8))).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    #expect(query.first { $0.name == "code_challenge" }?.value == expected)
    #expect(query.first { $0.name == "code_challenge_method" }?.value == "S256")
    #expect(!flow.authorizationURL.absoluteString.contains(flow.verifier))
    let callback = try #require(URL(string: "mb-fixture:/oauthredirect?state=\(flow.state)&code=a%2Bb%26c"))
    let token = try flow.tokenRequest(callback: callback)
    #expect(token.httpMethod == "POST" && token.url == request.tokenEndpoint)
    let form = try #require(URLComponents(string: "https://fixture.test/?" + String(decoding: token.httpBody ?? Data(), as: UTF8.self))?.queryItems)
    #expect(form.first { $0.name == "code" }?.value == "a+b&c")
    #expect(form.first { $0.name == "code_verifier" }?.value == flow.verifier)
    #expect(!form.contains { $0.name == "client_secret" })
    for suffix in ["state=wrong&code=x", "state=\(flow.state)&state=\(flow.state)&code=x", "state=\(flow.state)&code=x&code=y", "state=\(flow.state)&error=access_denied"] {
        #expect(throws: PluginFailure.self) { try flow.tokenRequest(callback: URL(string: "mb-fixture:/oauthredirect?" + suffix)!) }
    }
    for base in ["other:/oauthredirect", "mb-fixture:/other", "mb-fixture://host/oauthredirect", "mb-fixture://user:secret@/oauthredirect"] {
        if let url = URL(string: base + "?state=\(flow.state)&code=x") {
            #expect(throws: PluginFailure.self) { try flow.tokenRequest(callback: url) }
        }
    }
}

@Test func oauthRejectsUndeclaredEndpointsAndInvalidRedirectsBeforeOpeningBrowser() throws {
    let permissions = try oauthPermissions()
    for (key, value): (String, PluginJSON) in [
        ("authorizationEndpoint", .string("https://other.test/authorize")),
        ("tokenEndpoint", .string("http://api.example.test/token")),
        ("tokenEndpoint", .string("https://user:password@api.example.test/token")),
        ("redirectURI", .string("https://api.example.test/callback")),
        ("redirectURI", .string("mb-fixture:/callback?state=fixed")),
        ("scopes", .array([.string("library"), .number(1)])),
        ("scopes", .array([.string("library"), .string("library")])),
    ] {
        guard case .object(var options) = oauthOptions() else { return }
        options[key] = value
        #expect(throws: PluginFailure.self) { try PluginOAuthRequest(.object(options), permissions: permissions) }
    }
}
