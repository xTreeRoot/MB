import Foundation
import MBFoundation
import Testing

@Test @MainActor func manualQueueNavigationWrapsAndSelectionIsStable() {
    let queue = PlaybackQueue()
    queue.next()
    queue.previous()
    #expect(queue.current == nil)
    let tracks = ["a", "b"].map { Track(sourceID: "test", sourceTrackID: $0, title: $0, artist: "") }
    queue.replace(with: tracks, selecting: 1)
    queue.next()
    #expect(queue.current == tracks[0])
    queue.previous()
    #expect(queue.current == tracks[1])
    queue.select(index: 0)
    queue.replace(with: [], selecting: 0)
    queue.select(index: 9)
    #expect(queue.current == tracks[0])
}

@Test func sourceNamesArePartOfIdentity() {
    let a = Track(sourceID: "one", sourceTrackID: "7", title: "", artist: "")
    let b = Track(sourceID: "two", sourceTrackID: "7", title: "", artist: "")
    #expect(a.id != b.id)
}

@Test func captionsRespectEndsGapsAndSeekingBackwards() {
    let timeline = CaptionTimeline(lines: [
        CaptionLine(startMilliseconds: 3_000, endMilliseconds: 4_000, text: "第二句"),
        CaptionLine(startMilliseconds: 1_000, endMilliseconds: 2_000, text: "第一句"),
        CaptionLine(startMilliseconds: 8_000, endMilliseconds: 7_000, text: "无效"),
    ])
    #expect(timeline.lines.count == 2)
    #expect(timeline.activeIndex(at: 999) == nil)
    #expect(timeline.activeIndex(at: 1_000) == 0)
    #expect(timeline.activeIndex(at: 1_999) == 0)
    #expect(timeline.activeIndex(at: 2_000) == nil)
    #expect(timeline.activeIndex(at: 3_500) == 1)
    #expect(timeline.activeIndex(at: 4_000) == nil)
    #expect(timeline.activeIndex(at: 1_100) == 0)
}

@Test func timeLabelsUseMilliseconds() {
    #expect(TimeLabel.string(milliseconds: -1) == "0:00")
    #expect(TimeLabel.string(milliseconds: 61_999) == "1:01")
    #expect(TimeLabel.string(milliseconds: 3_600_000) == "60:00")
}


@Test func timedPluginLyricsPreserveWordsTranslationAndInferMissingEnd() throws {
    let values = try JSONDecoder().decode([PluginJSON].self, from: Data(#"[{"start":1000,"text":"","translation":"Hello","words":[{"start":1000,"end":1300,"text":"你"},{"start":1300,"end":1700,"text":"好"}]},{"start":3000,"end":4000,"text":"Next"}]"#.utf8))
    let timeline = CaptionTimeline.pluginLines(values)
    #expect(timeline.lines[0].text == "你好")
    #expect(timeline.lines[0].endMilliseconds == 3000)
    #expect(timeline.lines[0].translation == "Hello")
    #expect(timeline.lines[0].timedWords[1].progress(at: 1500) == 0.5)
    #expect(timeline.lines[0].timedWords[1].progress(at: 0) == 0)
    #expect(timeline.lines[0].timedWords[1].progress(at: 5000) == 1)
    let spread = CaptionLine.spread("你好 hello world", start: 1000, end: 5000)
    #expect(spread.map(\.text) == ["你", "好", "hello ", "world"])
    #expect(spread.last?.endMilliseconds == 4400)
}

@Test func quietCommentsExcludeCreditsReserveSingingAndRotateByLikes() {
    let timeline = CaptionTimeline(lines: [
        CaptionLine(startMilliseconds: 0, endMilliseconds: 5_000, text: "作词：作者"),
        CaptionLine(startMilliseconds: 25_000, endMilliseconds: 30_000, text: "歌词"),
    ])
    let comments = [TrackComment(id: "a", author: "A", text: "first", date: .distantPast, likes: 1), TrackComment(id: "b", author: "B", text: "second", date: .distantPast, likes: 10)]
    #expect(QuietComments.comment(at: 1_000, timeline: timeline, duration: 40_000, comments: comments) == nil)
    #expect(QuietComments.comment(at: 2_000, timeline: timeline, duration: 40_000, comments: comments)?.id == "b")
    #expect(QuietComments.comment(at: 15_000, timeline: timeline, duration: 40_000, comments: comments)?.id == "a")
    #expect(QuietComments.comment(at: 24_500, timeline: timeline, duration: 40_000, comments: comments) == nil)
    #expect(QuietComments.comment(at: 27_000, timeline: timeline, duration: 40_000, comments: comments) == nil)
    #expect(QuietComments.comment(at: 32_000, timeline: timeline, duration: 40_000, comments: comments)?.id == "b")
}

@Test func lyricTranslationMatchesNearbyTimestampWithoutJoiningIntoSungWords() {
    let timeline = CaptionTimeline.lrc("[00:01.00]Hello\n[00:05.00]World", translation: "[00:01.50]你好\n[00:08.00]不会匹配")
    #expect(timeline.lines.first?.text == "Hello")
    #expect(timeline.lines.first?.translation == "你好")
    #expect(timeline.lines.last?.translation == nil)
    #expect(timeline.lines.first?.timedWords.map(\.text).joined() == "Hello")
}
