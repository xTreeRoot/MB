import SwiftUI

#if os(iOS)
import AVKit

/// 系统提供实际可用的 AirPlay 和音频输出设备，不维护自定义设备清单。
struct AudioRoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = false
        picker.tintColor = .label
        picker.activeTintColor = .systemRed
        return picker
    }
    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
#endif
