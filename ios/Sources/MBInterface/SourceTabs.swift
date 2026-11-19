import SwiftUI

struct SourceTab: Identifiable {
    let id: String
    let name: String
}

struct SourceTabs: View {
    let sources: [SourceTab]
    let selected: String
    let width: CGFloat
    let select: (String) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 3) {
                    ForEach(sources) { source in
                        let active = source.id == selected
                        Button { select(source.id) } label: {
                            Text(source.name)
                                .font(.subheadline.weight(active ? .bold : .medium))
                                .foregroundStyle(active ? .primary : .secondary)
                                .lineLimit(1)
                                .padding(.horizontal, 7)
                                .frame(minHeight: 44)
                                .frame(maxWidth: width)
                                .contentShape(Rectangle())
                                .overlay(alignment: .bottom) {
                                    if active { Capsule().fill(MBTheme.accent).frame(height: 3).padding(.horizontal, 7).padding(.bottom, 4) }
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(active ? .isSelected : [])
                        .accessibilityIdentifier("source-tab-\(source.id)")
                        .id(source.id)
                    }
                }
            }
            .scrollIndicators(.hidden)
            .accessibilityIdentifier("source-tabs")
            .onChange(of: [selected] + sources.map(\.id), initial: true) { _, _ in
                proxy.scrollTo(selected)
            }
        }
        .frame(width: width)
    }
}
