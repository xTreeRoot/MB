#if os(iOS)
import UIKit

/// 手机仅在全屏视频时转入横屏；平板始终随设备方向和窗口尺寸布局。
@MainActor public final class MBMobileAppDelegate: NSObject, UIApplicationDelegate {
    public func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : MobileOrientation.mask
    }
}

@MainActor enum MobileOrientation {
    static var mask: UIInterfaceOrientationMask = .portrait
    static func fullscreen(_ active: Bool) {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        mask = active ? .landscape : .portrait
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) else { return }
        for window in scene.windows {
            window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            window.rootViewController?.presentedViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
    }
}
#endif
