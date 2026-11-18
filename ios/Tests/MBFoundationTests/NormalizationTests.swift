import Foundation
import AVFoundation
import MBSignal
@testable import MBFoundation
import Testing

private func rms(_ samples: ArraySlice<Float>) -> Double {
    sqrt(samples.reduce(0) { $0 + Double($1) * Double($1) } / Double(max(1, samples.count)))
}

@Test func dynamicNormalizationLinksStereoLiftsQuietAndPreventsClipping() throws {
    let signal = try #require(MBSignalCreate())
    defer { MBSignalRelease(signal) }
    MBSignalReset(signal, 1_000); MBSignalConfigure(signal, 2, 1)
    var samples: [Float] = []
    for frame in 0..<16_000 {
        let amplitude = frame < 8_000 ? 0.02 : 0.7
        let value = Float(sin(Double(frame) * .pi * 2 * 0.1) * amplitude)
        samples.append(value); samples.append(value * 0.5)
    }
    let original = samples
    samples.withUnsafeMutableBufferPointer { MBSignalProcess(signal, $0.baseAddress, 16_000, 2) }
    #expect(rms(samples[12_000..<16_000]) > rms(original[12_000..<16_000]) * 2)
    #expect(rms(samples[28_000..<32_000]) < rms(original[28_000..<32_000]) * 0.7)
    #expect(samples.map { abs($0) }.max() ?? 0 <= 0.981)
    for index in stride(from: 0, to: samples.count, by: 2) { #expect(abs(samples[index + 1] - samples[index] * 0.5) < 0.00001) }
}

@Test func normalizationBypassFixedGainAndChunkBoundariesMatch() throws {
    let signal = try #require(MBSignalCreate())
    defer { MBSignalRelease(signal) }
    let original = (0..<4_000).map { Float(sin(Double($0) * 0.3) * 0.1) }
    var samples = original
    MBSignalConfigure(signal, 0, 4)
    samples.withUnsafeMutableBufferPointer { MBSignalProcess(signal, $0.baseAddress, $0.count, 1) }
    #expect(samples == original)
    MBSignalConfigure(signal, 1, 2)
    samples.withUnsafeMutableBufferPointer { MBSignalProcess(signal, $0.baseAddress, $0.count, 1) }
    #expect(samples == original.map { $0 * 2 })
    MBSignalReset(signal, 1_000); MBSignalConfigure(signal, 2, 1)
    var whole = original
    whole.withUnsafeMutableBufferPointer { MBSignalProcess(signal, $0.baseAddress, $0.count, 1) }
    MBSignalReset(signal, 1_000)
    var chunked = original
    chunked.withUnsafeMutableBufferPointer { pointer in
        for index in stride(from: 0, to: pointer.count, by: 137) { MBSignalProcess(signal, pointer.baseAddress! + index, min(137, pointer.count - index), 1) }
    }
    #expect(whole == chunked)
}

@Test func standardMeasurementIgnoresSilenceAndPreservesWholeSongDynamics() {
    var meter = LoudnessMeasurement(sampleRate: 1_000, channels: 2)
    var tone: [Float] = []
    for frame in 0..<8_000 {
        let value: Float = frame < 2_000 ? 0 : Float(sin(Double(frame) * 0.2) * 0.03)
        tone.append(value); tone.append(value)
    }
    tone.withUnsafeBufferPointer { meter.append($0) }
    let gain = meter.finish()
    #expect(gain > 3.5 && gain <= 4)
    var silence = LoudnessMeasurement(sampleRate: 1_000, channels: 1)
    Array(repeating: Float.zero, count: 2_000).withUnsafeBufferPointer { silence.append($0) }
    #expect(silence.finish() == 1)
}

@Test func audioTapProcessesSystemDecodedPCMWithoutPlayingThroughSpeakers() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let file = directory.appendingPathComponent("tone.wav")
    var bytes = Data()
    func append<T: FixedWidthInteger>(_ value: T) { var little = value.littleEndian; withUnsafeBytes(of: &little) { bytes.append(contentsOf: $0) } }
    let frames = 44_100
    bytes.append(Data("RIFF".utf8)); append(UInt32(frames * 2 + 36)); bytes.append(Data("WAVEfmt ".utf8))
    append(UInt32(16)); append(UInt16(1)); append(UInt16(1)); append(UInt32(44_100)); append(UInt32(88_200)); append(UInt16(2)); append(UInt16(16))
    bytes.append(Data("data".utf8)); append(UInt32(frames * 2))
    for frame in 0..<frames { append(Int16(sin(Double(frame) * .pi * 2 * 440 / 44_100) * 3_000)) }
    try bytes.write(to: file)
    let asset = AVURLAsset(url: file)
    let track = try #require(try await asset.loadTracks(withMediaType: .audio).first)
    let normalizer = try AudioNormalizer()
    normalizer.configure(.standard, gain: 2)
    let mix = try normalizer.mix(track: track)
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderAudioMixOutput(audioTracks: [track], audioSettings: [AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMIsFloatKey: true, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsNonInterleaved: false])
    output.audioMix = mix
    reader.add(output)
    #expect(reader.startReading())
    var measured: [Float] = []
    while let buffer = output.copyNextSampleBuffer() {
        let block = try #require(CMSampleBufferGetDataBuffer(buffer))
        var count = 0, total = 0
        var pointer: UnsafeMutablePointer<Int8>?
        #expect(CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: &count, totalLengthOut: &total, dataPointerOut: &pointer) == kCMBlockBufferNoErr)
        if let pointer { pointer.withMemoryRebound(to: Float.self, capacity: count / 4) { measured.append(contentsOf: UnsafeBufferPointer(start: $0, count: count / 4)) } }
    }
    #expect(reader.status == .completed)
    #expect(measured.count == frames)
    let expected = Double(3_000) / 32_768 * sqrt(0.5) * 2
    #expect(abs(rms(measured[...]) - expected) < 0.002)
    #expect(try await LoudnessAnalyzer().gain(asset: asset) > 1.8)
}

@Test func bassMeterRespondsToLowFrequenciesWithoutChangingBypassedAudio() throws {
    let signal = try #require(MBSignalCreate()); defer { MBSignalRelease(signal) }
    func measure(_ hz: Double) -> Double {
        MBSignalReset(signal, 48_000); MBSignalConfigure(signal, 0, 1)
        var samples = (0..<48_000).map { Float(sin(Double($0) * .pi * 2 * hz / 48_000) * 0.1) }
        let original = samples
        samples.withUnsafeMutableBufferPointer { MBSignalProcess(signal, $0.baseAddress, $0.count, 1) }
        #expect(samples == original)
        return MBSignalBass(signal)
    }
    #expect(measure(80) > measure(4_000) * 10)
}
