import MBFoundation
import SwiftUI

struct ArtworkView: View {
    let track: Track
    var size: CGFloat = 52

    var body: some View {
        RemoteArtwork(track: track)
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size > 100 ? 24 : 10))
        .accessibilityHidden(true)
    }
}

/// 封面和 API/音频共用代理策略，图片上下文由音源随曲目提供。
struct RemoteArtwork: View {
    let track: Track
    @State private var image: Image?
    private struct Request: Hashable {
        let url: URL?
        let headers: [String: String]
    }
    var body: some View {
        ZStack {
            MBTheme.artworkGradient
        }
        .overlay {
            GeometryReader { geometry in
                if let image {
                    image.resizable().scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped()
                        .accessibilityIdentifier("artwork-loaded-" + track.id)
                }
                else if track.sourceID == "demo" || track.localMetadata?.folderID == "preview" { PreviewCover(track: track) }
                else { Image(systemName: "music.note").foregroundStyle(.white.opacity(0.75)).frame(maxWidth: .infinity, maxHeight: .infinity) }
            }
        }
        .task(id: Request(url: track.artworkURL, headers: track.artworkHeaders)) {
            image = nil
            guard let url = track.artworkURL else { return }
            if url.isFileURL {
                let result = await Task.detached(priority: .utility) { try? Data(contentsOf: url) }.value
                guard !Task.isCancelled else { return }
                image = result.flatMap(decodedImage); return
            }
            var request = URLRequest(url: url)
            request.allHTTPHeaderFields = track.artworkHeaders
            do {
                let (value, response) = try await NetworkClient.shared.data(for: request)
                guard !Task.isCancelled, let response = response as? HTTPURLResponse,
                      (200..<300).contains(response.statusCode) else { return }
                image = decodedImage(value)
            } catch { /* 无封面时保留渐变占位。 */ }
        }
    }

    // 每次资源加载只解码一次，播放时钟更新不反复解码同一张封面。
    private func decodedImage(_ data: Data) -> Image? {
        #if os(iOS)
        guard let image = UIImage(data: data) else { return nil }
        return Image(uiImage: image)
        #else
        guard let image = NSImage(data: data) else { return nil }
        return Image(nsImage: image)
        #endif
    }
}

struct TrackRow: View {
    let track: Track
    var selected = false

    var body: some View {
        HStack(spacing: 14) {
            ArtworkView(track: track)
            VStack(alignment: .leading, spacing: 5) {
                Text(track.title).font(.body.weight(.medium)).lineLimit(2)
                    .foregroundStyle(selected ? MBTheme.accent : .primary)
                Text(track.artist.isEmpty ? "未知作者" : track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(TimeLabel.string(milliseconds: track.durationMilliseconds))
                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        .opacity(track.unavailable == true ? 0.45 : 1)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}


/// 原创演示封面，明确标记 DEMO；不替代真实音源的图片加载。
private struct PreviewCover: View {
    let track: Track
    private var warm: Bool { track.sourceTrackID == "night" }
    private var rain: Bool { track.sourceTrackID == "rain" }
    var body: some View {
        GeometryReader { area in
            let side = area.size.width
            ZStack(alignment: .topLeading) {
                Color(red: warm ? 0.78 : 0.17, green: warm ? 0.39 : rain ? 0.29 : 0.44, blue: warm ? 0.26 : 0.49)
                Circle().fill(Color(red: 0.96, green: 0.81, blue: 0.51)).frame(width: side * 0.42, height: side * 0.42).offset(x: side * 0.48, y: side * 0.12)
                Canvas { context, size in
                    for layer in 0..<5 {
                        var path = Path()
                        let y = size.height * (0.5 + Double(layer) * 0.1)
                        path.move(to: CGPoint(x: 0, y: y))
                        path.addCurve(to: CGPoint(x: size.width, y: y + 24), control1: CGPoint(x: size.width * 0.3, y: y - size.height * 0.2), control2: CGPoint(x: size.width * 0.6, y: y + size.height * 0.2))
                        path.addLine(to: CGPoint(x: size.width, y: size.height)); path.addLine(to: CGPoint(x: 0, y: size.height)); path.closeSubpath()
                        context.fill(path, with: .color(.black.opacity(0.1 + Double(layer) * 0.035)))
                    }
                }
                VStack(alignment: .leading) {
                    Text("MB / DEMO").font(.system(size: max(5, side * 0.045), weight: .medium, design: .monospaced)).tracking(side * 0.005)
                    Spacer()
                    Text(track.title).font(.system(size: max(7, side * 0.09), weight: .bold)).lineLimit(2)
                }.foregroundStyle(.white).padding(side * 0.09)
            }.clipped()
        }.accessibilityHidden(true)
    }
}
