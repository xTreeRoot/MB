import Foundation

/// 对照 main 的静默段规则：至少 8 秒，头尾预留，长间奏每约 10 秒换一条。
public enum QuietComments {
    public static func comment(at position: Int64, timeline: CaptionTimeline, duration: Int64, comments: [TrackComment]) -> TrackComment? {
        let candidates = comments.filter { !$0.text.isEmpty && $0.text.unicodeScalars.count <= 140 }.sorted { $0.likes > $1.likes }
        guard !candidates.isEmpty else { return nil }
        var intervals: [(Int64, Int64)] = [], end: Int64 = 0
        let sung = timeline.lines.filter { !isCredit($0.text) }
        for line in sung {
            if line.startMilliseconds - end >= 8_000 { intervals.append((end, line.startMilliseconds)) }
            end = max(end, line.endMilliseconds)
        }
        if duration - end >= 8_000 { intervals.append((end, duration)) }
        var shown = 0
        for (start, end) in intervals {
            let from = start + 1_200, to = end - 1_000
            let slots = max(1, Int((to - from + 5_000) / 10_000)), each = max(1, (to - from) / Int64(slots))
            if position >= from && position < to {
                let slot = min(slots - 1, Int((position - from) / each))
                return candidates[(shown + slot) % candidates.count]
            }
            shown += slots
        }
        return nil
    }
    private static func isCredit(_ text: String) -> Bool {
        guard let separator = text.firstIndex(where: { $0 == ":" || $0 == "：" }) else { return false }
        let head = text[..<separator].trimmingCharacters(in: .whitespaces)
        guard head.count <= 12 else { return false }
        return ["作词", "作曲", "编曲", "制作", "监制", "混音", "录音", "母带", "和声", "吉他", "贝斯", "鼓", "键盘", "弦乐", "出品", "发行", "OP", "SP", "词", "曲", "Lyrics", "Music", "Composer", "Arranger", "Producer"].contains { head == $0 || $0.count > 1 && head.hasPrefix($0) }
    }
}
