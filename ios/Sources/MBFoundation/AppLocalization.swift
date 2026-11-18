import Foundation
import os

/// 非 UI 错误与平台状态使用和 SwiftUI 相同的语言选择；锁保护后台请求读取。
public enum AppLocalization {
    private static let selection = OSAllocatedUnfairLock(initialState: AppLanguage.system)
    public static func use(_ language: AppLanguage) { selection.withLock { $0 = language } }
    public static var languageCode: String { selection.withLock { $0.languageCode } }
    public static func text(_ key: String, arguments: [any CVarArg] = []) -> String {
        let code = selection.withLock { $0.languageCode }
        let bundle = Bundle.main.path(forResource: code, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
        let format = bundle.localizedString(forKey: key, value: key, table: nil)
        return arguments.isEmpty ? format : String(format: format, locale: Locale(identifier: code), arguments: arguments)
    }
}
