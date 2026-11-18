import XCTest
import UIKit

final class MiniPlayerLayoutTests: XCTestCase {
    @MainActor func testMiniPlayerKeepsTabButtonsClearAndUsable() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let more = app.buttons["查看更多为你推荐"]
        XCTAssertTrue(more.waitForExistence(timeout: 15))
        more.tap()
        let track = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
        XCTAssertTrue(track.waitForExistence(timeout: 5))
        track.tap()
        app.navigationBars["为你推荐"].buttons.element(boundBy: 0).tap()

        let player = app.buttons["打开播放页，晚风里的片刻"]
        for title in ["发现", "搜索", "资料库", "我的"] {
            let tab = app.tabBars.buttons[title]
            XCTAssertTrue(tab.isHittable)
            tab.tap()
            XCTAssertTrue(tab.isSelected)
            XCTAssertTrue(player.waitForExistence(timeout: 5))
            XCTAssertTrue(player.isHittable)
            for button in app.tabBars.buttons.allElementsBoundByIndex {
                XCTAssertFalse(player.frame.intersects(button.frame), "迷你播放器遮住了标签：\(button.label)")
                if UIDevice.current.userInterfaceIdiom == .phone {
                    XCTAssertLessThanOrEqual(player.frame.maxY, button.frame.minY, "迷你播放器应位于标签栏上方")
                }
            }
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "底部播放器-\(title)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        player.tap()
        XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 5))
        app.buttons["收起"].tap()
        XCTAssertTrue(player.waitForExistence(timeout: 5))
        XCTAssertTrue(app.tabBars.buttons["发现"].isHittable)
    }
}
