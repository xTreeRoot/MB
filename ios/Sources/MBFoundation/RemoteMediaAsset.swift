import AVFoundation
import Foundation
import UniformTypeIdentifiers

enum MediaStreamingFailure: LocalizedError {
    case rangeStatus(Int), rangeHeader, rangeData, contentType, unplayable, changed, fileBudget
    var errorDescription: String? {
        switch self {
        case .rangeStatus(let status): AppLocalization.text("媒体分段请求失败（HTTP %lld）", arguments: [status])
        case .rangeHeader: AppLocalization.text("媒体服务器未返回有效的分段范围")
        case .rangeData: AppLocalization.text("媒体分段数据与请求偏移不一致")
        case .contentType: AppLocalization.text("媒体类型不受原生播放器支持")
        case .unplayable: AppLocalization.text("原生播放器无法识别此媒体")
        case .changed: "媒体地址或内容已更新，需重新加载。"
        case .fileBudget: "媒体不支持分段，且完整文件超出临时下载容量。"
        }
    }

    static func transient(_ error: any Error) -> Bool {
        if TrackProblem.isConnectionError(error) { return true }
        if case Self.rangeStatus(let status) = error { return (500...599).contains(status) }
        return false
    }
    static func allowsRefresh(_ error: any Error) -> Bool {
        if transient(error) { return true }
        switch error {
        case Self.changed, Self.rangeStatus(401), Self.rangeStatus(410), NetworkFailure.http(401), NetworkFailure.http(410): return true
        default: return false
        }
    }
    static func allowsWholeFile(_ error: any Error) -> Bool {
        switch error {
        case Self.rangeStatus(200), Self.contentType, Self.unplayable: return true
        default: return false
        }
    }
}

/// Content-Range 必须对应请求偏移，不能把整段响应误当成 seek 后的数据。
struct MediaByteRange {
    let start: Int64
    let end: Int64
    let total: Int64
    init(response: HTTPURLResponse, offset: Int64, count: Int, requestedEnd: Int64? = nil) throws {
        guard response.statusCode == 206 else { throw MediaStreamingFailure.rangeStatus(response.statusCode) }
        guard let header = response.value(forHTTPHeaderField: "Content-Range"),
              header.hasPrefix("bytes ") else { throw MediaStreamingFailure.rangeHeader }
        let parts = header.dropFirst(6).split(separator: "/")
        guard parts.count == 2, let total = Int64(parts[1]) else { throw MediaStreamingFailure.rangeHeader }
        let bounds = parts[0].split(separator: "-")
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]),
              start == offset, start >= 0, end >= start, total > end, Int64(count) == end - start + 1,
              requestedEnd.map({ min($0, total - 1) == end }) ?? true,
              [nil, "identity"].contains(response.value(forHTTPHeaderField: "Content-Encoding")) else { throw MediaStreamingFailure.rangeData }
        self.start = start
        self.end = end
        self.total = total
    }
}

@MainActor final class MediaSegmentReader {
    nonisolated static let block: Int64 = 256 << 10
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    private let resource: MediaResource
    private let transport: Transport
    private var segments: [Int64: Data] = [:]
    private var order: [Int64] = []
    private(set) var length: Int64?
    private var validator: String?

    init(resource: MediaResource, transport: @escaping Transport) { self.resource = resource; self.transport = transport }

    func read(offset: Int64, end: Int64) async throws -> Data {
        guard offset >= 0, offset < end, length.map({ offset < $0 }) ?? true else { return Data() }
        let blockStart = offset / Self.block * Self.block
        var bytes: Data
        if let cached = segments[blockStart] { bytes = cached }
        else {
            let last = min(blockStart + (Self.block - 1), (length ?? Int64.max) - 1)
            let (body, range, tag) = try await fetch(blockStart, last)
            try Task.checkCancellation()
            if let length, length != range.total { throw MediaStreamingFailure.changed }
            if let validator, let tag, validator != tag { throw MediaStreamingFailure.changed }
            if length == nil { validator = tag }
            length = range.total
            if segments[blockStart] == nil {
                if order.count >= 16 { segments.removeValue(forKey: order.removeFirst()) }
                segments[blockStart] = body; order.append(blockStart)
            }
            bytes = body
        }
        let start = Int(offset - blockStart)
        guard start < bytes.count else { return Data() }
        return bytes.subdata(in: start..<min(bytes.count, Int(min(end - blockStart, Self.block))))
    }

    private func fetch(_ first: Int64, _ last: Int64) async throws -> (Data, MediaByteRange, String?) {
        for attempt in 0...2 {
            try Task.checkCancellation()
            var request = URLRequest(url: resource.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
            request.allHTTPHeaderFields = resource.headers
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.setValue("bytes=\(first)-\(last)", forHTTPHeaderField: "Range")
            request.setValue(validator, forHTTPHeaderField: "If-Range")
            do {
                let (data, response) = try await transport(request)
                guard let http = response as? HTTPURLResponse else { throw NetworkFailure.response }
                if validator != nil && [200, 416].contains(http.statusCode) { throw MediaStreamingFailure.changed }
                let range = try MediaByteRange(response: http, offset: first, count: data.count, requestedEnd: last)
                let tag = http.value(forHTTPHeaderField: "ETag").flatMap { $0.first == "\"" && $0.last == "\"" ? $0 : nil }
                return (data, range, tag)
            } catch where attempt < 2 && MediaStreamingFailure.transient(error) {
                try await Task.sleep(for: .milliseconds(180 * (attempt + 1)))
            }
        }
        throw NetworkFailure.response
    }
}

/// AVFoundation 只看虚拟媒体 URL；真实 Range 请求复用宿主网络层，保留代理、请求头和取消语义。
/// delegate 固定在 main queue 调用；耗时工作使用 URLSession async，不在主线程同步取远程轨道。
@MainActor final class RemoteMediaAsset: NSObject, @preconcurrency AVAssetResourceLoaderDelegate {
    let asset: AVURLAsset
    private let resource: MediaResource
    private let reader: MediaSegmentReader
    private(set) var lastFailure: (any Error)?
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var cancelled = false

    init(resource: MediaResource, transport: @escaping MediaSegmentReader.Transport = {
        try await NetworkClient.shared.data(for: $0, maximumBytes: Int(MediaSegmentReader.block), acceptingBody: { $0 == 206 })
    }) throws {
        guard let virtual = URL(string: "mbmedia://play/\(UUID()).\(resource.fileExtension)") else { throw NetworkFailure.response }
        self.resource = resource
        reader = MediaSegmentReader(resource: resource, transport: transport)
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
                if !request.isCancelled, !Task.isCancelled, !cancelled { lastFailure = error; request.finishLoading(with: error) }
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
            let data = try await reader.read(offset: offset, end: end)
            try Task.checkCancellation()
            guard !loading.isCancelled, !cancelled, let total = reader.length else { return }
            end = min(end, total)
            if let information = loading.contentInformationRequest {
                let type = UTType(filenameExtension: resource.fileExtension)?.identifier ?? UTType.mpeg4Movie.identifier
                if let allowed = information.allowedContentTypes, !allowed.isEmpty, !allowed.contains(type) { throw MediaStreamingFailure.contentType }
                information.contentType = type
                information.contentLength = total
                information.isByteRangeAccessSupported = true
            }
            if let dataRequest, !data.isEmpty { dataRequest.respond(with: data) }
            else { return }
            offset += Int64(data.count)
        } while offset < end
    }
}
