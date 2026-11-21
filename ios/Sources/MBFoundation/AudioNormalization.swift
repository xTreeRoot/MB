import AVFoundation
import Foundation
import MBSignal
import MediaToolbox

public enum AudioNormalization: String, Codable, CaseIterable, Identifiable, Sendable {
    case standard, dynamic, off
    public var id: String { rawValue }
    public var title: String { switch self { case .standard: "标准"; case .dynamic: "动态"; case .off: "关闭" } }
    var signalMode: Int32 { switch self { case .off: 0; case .standard: 1; case .dynamic: 2 } }
}

@MainActor public protocol AudioEngineNormalization: AnyObject { func setNormalization(_ mode: AudioNormalization) }

/// 使用 AVPlayer 公开音频 tap 接入处理；参数原子传递，音频回调不分配内存或调用 Swift。
final class AudioNormalizer {
    private let signal: OpaquePointer
    init() throws {
        guard let value = MBSignalCreate() else { throw CocoaError(.coderInvalidValue) }; signal = value
    }
    var bassLevel: Double { MBSignalBass(signal) }
    func configure(_ mode: AudioNormalization, gain: Double) { MBSignalConfigure(signal, mode.signalMode, gain) }
    func mix(track: AVAssetTrack) throws -> AVAudioMix {
        var unmanaged: Unmanaged<MTAudioProcessingTap>?
        let status = MBSignalCreateTap(signal, &unmanaged)
        guard status == noErr, let unmanaged else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        let input = AVMutableAudioMixInputParameters(track: track)
        input.audioTapProcessor = unmanaged.takeRetainedValue()
        let mix = AVMutableAudioMix(); mix.inputParameters = [input]; return mix
    }
    deinit { MBSignalRelease(signal) }
}

/// 在独立 actor 用系统解码器测量全曲，0.4 秒门控能量与 main 的固定增益算法一致。
actor LoudnessAnalyzer {
    func gain(asset: AVURLAsset) async throws -> Double {
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else { return 1 }
        let descriptions = try await track.load(.formatDescriptions)
        guard let description = descriptions.first, let format = CMAudioFormatDescriptionGetStreamBasicDescription(description) else { return 1 }
        let channels = Int(format.pointee.mChannelsPerFrame), rate = Int(format.pointee.mSampleRate)
        guard channels > 0, rate > 0 else { return 1 }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMIsFloatKey: true, AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsNonInterleaved: false, AVLinearPCMIsBigEndianKey: false,
        ])
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { return 1 }; reader.add(output)
        guard reader.startReading() else { throw reader.error ?? NetworkFailure.response }
        defer { reader.cancelReading() }
        var meter = LoudnessMeasurement(sampleRate: rate, channels: channels)
        while let sample = output.copyNextSampleBuffer() {
            try Task.checkCancellation()
            guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
            var length = 0, total = 0
            var pointer: UnsafeMutablePointer<Int8>?
            guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: &length, totalLengthOut: &total, dataPointerOut: &pointer) == kCMBlockBufferNoErr,
                  length == total, let pointer, total.isMultiple(of: MemoryLayout<Float>.size) else { throw NetworkFailure.response }
            pointer.withMemoryRebound(to: Float.self, capacity: total / 4) { floats in meter.append(UnsafeBufferPointer(start: floats, count: total / 4)) }
        }
        if reader.status == .failed { throw reader.error ?? NetworkFailure.response }
        return meter.finish()
    }
}

struct LoudnessMeasurement {
    private let channels: Int
    private let blockFrames: Int
    private var energy = 0.0, total = 0.0, peak = 0.0
    private var frames = 0, kept = 0
    init(sampleRate: Int, channels: Int) { self.channels = max(1, channels); blockFrames = max(1, sampleRate * 2 / 5) }
    mutating func append(_ samples: UnsafeBufferPointer<Float>) {
        guard samples.count >= channels else { return }
        for index in stride(from: 0, through: samples.count - channels, by: channels) {
            for channel in 0..<channels {
                let value = Double(samples[index + channel]); energy += value * value / Double(channels); peak = max(peak, abs(value))
            }
            frames += 1
            if frames == blockFrames { addBlock() }
        }
    }
    mutating func finish() -> Double {
        addBlock()
        guard kept > 0, peak > 0 else { return 1 }
        return min(min(4, max(0.25, 0.126 / sqrt(total / Double(kept)))), 0.98 / peak)
    }
    private mutating func addBlock() {
        if frames > 0, energy / Double(frames) >= 0.003 * 0.003 { total += energy; kept += frames }
        energy = 0; frames = 0
    }
}
