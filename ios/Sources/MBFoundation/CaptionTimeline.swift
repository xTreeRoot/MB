import Foundation

public struct CaptionWord: Codable, Equatable, Sendable {
    public let startMilliseconds: Int64
    public let endMilliseconds: Int64
    public let text: String
    public init(startMilliseconds: Int64, endMilliseconds: Int64, text: String) {
        self.startMilliseconds = startMilliseconds; self.endMilliseconds = endMilliseconds; self.text = text
    }
    public func progress(at position: Int64) -> Double {
        guard endMilliseconds > startMilliseconds else { return position >= startMilliseconds ? 1 : 0 }
        return min(1, max(0, Double(position - startMilliseconds) / Double(endMilliseconds - startMilliseconds)))
    }
}

public struct CaptionLine: Codable, Equatable, Sendable {
    public let startMilliseconds: Int64
    public let endMilliseconds: Int64
    public let text: String
    public let translation: String?
    public let words: [CaptionWord]?
    public init(startMilliseconds: Int64, endMilliseconds: Int64, text: String, translation: String? = nil, words: [CaptionWord]? = nil) {
        self.startMilliseconds = startMilliseconds; self.endMilliseconds = endMilliseconds
        self.text = text; self.translation = translation; self.words = words
    }
    public var timedWords: [CaptionWord] {
        if let words, !words.isEmpty { return words }
        return Self.spread(text, start: startMilliseconds, end: endMilliseconds)
    }
    /// 没有逐字时间时沿用 main 的按字长分配 85% 行时长；CJK 单字、拉丁单词保留空格。
    public static func spread(_ text: String, start: Int64, end: Int64) -> [CaptionWord] {
        var parts: [String] = [], current = ""
        func flush() { if !current.isEmpty { parts.append(current); current = "" } }
        for scalar in text.unicodeScalars {
            let cjk = (0x3400...0x9FFF).contains(scalar.value) || (0x3040...0x30FF).contains(scalar.value) || (0xAC00...0xD7AF).contains(scalar.value) || (0x20000...0x323AF).contains(scalar.value)
            if CharacterSet.whitespacesAndNewlines.contains(scalar) {
                if !current.isEmpty { current += " "; flush() }
            } else if cjk { flush(); parts.append(String(scalar)) }
            else { current.unicodeScalars.append(scalar) }
        }
        flush()
        let weights = parts.map { max(1, $0.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.count) }
        let total = max(1, weights.reduce(0, +)), span = Double(max(0, end - start)) * 0.85
        var elapsed = 0
        return zip(parts, weights).map { part, weight in
            let from = start + Int64(span * Double(elapsed) / Double(total)); elapsed += weight
            return CaptionWord(startMilliseconds: from, endMilliseconds: start + Int64(span * Double(elapsed) / Double(total)), text: part)
        }
    }
}

/// 字幕区间为 [开始, 结束)；间隙和结束后清除高亮，不把上一句无限延长。
public struct CaptionTimeline: Sendable {
    public let lines: [CaptionLine]
    public init(lines: [CaptionLine]) {
        self.lines = lines.filter {
            $0.startMilliseconds >= 0 && $0.endMilliseconds >= $0.startMilliseconds && !$0.text.isEmpty
        }.sorted { $0.startMilliseconds < $1.startMilliseconds }
    }
    public func activeIndex(at milliseconds: Int64) -> Int? {
        lines.indices.last { lines[$0].startMilliseconds <= milliseconds && milliseconds < lines[$0].endMilliseconds }
    }
    public static func pluginLines(_ values: [PluginJSON]) -> CaptionTimeline {
        let ordered = values.filter { ($0["start"].integer ?? -1) >= 0 }.sorted { ($0["start"].integer ?? 0) < ($1["start"].integer ?? 0) }
        return CaptionTimeline(lines: ordered.enumerated().compactMap { index, line in
            let start = Int64(line["start"].integer ?? 0)
            let suppliedEnd = Int64(line["end"].integer ?? 0)
            let fallback = start > Int64.max - 6_000 ? Int64.max : start + 6_000
            let end = suppliedEnd > start ? suppliedEnd : min(fallback, index + 1 < ordered.count ? Int64(ordered[index + 1]["start"].integer ?? Int(fallback)) : fallback)
            let words = line["words"].array.compactMap { word -> CaptionWord? in
                guard let from = word["start"].integer, let to = word["end"].integer, to >= from, from >= 0, let text = word["text"].string else { return nil }
                return CaptionWord(startMilliseconds: Int64(from), endMilliseconds: Int64(to), text: text)
            }
            let text = line["text"].string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let title = text.isEmpty ? words.map(\.text).joined().trimmingCharacters(in: .whitespacesAndNewlines) : text
            return CaptionLine(startMilliseconds: start, endMilliseconds: end, text: title, translation: line["translation"].string, words: words.isEmpty ? nil : words)
        })
    }
}
