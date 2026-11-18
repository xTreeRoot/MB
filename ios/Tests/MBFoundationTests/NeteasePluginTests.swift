import Foundation
@testable import MBFoundation
import Testing

private actor NeteaseSignInServer {
    var expired = true
    func respond(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        let url = try #require(request.url)
        let body: String
        var headers: [String: String] = [:]
        switch url.path {
        case "/eapi/login/qrcode/unikey":
            #expect(request.httpMethod == "POST")
            #expect(request.httpBody?.starts(with: Data("params=".utf8)) == true)
            body = #"{"code":200,"unikey":"fixture-qr"}"#
        case "/eapi/login/qrcode/client/login":
            body = expired ? #"{"code":800}"# : #"{"code":803}"#
            if !expired { headers["Set-Cookie"] = "MUSIC_U=fixture-session; Path=/; HttpOnly" }
            expired = false
        case "/api/nuser/account/get":
            #expect(request.value(forHTTPHeaderField: "Cookie") == "MUSIC_U=fixture-session")
            body = #"{"code":200,"profile":{"userId":42,"nickname":"Fixture"}}"#
        default: throw PluginFailure.invalid("Unexpected fixture request")
        }
        return (Data(body.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: headers)))
    }
}

private final class NeteasePrompts: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [SignInPrompt] = []
    func append(_ value: SignInPrompt) { lock.lock(); defer { lock.unlock() }; values.append(value) }
    var prompts: [SignInPrompt] { lock.lock(); defer { lock.unlock() }; return values }
}

private actor NeteasePollObservation {
    var polls: [Int] = []
    var steps: [String] = []
    func response(path: String, status: Int, body: Data) {
        let code = (try? JSONDecoder().decode(PluginJSON.self, from: body))?["code"].integer
        steps.append("\(path) HTTP \(status) code \(code ?? 0)")
        if path == "/eapi/login/qrcode/client/login", let code { polls.append(code) }
    }
    func failure(path: String, error: any Error) {
        let error = error as NSError
        steps.append("\(path) \(error.domain) \(error.code)")
    }
}

private func neteaseFixture(_ name: String = "NeteasePlugin") throws -> (URL, PluginManifest) {
    let root = try #require(Bundle.module.url(forResource: "Fixtures/" + name, withExtension: nil))
    let manifest = try JSONDecoder().decode(PluginManifest.self, from: Data(contentsOf: root.appendingPathComponent("plugin.json")))
    return (root, manifest)
}

@Test func originalNeteasePluginCanShowQRCodeRetryExpiredLoginAndRestoreAccount() async throws {
    let (root, manifest) = try neteaseFixture()
    let data = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: data) }
    let server = NeteaseSignInServer(), recorder = NeteasePrompts()
    let transport: PluginRuntime.Transport = { request, _ in try await server.respond(request) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: data, manifest: manifest, transport: transport)
    await runtime.setPrompt { recorder.append($0) }
    do {
        _ = try await runtime.invoke("signIn")
        Issue.record("Expired QR must fail")
    } catch PluginFailure.execution(let message) { #expect(message.contains("二维码已过期")) }
    let result = try await runtime.invoke("signIn")
    #expect(result["id"] == .string("42"))
    #expect(recorder.prompts.map { $0.qrURL?.host } == [nil, "music.163.com", nil, "music.163.com", nil])
    #expect(recorder.prompts.last?.message == "登录成功")
    let diagnosticText = try String(contentsOf: data.appendingPathComponent("sign-in-diagnostics.json"), encoding: .utf8)
    #expect(!diagnosticText.contains("fixture-session"))
    #expect(!diagnosticText.contains("fixture-qr"))
    #expect(!diagnosticText.contains("/eapi/login"))
    let restored = try PluginRuntime(directory: root, dataDirectory: data, manifest: manifest, transport: transport)
    #expect(try await PluginSource(manifest: manifest, runtime: restored).account()?.id == "42")
}

/// 手动联网验收只生成二维码，再取消等待；不读取手机账号，不输出二维码密钥或 Cookie。
@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_NETEASE_LIVE_LOGIN"] == "1"))
func liveOriginalNeteasePluginGeneratesQRCodeAndCancelsWithoutAnAccount() async throws {
    let (root, manifest) = try neteaseFixture()
    let data = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: data) }
    let runtime = try PluginRuntime(directory: root, dataDirectory: data, manifest: manifest)
    let recorder = NeteasePrompts()
    await runtime.setPrompt { recorder.append($0) }
    let operation = Task { try await runtime.invoke("signIn") }
    defer { operation.cancel() }
    let deadline = ContinuousClock.now + .seconds(20)
    while !recorder.prompts.contains(where: { $0.qrURL != nil }), ContinuousClock.now < deadline {
        if operation.isCancelled { break }
        try await Task.sleep(for: .milliseconds(20))
    }
    operation.cancel()
    await #expect(throws: CancellationError.self) { try await operation.value }
    #expect(recorder.prompts.first?.message.contains("正在生成") == true)
    #expect(recorder.prompts.contains(where: { $0.qrURL?.host == "music.163.com" }))
    #expect(try await runtime.invoke("account") == .null)
}

/// 覆盖真正的扫码轮询，诊断只保留端点、状态码和系统错误码，不记录密钥、请求头或响应内容。
@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_NETEASE_LIVE_LOGIN"] == "1"))
func liveOriginalNeteasePluginCanPollWaitingForScanTwice() async throws {
    let (root, manifest) = try neteaseFixture()
    let data = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: data) }
    let observed = NeteasePollObservation(), network = NetworkClient()
    let runtime = try PluginRuntime(directory: root, dataDirectory: data, manifest: manifest, transport: { request, permissions in
        do {
            let (data, response) = try await network.data(for: request, maximumBytes: 16 * 1_024 * 1_024, permitting: { permissions.reaches($0.host ?? "") })
            let http = try #require(response as? HTTPURLResponse)
            await observed.response(path: request.url?.path ?? "", status: http.statusCode, body: data)
            return (data, http)
        } catch {
            await observed.failure(path: request.url?.path ?? "", error: error)
            throw error
        }
    })
    let operation = Task { try await runtime.invoke("signIn") }
    defer { operation.cancel() }
    let deadline = ContinuousClock.now + .seconds(20)
    while await observed.polls.count < 2, ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(20)) }
    operation.cancel()
    let steps = await observed.steps
    await #expect(throws: CancellationError.self) { try await operation.value }
    #expect(await observed.polls == [801, 801], "\(steps)")
    var profile = URLRequest(url: URL(string: "https://music.163.com/api/nuser/account/get")!)
    profile.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
    profile.setValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15", forHTTPHeaderField: "User-Agent")
    do {
        let (body, response) = try await network.data(for: profile)
        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        #expect(try JSONDecoder().decode(PluginJSON.self, from: body)["code"].integer != nil)
    } catch {
        let failure = error as NSError
        Issue.record("账号读取端点的匿名请求失败：\(failure.domain) \(failure.code)")
    }
}


private actor NeteaseRecoveringServer {
    var keys = 0
    var polls = 0
    var profiles = 0
    func respond(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        let url = try #require(request.url)
        let body: String
        var headers: [String: String] = [:]
        switch url.path {
        case "/eapi/login/qrcode/unikey":
            keys += 1
            body = #"{"code":200,"unikey":"fixture-recovery-qr"}"#
        case "/eapi/login/qrcode/client/login":
            polls += 1
            if polls == 1 { throw URLError(.networkConnectionLost) }
            body = #"{"code":803}"#
            headers["Set-Cookie"] = "MUSIC_U=fixture-recovery-session; Path=/; HttpOnly"
        case "/api/nuser/account/get":
            profiles += 1
            #expect(request.value(forHTTPHeaderField: "Cookie") == "MUSIC_U=fixture-recovery-session")
            if profiles == 1 { throw URLError(.networkConnectionLost) }
            body = #"{"code":200,"profile":{"userId":42,"nickname":"Fixture"}}"#
        default: throw PluginFailure.invalid("Unexpected recovery fixture request")
        }
        return (Data(body.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: headers)))
    }
}

@Test func neteaseIOSPluginRecoversAppSwitchDisconnectsWithoutLosingPendingSession() async throws {
    let (root, manifest) = try neteaseFixture("NeteaseIOSPlugin")
    let data = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: data) }
    let server = NeteaseRecoveringServer(), recorder = NeteasePrompts()
    let runtime = try PluginRuntime(directory: root, dataDirectory: data, manifest: manifest, transport: { request, _ in
        try await server.respond(request)
    })
    await runtime.setPrompt { recorder.append($0) }
    #expect(try await runtime.invoke("signIn")["id"] == .string("42"))
    #expect(await server.keys == 1)
    #expect(await server.polls == 2)
    #expect(await server.profiles == 2)
    let prompts = recorder.prompts
    #expect(Set(prompts.compactMap(\.qrURL)).count == 1)
    #expect(prompts.filter { $0.message.contains("正在恢复登录") }.count == 2)
    #expect(prompts.last?.message == "登录成功")
    let diagnostic = try JSONDecoder().decode(PluginJSON.self, from: Data(contentsOf: data.appendingPathComponent("sign-in-diagnostics.json")))
    #expect(diagnostic["completed"] == .bool(true))
    let restored = try PluginRuntime(directory: root, dataDirectory: data, manifest: manifest, transport: { request, _ in
        try await server.respond(request)
    })
    #expect(try await PluginSource(manifest: manifest, runtime: restored).account()?.id == "42")
}
