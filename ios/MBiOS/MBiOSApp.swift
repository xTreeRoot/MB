import MBBilibili
import MBInterface
import SwiftUI

@main
struct MBiOSApp: App {
    @UIApplicationDelegateAdaptor(MBMobileAppDelegate.self) private var appDelegate
    private let demo: Bool
    private let source = BilibiliSource()

    init() {
        #if DEBUG
        demo = ProcessInfo.processInfo.arguments.contains("--demo")
        #else
        demo = false
        #endif
    }

    var body: some Scene {
        WindowGroup {
            if demo { MBRootView(source: DemoSource(), demo: true) }
            else { MBRootView(source: source, sourceID: "bilibili") }
        }
    }
}
