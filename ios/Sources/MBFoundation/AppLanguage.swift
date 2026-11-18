import Foundation

public enum AppLanguage: String, Codable, CaseIterable, Identifiable, Sendable {
    case system, chinese = "zh-CN", english = "en"
    public var id: String { rawValue }
    public var title: String { switch self { case .system: "跟随系统"; case .chinese: "简体中文"; case .english: "English" } }
    public var languageCode: String {
        switch self {
        case .system: Locale.preferredLanguages.first?.hasPrefix("zh") == true ? "zh-Hans" : "en"
        case .chinese: "zh-Hans"
        case .english: "en"
        }
    }
}
