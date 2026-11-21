import Foundation
import MBFoundation

extension BilibiliSource {
    func loadSession() async throws {
        guard !sessionLoaded else { return }
        if sessionLoading == nil {
            let store = sessionStore
            sessionLoading = Task { try await store?.load() }
        }
        do {
            let loaded = try await sessionLoading?.value
            if !sessionLoaded { session = loaded; sessionLoaded = true }
            sessionLoading = nil
        } catch { sessionLoading = nil; throw error }
    }

    public func account() async throws -> SourceAccount? {
        try await loadSession()
        guard session?.active == true, let cookie = session?.cookie, !cookie.isEmpty else { return nil }
        let revision = accountRevision
        let found = try await checkCookie(cookie)
        guard revision == accountRevision else { throw CancellationError() }
        accountSnapshot = found
        if found == nil { try await forgetExpiredCookie() }
        return found
    }

    func requireAccount() async throws -> SourceAccount {
        try await loadSession()
        guard session?.active == true else { throw BilibiliError.signInRequired }
        if let accountSnapshot { return accountSnapshot }
        guard let account = try await account() else { throw BilibiliError.signInRequired }
        return account
    }

    public func signOut() async throws {
        try await loadSession()
        accountRevision += 1
        clearAccountCaches()
        let disabled = BilibiliSession(cookie: session?.cookie ?? "", active: false)
        session = disabled
        try await sessionStore?.save(disabled)
    }

    func forgetExpiredCookie() async throws {
        accountRevision += 1
        clearAccountCaches()
        let empty = BilibiliSession(cookie: "", active: false)
        session = empty
        try await sessionStore?.save(empty)
    }

    private func checkCookie(_ cookie: String) async throws -> SourceAccount? {
        let result: APIEnvelope<LoginNavigationDTO> = try await request(path: "/x/web-interface/nav", cookieOverride: cookie)
        if result.code == -101 { return nil }
        guard result.code == 0 else { throw BilibiliError.api(result.code) }
        guard let nav = result.data, nav.isLogin == true, let mid = nav.mid, mid.number > 0 else { return nil }
        return SourceAccount(id: mid.value, name: nav.uname ?? mid.value, avatarURL: VideoDTO.coverURL(nav.face))
    }

    /// 验证已保存 Cookie 后才复用；二维码过程与取消、退出按代次隔离。
    public func signIn(prompt: @escaping @Sendable (SignInPrompt) async -> Void) async throws -> SourceAccount {
        try await loadSession()
        accountRevision += 1
        let revision = accountRevision
        let previous = session
        if let cookie = previous?.cookie, !cookie.isEmpty,
           let existing = try await checkCookie(cookie) {
            return try await activate(cookie, account: existing, revision: revision, previous: previous)
        }
        var qr = try await newQRCode()
        await prompt(SignInPrompt(qrURL: qr.url, message: "用哔哩哔哩 App 扫码；同一手机可保存二维码后从相册识别"))
        for _ in 0..<140 {
            try await pollDelay()
            try checkAttempt(revision)
            let url = URL(string: "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?" + Self.form(["qrcode_key": qr.key]))!
            var request = URLRequest(url: url)
            request.allHTTPHeaderFields = Self.headers
            let (data, response) = try await transport(request)
            try checkAttempt(revision)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw BilibiliError.invalidResponse }
            let result = try JSONDecoder().decode(APIEnvelope<LoginPollDTO>.self, from: data)
            guard result.code == 0, let poll = result.data else { throw BilibiliError.api(result.code) }
            switch poll.code {
            case 86101: break
            case 86090:
                await prompt(SignInPrompt(qrURL: qr.url, message: "已扫码，请在哔哩哔哩 App 确认登录"))
            case 86038:
                qr = try await newQRCode()
                try checkAttempt(revision)
                await prompt(SignInPrompt(qrURL: qr.url, message: "二维码已刷新，请扫描新二维码"))
            case 0:
                let cookie = try Self.loginCookie(http, callback: poll.url)
                guard let account = try await checkCookie(cookie) else { throw BilibiliError.invalidLogin }
                return try await activate(cookie, account: account, revision: revision, previous: previous)
            default: throw BilibiliError.invalidLogin
            }
        }
        throw BilibiliError.invalidLogin
    }

    private func checkAttempt(_ revision: Int) throws {
        try Task.checkCancellation()
        guard revision == accountRevision else { throw CancellationError() }
    }

    private func activate(_ cookie: String, account: SourceAccount, revision: Int,
                          previous: BilibiliSession?) async throws -> SourceAccount {
        try checkAttempt(revision)
        let enabled = BilibiliSession(cookie: cookie, active: true)
        session = enabled
        clearAccountCaches()
        accountSnapshot = account
        do {
            try await sessionStore?.save(enabled)
            try checkAttempt(revision)
            return account
        } catch {
            if revision == accountRevision {
                session = previous
                clearAccountCaches()
                try await sessionStore?.save(previous ?? BilibiliSession(cookie: "", active: false))
            }
            throw error
        }
    }

    private func newQRCode() async throws -> (url: URL, key: String) {
        let result: APIEnvelope<QRCodeDTO> = try await request(path: "/x/passport-login/web/qrcode/generate",
            origin: "https://passport.bilibili.com", cookieOverride: "")
        guard result.code == 0 else { throw BilibiliError.api(result.code) }
        // 生成接口位于 passport 域名，实际扫码链接由 account 域名承载。
        guard let data = result.data, !data.qrcode_key.isEmpty,
              let url = URL(string: data.url), url.scheme == "https",
              ["account.bilibili.com", "passport.bilibili.com"].contains(url.host ?? "") else {
            throw BilibiliError.invalidResponse
        }
        return (url, data.qrcode_key)
    }

    static func loginCookie(_ response: HTTPURLResponse, callback: String?) throws -> String {
        let allowed = Set(["SESSDATA", "DedeUserID", "DedeUserID__ckMd5", "bili_jct", "sid"])
        var values: [String: String] = [:]
        let headers = Dictionary(uniqueKeysWithValues: response.allHeaderFields.map { (String(describing: $0.key), String(describing: $0.value)) })
        for cookie in HTTPCookie.cookies(withResponseHeaderFields: headers, for: response.url!) where allowed.contains(cookie.name) {
            values[cookie.name] = cookie.value
        }
        if !["SESSDATA", "DedeUserID", "bili_jct"].allSatisfy({ !(values[$0] ?? "").isEmpty }) {
            guard let callback, let url = URL(string: callback), url.scheme == "https",
                  url.host == "www.bilibili.com" || (url.host == "passport.biligame.com" && url.path == "/crossDomain") else {
                throw BilibiliError.invalidLogin
            }
            for item in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] where allowed.contains(item.name) {
                if values[item.name] == nil, let value = item.value, !value.contains(";"), !value.contains("\n"), !value.contains("\r") {
                    values[item.name] = value
                }
            }
        }
        guard ["SESSDATA", "DedeUserID", "bili_jct"].allSatisfy({ !(values[$0] ?? "").isEmpty }) else { throw BilibiliError.invalidLogin }
        return values.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }
}

private struct LoginNavigationDTO: Decodable {
    let isLogin: Bool?; let mid: ScalarText?; let uname: String?; let face: String?
}
private struct QRCodeDTO: Decodable { let url: String; let qrcode_key: String }
private struct LoginPollDTO: Decodable { let code: Int; let url: String? }
