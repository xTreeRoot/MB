import ImageIO
import SwiftUI
import UniformTypeIdentifiers

@MainActor @Observable final class BackgroundSkin {
    private(set) var image: CGImage?
    private(set) var busy = false
    private(set) var failed = false
    private let file: URL

    init(directory: URL? = nil) {
        file = (directory ?? URL.applicationSupportDirectory.appendingPathComponent("MBMobile/background-skin"))
            .appendingPathComponent("background.jpg")
    }

    func restore() async {
        let file = file
        await update {
            FileManager.default.fileExists(atPath: file.path) ? try Self.thumbnail(from: file) : nil
        }
    }

    func choose(_ result: Result<URL, any Error>) async {
        let file = file
        await update {
            let source = try result.get()
            let accessing = source.startAccessingSecurityScopedResource()
            defer { if accessing { source.stopAccessingSecurityScopedResource() } }
            let image = try Self.thumbnail(from: source)
            let data = NSMutableData()
            guard let output = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { throw CocoaError(.fileWriteUnknown) }
            CGImageDestinationAddImage(output, image, [kCGImageDestinationLossyCompressionQuality: 0.88] as CFDictionary)
            guard CGImageDestinationFinalize(output) else { throw CocoaError(.fileWriteUnknown) }
            var directory = file.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            try (data as Data).write(to: file, options: .atomic)
            return image
        }
    }

    func clear() async {
        let file = file
        await update {
            do { try FileManager.default.removeItem(at: file) } catch CocoaError.fileNoSuchFile {}
            return nil
        }
    }

    private func update(_ operation: @escaping @Sendable () throws -> CGImage?) async {
        guard !busy else { return }
        busy = true; failed = false
        defer { busy = false }
        do { image = try await Task.detached(priority: .utility, operation: operation).value }
        catch CocoaError.userCancelled {} catch { failed = true }
    }

    private nonisolated static func thumbnail(from file: URL) throws -> CGImage {
        let values = try file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, let size = values.fileSize, size <= 32 * 1024 * 1024,
              let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: 2560,
                  kCGImageSourceShouldCacheImmediately: true,
              ] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        return image
    }
}

extension EnvironmentValues {
    @Entry var backgroundSkinImage: CGImage? = nil
}

struct SkinSurface: ViewModifier {
    var defaultColor: Color? = nil
    var list = false
    var base = MBTheme.background
    var enabled = true
    @Environment(\.backgroundSkinImage) private var image
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .scrollContentBackground(list && enabled && image != nil ? .hidden : .automatic)
            .background {
                if enabled, let image {
                    GeometryReader { geometry in
                        Image(decorative: image, scale: 1)
                            .resizable().scaledToFill()
                            .frame(width: geometry.size.width, height: geometry.size.height).clipped()
                            .opacity(colorScheme == .dark ? 0.35 : 0.22)
                    }
                    .background(base)
                    .allowsHitTesting(false).accessibilityHidden(true)
                } else if let defaultColor { defaultColor }
            }
    }
}

struct BackgroundSkinSettings: View {
    let skin: BackgroundSkin
    @State private var importing = false

    var body: some View {
        Form {
            Section {
                Button("选择背景图片", systemImage: "photo") { importing = true }
                    .accessibilityIdentifier("choose-background-skin")
                if skin.image != nil || skin.failed {
                    Button("恢复默认背景", role: .destructive) { Task { await skin.clear() } }
                        .accessibilityIdentifier("clear-background-skin")
                }
                if skin.busy { ProgressView() }
                if skin.failed { Text("无法更新背景图片").foregroundStyle(.red) }
            }.disabled(skin.busy)
        }
        .navigationTitle("背景皮肤")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image]) { result in
            Task { await skin.choose(result) }
        }
    }
}
