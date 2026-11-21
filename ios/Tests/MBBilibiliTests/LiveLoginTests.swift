import Foundation
import MBFoundation
@testable import MBBilibili
import Testing

private actor LiveLoginObservation {
    var hosts: [String] = []
    var pollCodes: [Int] = []
    private var delays = 0

    func prompt(_ value: SignInPrompt) { hosts.append(value.qrURL?.host ?? "") }
    func poll(_ code: Int) { pollCodes.append(code) }
    func delay() throws {
        delays += 1
        if delays > 1 { throw CancellationError() }
    }
}

private struct LiveLoginPoll: Decodable { let code: Int }

/// 手动联网验收：不读写账号存储，收到未扫码状态后取消，不输出二维码密钥或 Cookie。
@Test(.enabled(if: ProcessInfo.processInfo.environment["MB_BILIBILI_LIVE_LOGIN"] == "1"))
func liveQRCodeCanBeGeneratedPolledAndRequestedAgain() async throws {
    for _ in 0..<2 {
        let observed = LiveLoginObservation()
        let network = NetworkClient()
        let source = BilibiliSource(transport: { request in
            let response = try await network.data(for: request)
            if request.url?.path == "/x/passport-login/web/qrcode/poll" {
                let result = try JSONDecoder().decode(APIEnvelope<LiveLoginPoll>.self, from: response.0)
                if let data = result.data { await observed.poll(data.code) }
            }
            return response
        }, now: { Date() }, sessionStore: nil, pollDelay: { try await observed.delay() })
        await #expect(throws: CancellationError.self) {
            try await source.signIn { await observed.prompt($0) }
        }
        #expect(await observed.hosts == ["account.bilibili.com"])
        #expect(await observed.pollCodes == [86101])
        #expect(try await source.account() == nil)
    }
}
