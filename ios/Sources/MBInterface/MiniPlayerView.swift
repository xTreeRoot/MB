import MBFoundation
import SwiftUI

struct MiniPlayerView: View {
    let playback: PlaybackModel
    var transitionEnabled = false
    let transitionNamespace: Namespace.ID
    let open: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            if let track = playback.queue.current {
                Button(action: open) {
                    HStack(spacing: 12) {
                        ArtworkView(track: track, size: 44).modifier(CoverTransitionSource(enabled: transitionEnabled, namespace: transitionNamespace))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text(playback.errorMessage ?? track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                    }
                }
                .accessibilityLabel("打开播放页，\(track.title)")
                Button { playback.toggle() } label: {
                    if playback.state == .loading { ProgressView().frame(width: 44, height: 44) }
                    else {
                        Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill").frame(width: 44, height: 44)
                    }
                }
                .accessibilityLabel(Text(playback.state == .playing ? "暂停" : "播放"))
            }
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(.primary.opacity(0.06)))
        .padding(.horizontal, 12).padding(.vertical, 8)
        .buttonStyle(.plain)
    }
}

