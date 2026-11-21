import AVFoundation
import Foundation
import UniformTypeIdentifiers

enum MediaStreamingFailure: LocalizedError {
    case rangeStatus(Int), rangeHeader, rangeData, contentType, unplayable
    var errorDescription: String? {
        switch self {
        case .rangeStatus(let status): AppLocalization.text("媒体分段请求失败（HTTP %lld）", arguments: [status])
        case .rangeHeader: AppLocalization.text("媒体服务器未返回有效的分段范围")
        case .rangeData: AppLocalization.text("媒体分段数据与请求偏移不一致")
        case .contentType: AppLocalization.text("媒体类型不受原生播放器支持")
        case .unplayable: AppLocalization.text("原生播放器无法识别此媒体")
        }
    }
}

/// Content-Range 必须对应请求偏移，不能把整段响应误当成 seek 后的数据。
struct MediaByteRange {
    let start: Int64
    let end: Int64
    let total: Int64
    init(response: HTTPURLResponse, offset: Int64, count: Int) throws {
        guard response.statusCode == 206 else { throw MediaStreamingFailure.rangeStatus(response.statusCode) }
        guard let header = response.value(forHTTPHeaderField: "Content-Range"),
              header.hasPrefix("bytes ") else { throw MediaStreamingFailure.rangeHeader }
        let parts = header.dropFirst(6).split(separator: "/")
        guard parts.count == 2, let total = Int64(parts[1]) else { throw MediaStreamingFailure.rangeHeader }
        let bounds = parts[0].split(separator: "-")
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]),
              start == offset, start >= 0, end >= start, total > end, Int64(count) == end - start + 1 else { throw MediaStreamingFailure.rangeData }
        self.start = start
        self.end = end
        self.total = total
    }
}

/// AVFoundation 只看虚拟媒体 URL；真实 Range 请求复用宿主网络层，保留代理、请求头和取消语义。
/// delegate 固定在 main queue 调用；耗时工作使用 URLSession async，不在主线程同步取远程轨道。
@MainActor final class RemoteMediaAsset: NSObject, @preconcurrency AVAssetResourceLoaderDelegate {
    let asset: AVURLAsset
    private let resource: MediaResource
    private let transport: @Sendable (URLRequest) async throws -> (Data, URLResponse)
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var cancelled = false

    init(resource: MediaResource, transport: @escaping @Sendable (URLRequest) async throws -> (Data, URLResponse) = { try await NetworkClient.shared.data(for: $0) }) throws {
        guard let virtual = URL(string: "mbmedia://play/\(UUID()).\(resource.fileExtension)") else { throw NetworkFailure.response }
        self.resource = resource
        self.transport = transport
        asset = AVURLAsset(url: virtual)
        super.init()
        asset.resourceLoader.setDelegate(self, queue: .main)
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        guard !cancelled else { return false }
        let key = ObjectIdentifier(request)
        tasks[key] = Task {
            defer { tasks[key] = nil }
            do {
                try await serve(request)
                if !request.isCancelled { request.finishLoading() }
            } catch {
                if !request.isCancelled { request.finishLoading(with: error) }
            }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: ObjectIdentifier(request))?.cancel()
    }

    func cancel() {
        cancelled = true
        asset.cancelLoading()
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
    }

    private func serve(_ loading: AVAssetResourceLoadingRequest) async throws {
        let dataRequest = loading.dataRequest
        var offset = dataRequest?.currentOffset ?? 0
        let length = dataRequest?.requestedLength ?? 2
        let all = dataRequest?.requestsAllDataToEndOfResource == true
        guard offset >= 0, length >= 0 else { throw NetworkFailure.response }
        let requestedOffset = dataRequest?.requestedOffset ?? 0
        let (requestedEnd, overflow) = requestedOffset.addingReportingOverflow(Int64(length))
        guard all || !overflow else { throw NetworkFailure.response }
        var end = all ? Int64.max : requestedEnd
        repeat {
            try Task.checkCancellation()
            guard !loading.isCancelled, !cancelled, offset < end else { return }
            let chunk = min(Int64(512 * 1024), end - offset)
            var request = URLRequest(url: resource.url)
            request.allHTTPHeaderFields = resource.headers
            request.setValue("bytes=\(offset)-\(offset + chunk - 1)", forHTTPHeaderField: "Range")
            let (data, response) = try await transport(request)
            try Task.checkCancellation()
            guard !loading.isCancelled, !cancelled, let http = response as? HTTPURLResponse else { return }
            let range = try MediaByteRange(response: http, offset: offset, count: data.count)
            end = min(end, range.total)
            if let information = loading.contentInformationRequest {
                let type = UTType(filenameExtension: resource.fileExtension)?.identifier ?? UTType.mpeg4Movie.identifier
                if let allowed = information.allowedContentTypes, !allowed.isEmpty, !allowed.contains(type) { throw MediaStreamingFailure.contentType }
                information.contentType = type
                information.contentLength = range.total
                information.isByteRangeAccessSupported = true
            }
            if let dataRequest { dataRequest.respond(with: data) }
            else { return }
            offset = range.end + 1
        } while offset < end
    }
}
