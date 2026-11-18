import SwiftUI

/// 延续桌面的朱红强调色与安静的背景；文字、材料随系统明暗模式变化。
enum MBTheme {
    static let accent = Color(red: 0.88, green: 0.25, blue: 0.30)
    static let artworkGradient = LinearGradient(
        colors: [Color(red: 0.43, green: 0.22, blue: 0.29), Color(red: 0.12, green: 0.13, blue: 0.19)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
}

