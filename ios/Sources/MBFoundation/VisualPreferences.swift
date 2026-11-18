import Foundation

public enum PlayerVisualStyle: String, Codable, CaseIterable, Identifiable, Sendable {
    case cover = "", vinyl, cassette
    public var id: String { rawValue }
    public var title: String { switch self { case .cover: "封面"; case .vinyl: "黑胶"; case .cassette: "磁带" } }
}

public enum LyricVisualStyle: String, Codable, CaseIterable, Identifiable, Sendable {
    case flow = "", steps = "partita", tilt
    public var id: String { rawValue }
    public var title: String { switch self { case .flow: "Flow · 流动"; case .steps: "Steps · 阶梯"; case .tilt: "Tilt · 倾诉" } }
}

public enum AudioQuality: String, Codable, CaseIterable, Identifiable, Sendable {
    case standard, high, lossless
    public var id: String { rawValue }
    public var title: String { switch self { case .standard: "标准"; case .high: "高品质"; case .lossless: "无损" } }
}
