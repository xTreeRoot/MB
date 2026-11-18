import Foundation
import MBFoundation
@testable import MBBilibili
import Testing

private let testCookie = "SESSDATA=test-session; DedeUserID=42; bili_jct=test-csrf"
private let loggedIn = #"{"code":0,"data":{"isLogin":true,"mid":42,"uname":"测试账号","face":"//i0.hdslb.com/avatar.jpg"}}"#

private actor SessionMemory: BilibiliSessionStore {
    var value: BilibiliSession?
    init(_ value: BilibiliSession?) { self.value = value }
    func load() -> BilibiliSession? { value }
    func save(_ session: BilibiliSession) { value = session }
}

private actor AccountRoutes {
    var bodies: [String: [String]]
    var calls: [URLRequest] = []
    init(_ bodies: [String: [String]]) { self.bodies = bodies }
    func respond(_ request: URLRequest) throws -> (Data, URLResponse) {
        calls.append(request)
        let url = try #require(request.url)
        var route = bodies[url.path] ?? [#"{"code":-404}"#]
        let body = route.removeFirst()
        if !route.isEmpty { bodies[url.path] = route }
        return (Data(body.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)))
    }
}

private func accountSource(_ routes: AccountRoutes, store: SessionMemory,
                           delay: @escaping @Sendable () async throws -> Void = {}) -> BilibiliSource {
    BilibiliSource(transport: { try await routes.respond($0) }, now: { Date(timeIntervalSince1970: 1_702_204_169) },
                   sessionStore: store, pollDelay: delay)
}

@Test func signOutKeepsCookieDisabledAcrossRelaunchAndSignInValidatesBeforeReuse() async throws {
    let memory = SessionMemory(BilibiliSession(cookie: testCookie, active: true))
    let routes = AccountRoutes(["/x/web-interface/nav": [loggedIn]])
    let source = accountSource(routes, store: memory)
    #expect(try await source.account()?.id == "42")
    try await source.signOut()
    #expect(await memory.value == BilibiliSession(cookie: testCookie, active: false))
    let reopened = accountSource(routes, store: memory)
    #expect(try await reopened.account() == nil)
    let result = try await reopened.signIn { _ in Issue.record("有效 Cookie 不应生成二维码") }
    #expect(result.name == "测试账号")
    #expect(await memory.value?.active == true)
    let calls = await routes.calls
    #expect(calls.count == 2)
    #expect(calls.allSatisfy { $0.url?.path == "/x/web-interface/nav" && $0.value(forHTTPHeaderField: "Cookie") == testCookie })
}

@Test func confirmedExpiredCookieIsClearedButHTTPFailurePreservesSession() async throws {
    let memory = SessionMemory(BilibiliSession(cookie: testCookie, active: true))
    let routes = AccountRoutes(["/x/web-interface/nav": [#"{"code":-352}"#, #"{"code":-101,"data":{}}"#]])
    let source = accountSource(routes, store: memory)
    await #expect(throws: BilibiliError.api(-352)) { try await source.account() }
    #expect(await memory.value?.cookie == testCookie)
    #expect(try await source.account() == nil)
    #expect(await memory.value == BilibiliSession(cookie: "", active: false))
}

private actor Prompts { var values: [SignInPrompt] = []; func add(_ value: SignInPrompt) { values.append(value) } }

@Test func qrLoginRefreshesExpiredCodeConfirmsSessionAndPersistsOnlyAfterValidation() async throws {
    let memory = SessionMemory(nil), prompts = Prompts()
    let routes = AccountRoutes([
        "/x/passport-login/web/qrcode/generate": [
            #"{"code":0,"data":{"url":"https://passport.bilibili.com/scan?key=one","qrcode_key":"one"}}"#,
            #"{"code":0,"data":{"url":"https://passport.bilibili.com/scan?key=two","qrcode_key":"two"}}"#,
        ],
        "/x/passport-login/web/qrcode/poll": [
            #"{"code":0,"data":{"code":86038}}"#, #"{"code":0,"data":{"code":86090}}"#,
            #"{"code":0,"data":{"code":0,"url":"https://passport.biligame.com/crossDomain?SESSDATA=test-session&DedeUserID=42&bili_jct=test-csrf"}}"#,
        ],
        "/x/web-interface/nav": [loggedIn],
    ])
    let result = try await accountSource(routes, store: memory).signIn { await prompts.add($0) }
    #expect(result.id == "42")
    #expect(await memory.value?.active == true)
    let messages = await prompts.values
    #expect(messages.count == 3)
    #expect(messages[1].qrURL.query == "key=two")
    #expect(messages.last?.message.contains("确认") == true)
    let calls = await routes.calls
    let polls = calls.filter { $0.url?.path.hasSuffix("/poll") == true }
    #expect(polls.map { $0.url?.query } == ["qrcode_key=one", "qrcode_key=two", "qrcode_key=two"])
}

private actor PollGate {
    var waiting: CheckedContinuation<Void, Never>?
    var observer: CheckedContinuation<Void, Never>?
    func pause() async { await withCheckedContinuation { waiting = $0; observer?.resume(); observer = nil } }
    func wait() async { if waiting == nil { await withCheckedContinuation { observer = $0 } } }
    func release() { waiting?.resume(); waiting = nil }
}

@Test func cancelledLoginCannotPollOrSaveLateSession() async throws {
    let memory = SessionMemory(nil), gate = PollGate()
    let routes = AccountRoutes(["/x/passport-login/web/qrcode/generate": [#"{"code":0,"data":{"url":"https://passport.bilibili.com/scan","qrcode_key":"one"}}"#]])
    let source = accountSource(routes, store: memory, delay: { await gate.pause() })
    let task = Task { try await source.signIn { _ in } }
    await gate.wait()
    task.cancel()
    await gate.release()
    await #expect(throws: CancellationError.self) { try await task.value }
    #expect(await memory.value == nil)
    #expect(await routes.calls.count == 1)
}

@Test(arguments: ["http://www.bilibili.com/?SESSDATA=a&DedeUserID=42&bili_jct=b", "https://evil.test/?SESSDATA=a&DedeUserID=42&bili_jct=b", "https://passport.biligame.com/other?SESSDATA=a&DedeUserID=42&bili_jct=b"])
func qrCookieRejectsUntrustedCallback(_ callback: String) throws {
    let response = try #require(HTTPURLResponse(url: URL(string: "https://passport.bilibili.com/poll")!, statusCode: 200, httpVersion: nil, headerFields: nil))
    #expect(throws: BilibiliError.invalidLogin) { try BilibiliSource.loginCookie(response, callback: callback) }
}

@Test func favoriteFolderPaginationMapsUpperAndNumericHasMore() async throws {
    let routes = AccountRoutes(["/x/v3/fav/resource/list": [#"{"code":0,"data":{"info":{"id":9,"title":"收藏"},"has_more":1,"medias":[{"bvid":"BV123","title":"视频","cover":"//i0.hdslb.com/c.jpg","duration":61,"upper":{"mid":8,"name":"作者"}}]}}"#]])
    let source = accountSource(routes, store: SessionMemory(nil))
    let page = try await source.library(.folder("9"), cursor: "2")
    #expect(page.title == "收藏")
    #expect(page.tracks.first?.artistID == "8")
    #expect(page.tracks.first?.artist == "作者")
    #expect(page.nextCursor == "3")
    let url = try #require(await routes.calls.first?.url)
    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    #expect(items.contains { $0.name == "pn" && $0.value == "2" })
}

@Test func historyUsesServerCursorAndSkipsOtherMediaWithoutStoppingPagination() async throws {
    let body = #"{"code":0,"data":{"list":[{"title":"节目","history":{"business":"pgc"}},{"title":"历史视频","author_name":"作者","author_mid":8,"history":{"business":"archive","bvid":"BV123"}}],"cursor":{"max":100,"view_at":200,"business":"archive"}}}"#
    let routes = AccountRoutes(["/x/web-interface/nav": [loggedIn], "/x/web-interface/history/cursor": [body]])
    let source = accountSource(routes, store: SessionMemory(BilibiliSession(cookie: testCookie, active: true)))
    let first = try await source.library(.history, cursor: nil)
    #expect(first.tracks.map(\.title) == ["历史视频"])
    let cursor = try #require(first.nextCursor)
    let second = try await source.library(.history, cursor: cursor)
    #expect(second.nextCursor == nil)
    let url = try #require(await routes.calls.last?.url)
    let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    #expect(query.contains { $0.name == "max" && $0.value == "100" })
    #expect(query.contains { $0.name == "view_at" && $0.value == "200" })
}

@Test func likeUsesValidatedSessionCSRFAndFormEncodingWithoutLeakingCookieInURL() async throws {
    let routes = AccountRoutes([
        "/x/web-interface/nav": [loggedIn], "/x/web-interface/view": [#"{"code":0,"data":{"aid":123,"cid":456}}"#],
        "/x/web-interface/archive/like": [#"{"code":0,"data":null}"#],
    ])
    let source = accountSource(routes, store: SessionMemory(BilibiliSession(cookie: testCookie, active: true)))
    try await source.like(trackID: "BV123", enabled: false)
    let request = try #require(await routes.calls.last)
    #expect(request.httpMethod == "POST")
    let body = String(data: try #require(request.httpBody), encoding: .utf8)
    #expect(body?.contains("like=2") == true)
    #expect(body?.contains("csrf=test-csrf") == true)
    #expect(request.url?.query == nil)
    #expect(request.value(forHTTPHeaderField: "Cookie") == testCookie)
    #expect(BilibiliSource.form(["message": "中文+a&b"]).contains("%2B"))
}

@Test func guestMutationDoesNotSendAnyNetworkRequest() async {
    let routes = AccountRoutes([:])
    let source = accountSource(routes, store: SessionMemory(nil))
    await #expect(throws: BilibiliError.signInRequired) { try await source.like(trackID: "BV123", enabled: true) }
    await #expect(throws: BilibiliError.signInRequired) { try await source.library(.favorites, cursor: nil) }
    #expect(await routes.calls.isEmpty)
}
