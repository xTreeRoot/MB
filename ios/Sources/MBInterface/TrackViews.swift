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
    @State private var data: Data?
    var body: some View {
        ZStack {
            MBTheme.artworkGradient
        }
        .overlay {
            GeometryReader { geometry in
                if let image { image.resizable().scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                else { Image(systemName: "music.note").foregroundStyle(.white.opacity(0.75)).frame(maxWidth: .infinity, maxHeight: .infinity) }
            }
        }
        .task(id: track.artworkURL) {
            data = nil
            guard let url = track.artworkURL else { return }
            if url.isFileURL {
                let result = await Task.detached(priority: .utility) { try? Data(contentsOf: url) }.value
                guard !Task.isCancelled else { return }
                data = result; return
            }
            var request = URLRequest(url: url)
            request.allHTTPHeaderFields = track.artworkHeaders
            do {
                let (value, response) = try await NetworkClient.shared.data(for: request)
                guard !Task.isCancelled, let response = response as? HTTPURLResponse,
                      (200..<300).contains(response.statusCode) else { return }
                data = value
            } catch { /* 无封面时保留渐变占位。 */ }
        }
    }

    private var image: Image? {
        guard let data else { return nil }
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
    }
}

