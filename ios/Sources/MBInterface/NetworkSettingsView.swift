import MBFoundation
import SwiftUI

struct NetworkSettingsView: View {
    @Bindable var model: NetworkSettingsModel
    @State private var saving = false
    @FocusState private var editing: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $model.draft).font(.body.monospaced()).frame(minHeight: 210)
                        .focused($editing)
                        .autocorrectionDisabled()
                        .accessibilityLabel("系统代理域名白名单")
                    Button("保存") { editing = false; saving = true; Task { await model.save(); saving = false } }.disabled(saving)
                    if saving { ProgressView() }
                    if let message = model.errorMessage { Text(LocalizedStringKey(message)).foregroundStyle(.red) }
                    if let message = model.savedMessage { Text(LocalizedStringKey(message)).foregroundStyle(.secondary) }
                } header: { Text("系统代理域名白名单") } footer: {
                    Text("每行一个域名，支持 *.example.com。只有白名单域名使用系统代理，其余请求直连；空列表全部直连。此设置不改变系统 VPN 的路由。")
                }
            }
            .navigationTitle("网络设置")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}
