import MBFoundation
import SwiftUI

private struct LyricVisualStyleKey: EnvironmentKey { static let defaultValue = LyricVisualStyle.flow }
private struct LyricSparksKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var lyricVisualStyle: LyricVisualStyle {
        get { self[LyricVisualStyleKey.self] }
        set { self[LyricVisualStyleKey.self] = newValue }
    }
    var lyricSparks: Bool {
        get { self[LyricSparksKey.self] }
        set { self[LyricSparksKey.self] = newValue }
    }
}

struct CaptionView: View {
    let timeline: CaptionTimeline
    let positionMilliseconds: Int64
    let message: String?
    var seek: ((Int64) -> Void)? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.lyricVisualStyle) private var style
    @Environment(\.lyricSparks) private var sparks

    var body: some View {
        if timeline.lines.isEmpty {
            ContentUnavailableView("暂无字幕", systemImage: "quote.bubble", description: Text(LocalizedStringKey(message ?? "此视频没有提供可用字幕")))
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        ForEach(Array(timeline.lines.enumerated()), id: \.offset) { index, line in
                            Button { seek?(line.startMilliseconds) } label: {
                                VStack(alignment: .leading, spacing: 10) {
                                    LyricWordLayout(style: style) {
                                        ForEach(Array(line.timedWords.enumerated()), id: \.offset) { wordIndex, word in
                                            wordView(word, index: wordIndex, active: index == activeIndex)
                                        }
                                    }
                                    if let translation = line.translation, !translation.isEmpty {
                                        Text(translation).font(.subheadline).foregroundStyle(.secondary)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .scaleEffect(index == activeIndex ? 1 : 0.94, anchor: .leading)
                                .opacity(index == activeIndex ? 1 : 0.6)
                                .animation(reduceMotion ? nil : .spring(duration: 0.45), value: activeIndex)
                            }.buttonStyle(.plain).disabled(seek == nil)
                            .id(index)
                            .accessibilityLabel("跳转到 \(line.text)")
                            .accessibilityAddTraits(index == activeIndex ? .isSelected : [])
                        }
                    }.padding(.vertical, 32).padding(.horizontal, 8).frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 260)
                .clipped()
                .accessibilityIdentifier("lyric-style-" + style.rawValue)
                .onAppear { if let activeIndex { proxy.scrollTo(activeIndex, anchor: .center) } }
                .onChange(of: activeIndex) { _, index in
                    guard let index else { return }
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { proxy.scrollTo(index, anchor: .center) }
                }
            }
        }
    }
    private var activeIndex: Int? { timeline.activeIndex(at: positionMilliseconds) }
    private func wordView(_ word: CaptionWord, index: Int, active: Bool) -> some View {
        let progress = word.progress(at: positionMilliseconds)
        return Text(word.text)
            .font(.system(size: style == .tilt ? 30 : 25, weight: style == .tilt && index % 2 == 0 ? .black : .semibold, design: style == .tilt ? .rounded : .default))
            .foregroundStyle(.secondary)
            .overlay(alignment: .leading) {
                GeometryReader { geometry in
                    MBTheme.accent.frame(width: geometry.size.width * (active ? progress : 0))
                }.mask(Text(word.text).font(.system(size: style == .tilt ? 30 : 25, weight: style == .tilt && index % 2 == 0 ? .black : .semibold, design: style == .tilt ? .rounded : .default)))
            }
            .overlay {
                if sparks && !reduceMotion && active {
                    Canvas { context, size in
                        let age = Double(positionMilliseconds - word.startMilliseconds) / 600
                        guard age >= 0 && age < 1 else { return }
                        for particle in 0..<6 {
                            let angle = Double(particle) * .pi / 3
                            let x = size.width * progress + cos(angle) * age * 24
                            let y = size.height * 0.5 + sin(angle) * age * 22 - age * 15
                            context.opacity = 1 - age
                            context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 2.5, height: 2.5)), with: .color(MBTheme.accent))
                        }
                    }.allowsHitTesting(false)
                }
            }
            .rotationEffect(.degrees(style == .tilt && !reduceMotion && index % 3 == 0 ? -4 : 0))
            .fixedSize()
    }
}

/// 按词自然换行；阶梯与倾诉在行内保留逐字时间和字重。
private struct LyricWordLayout: Layout {
    let style: LyricVisualStyle
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? 300, subviews: subviews).size
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(width: bounds.width, subviews: subviews)
        for (view, point) in zip(subviews, result.points) { view.place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: .unspecified) }
    }
    private func arrange(width: CGFloat, subviews: Subviews) -> (size: CGSize, points: [CGPoint]) {
        var x: CGFloat = 0, y: CGFloat = 0, height: CGFloat = 0, row = 0
        var points: [CGPoint] = []
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                row += 1; y += height + (style == .flow ? 5 : 10); height = 0
                x = style == .steps ? CGFloat(row % 3) * 12 : 0
            }
            points.append(CGPoint(x: x, y: y)); x += size.width + (style == .flow ? 1 : 3); height = max(height, size.height)
        }
        return (CGSize(width: width, height: y + height), points)
    }
}
