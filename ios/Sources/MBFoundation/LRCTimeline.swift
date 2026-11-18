import Foundation

public extension CaptionTimeline {
    static func lrc(_ text: String, translation: String, durationMilliseconds: Int64 = 0) -> CaptionTimeline {
        let original = lrc(text, durationMilliseconds: durationMilliseconds), translated = lrc(translation, durationMilliseconds: durationMilliseconds)
        return CaptionTimeline(lines: original.lines.map { line in
            let candidate = translated.lines.min { abs($0.startMilliseconds - line.startMilliseconds) < abs($1.startMilliseconds - line.startMilliseconds) }
            let matched = candidate.flatMap { abs($0.startMilliseconds - line.startMilliseconds) <= 1_500 && $0.text != line.text ? $0.text : nil }
            return CaptionLine(startMilliseconds: line.startMilliseconds, endMilliseconds: line.endMilliseconds, text: line.text, translation: matched, words: line.words)
        })
    }

    /// 同时接受多个时间标签、百分秒/毫秒和全局 offset；空白时间行结束上一句。
    static func lrc(_ text: String, durationMilliseconds: Int64 = 0) -> CaptionTimeline {
        guard let timestamp = try? NSRegularExpression(pattern: "\\[(\\d+):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]"),
              let offsetPattern = try? NSRegularExpression(pattern: "\\[offset:([+-]?\\d+)\\]", options: .caseInsensitive) else { return CaptionTimeline(lines: []) }
        let offset: Int64 = offsetPattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text))
            .flatMap { Range($0.range(at: 1), in: text) }.flatMap { Int64(text[$0]) } ?? 0
        var events: [Int64: [String]] = [:]
        for line in text.components(separatedBy: .newlines) {
            let matches = timestamp.matches(in: line, range: NSRange(line.startIndex..., in: line))
            guard let last = matches.last, let lastRange = Range(last.range, in: line) else { continue }
            let value = line[lastRange.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
            for match in matches {
                guard let minuteRange = Range(match.range(at: 1), in: line), let secondRange = Range(match.range(at: 2), in: line),
                      let minutes = Int64(line[minuteRange]), let seconds = Int64(line[secondRange]), minutes < Int64.max / 120_000 else { continue }
                let fraction = Range(match.range(at: 3), in: line).map { String(line[$0]) } ?? ""
                let milliseconds = Int64(fraction.padding(toLength: 3, withPad: "0", startingAt: 0)) ?? 0
                let (start, overflow) = (minutes * 60_000 + seconds * 1_000 + milliseconds).addingReportingOverflow(offset)
                guard !overflow, durationMilliseconds <= 0 || start < durationMilliseconds else { continue }
                let time = max(0, start)
                if !events[time, default: []].contains(value) { events[time, default: []].append(value) }
            }
        }
        let times = events.keys.sorted()
        return CaptionTimeline(lines: times.enumerated().map { index, start in
            let end = index + 1 < times.count ? times[index + 1] : max(start, durationMilliseconds > 0 ? durationMilliseconds : start + 6_000)
            return CaptionLine(startMilliseconds: start, endMilliseconds: end, text: (events[start] ?? []).filter { !$0.isEmpty }.joined(separator: "\n"))
        })
    }
}
