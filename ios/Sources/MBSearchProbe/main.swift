import AVFoundation
import CoreVideo
import Foundation
import ImageIO
import MBBilibili
import MBFoundation

/// 手动联网验证真实游客合同和原生解码；不输出地址、签名、请求头或会话。
@main struct SearchProbe {
    @MainActor static func main() async {
        var args = Array(CommandLine.arguments.dropFirst())
        let mode = args.first?.hasPrefix("--") == true ? args.removeFirst() : "--search"
        let query = args.isEmpty ? "纯音乐" : args.joined(separator: " ")
        let source = BilibiliSource()
        do {
            if mode == "--artwork" {
                for section in try await source.home() where !section.tracks.isEmpty {
                    var loaded = 0
                    for track in section.tracks {
                        guard let url = track.artworkURL, url.scheme == "https" else { throw ProbeFailure.artwork }
                        var request = URLRequest(url: url); request.allHTTPHeaderFields = track.artworkHeaders
                        let (data, response) = try await NetworkClient.shared.data(for: request)
                        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
                              let image = CGImageSourceCreateWithData(data as CFData, nil),
                              CGImageSourceCreateImageAtIndex(image, 0, nil) != nil else { throw ProbeFailure.artwork }
                        loaded += 1
                    }
                    print("\(section.category.title)：\(loaded)/\(section.tracks.count) 张 HTTPS 封面下载和原生解码成功")
                }
                return
            }
            if mode == "--home" {
                for section in try await source.home() {
                    print("\(section.category.title)：\(section.tracks.count) 条；失败：\(section.errorMessage != nil)")
                    do {
                        let all = try await source.discovery(section.category)
                        print("查看更多：\(all.tracks.count) 条")
                    } catch { print("查看更多失败：\(error.localizedDescription)") }
                }
                return
            }
            let page = try await source.search(query: query, page: 1)
            print("游客搜索成功：\(page.tracks.count) 条；还有下一页：\(page.hasMore)")
            guard ["--audio", "--video", "--stream"].contains(mode) else { return }
            guard let track = page.tracks.filter({ $0.durationMilliseconds > 0 && $0.durationMilliseconds < 600_000 }).min(by: { $0.durationMilliseconds < $1.durationMilliseconds }) else {
                throw ProbeFailure.noShortTrack
            }
            let resource = try await (mode == "--audio" ? source.audio(trackID: track.sourceTrackID) : source.video(trackID: track.sourceTrackID))
            if mode == "--stream" {
                var firstRequest = URLRequest(url: resource.url)
                firstRequest.allHTTPHeaderFields = resource.headers
                firstRequest.setValue("bytes=0-1", forHTTPHeaderField: "Range")
                let (firstBytes, firstResponse) = try await NetworkClient.shared.data(for: firstRequest)
                if let http = firstResponse as? HTTPURLResponse {
                    print("媒体分段：HTTP \(http.statusCode)；\(firstBytes.count) 字节；Content-Range：\(http.value(forHTTPHeaderField: "Content-Range") ?? "无")")
                }
                let engine = NativeVideoEngine()
                try await engine.load(resource: resource, at: 0)
                let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
                engine.player.currentItem?.add(output)
                engine.sync(positionMilliseconds: 0, playing: true)
                var decoded = false
                // 仅联网探针等待解码帧；产品流程不插入等待。
                for _ in 0..<400 {
                    try await Task.sleep(for: .milliseconds(25))
                    if output.copyPixelBuffer(forItemTime: engine.player.currentTime(), itemTimeForDisplay: nil) != nil { decoded = true; break }
                    if engine.errorMessage != nil { break }
                }
                engine.stop()
                guard decoded else { throw ProbeFailure.decode }
                print("Range 分段加载与原生视频画面输出成功")
                return
            }
            let local = try await NetworkClient.shared.download(resource)
            defer { try? FileManager.default.removeItem(at: local) }
            if mode == "--audio" {
                let file = try AVAudioFile(forReading: local)
                guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 4096) else { throw ProbeFailure.decode }
                try file.read(into: buffer)
                guard buffer.frameLength > 0 else { throw ProbeFailure.decode }
                print("真实音频下载并解码 PCM 成功：\(buffer.frameLength) 帧")
            } else {
                let asset = AVURLAsset(url: local)
                guard try await asset.load(.isPlayable), let track = try await asset.loadTracks(withMediaType: .video).first else { throw ProbeFailure.decode }
                let reader = try AVAssetReader(asset: asset)
                let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
                reader.add(output)
                guard reader.startReading(), let sample = output.copyNextSampleBuffer(), CMSampleBufferGetImageBuffer(sample) != nil else { throw ProbeFailure.decode }
                reader.cancelReading()
                print("真实视频下载并解码画面成功")
            }
        } catch {
            print("游客验证失败：\(error.localizedDescription)")
            exit(1)
        }
    }
}
private enum ProbeFailure: LocalizedError {
    case noShortTrack, decode, artwork
    var errorDescription: String? {
        switch self {
        case .noShortTrack: "未找到十分钟内的验证曲目"
        case .decode: "原生解码未得到有效帧"
        case .artwork: "封面地址、下载或原生图像解码失败"
        }
    }
}
