import XCTest
import UIKit

final class MBiOSUITests: XCTestCase {
    /// 手动联网回归，需先安装原版网易云插件；不扫码确认，不改变账号。
    @MainActor func testNeteaseQRCodeRetryAndPlaybackSurviveRelaunch() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let source = app.buttons.matching(NSPredicate(format: "label == %@", "网易云音乐")).firstMatch
        guard source.waitForExistence(timeout: 5) else {
            throw XCTSkip("需要预先安装 sogud/mygo-music-netease v0.1.14")
        }
        source.tap()
        app.tabBars.buttons["我的"].tap()
        let login = app.buttons["登录网易云音乐"]
        XCTAssertTrue(login.waitForExistence(timeout: 15))
        for attempt in 1...2 {
            login.tap()
            XCTAssertTrue(app.images["登录二维码"].waitForExistence(timeout: 20))
            XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Invalid sign-in prompt")).firstMatch.exists)
            capture("网易云-第\(attempt)次生成二维码-未扫码", app: app)
            app.buttons["取消"].tap()
            XCTAssertTrue(login.waitForExistence(timeout: 5))
        }
        app.tabBars.buttons["搜索"].tap()
        let search = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("夜曲\n")
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "选择 夜曲", "Cover")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 20))
        song.tap()
        let player = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "打开播放页，")).firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 15))
        let title = player.label
        let pause = app.buttons["暂停"]
        XCTAssertTrue(pause.waitForExistence(timeout: 30), "真实音频应进入播放状态")
        pause.tap()
        capture("网易云-真实曲目播放后暂停", app: app)
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons[title].waitForExistence(timeout: 20))
        app.buttons[title].tap()
        XCTAssertTrue(app.buttons["收起"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.state, .runningForeground)
        capture("网易云-重启恢复并展开播放器", app: app)
    }

    @MainActor func testSearchKeyboardRequiresTapAndCanBeDismissed() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("搜索", app: app)
        let field = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        XCTAssertFalse(app.keyboards.firstMatch.exists, "进入搜索不应自动弹出键盘")
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        let done = app.buttons["dismiss-search-keyboard"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        done.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        app.staticTexts["浏览音乐"].tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        let landing = app.scrollViews["search-landing"]
        XCTAssertTrue(landing.isHittable)
        capture("搜索-滚动收起键盘前-演示数据", app: app)
        // 滚动容器延伸到键盘后方，手势落在当前可见内容内。
        let dragStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let dragEnd = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.30))
        dragStart.press(forDuration: 0.05, thenDragTo: dragEnd)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        selectTab("发现", app: app)
        selectTab("搜索", app: app)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        field.tap()
        field.typeText("music")
        app.buttons["submit-search"].tap()
        XCTAssertTrue(app.buttons["选择 晚风里的片刻"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        capture("搜索-手动输入与收起键盘-演示数据", app: app)
    }

    @MainActor func testPlayerControlsAndMiniQueueStayUsableAcrossTrackChanges() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
            app.buttons["为你推荐"].firstMatch.tap()
        } else {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
            app.buttons["查看更多为你推荐"].tap()
        }
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        XCTAssertTrue(app.buttons["播放队列"].waitForExistence(timeout: 5))
        app.buttons["播放队列"].tap()
        XCTAssertTrue(app.navigationBars["播放队列 · 3"].waitForExistence(timeout: 5))
        capture("迷你播放器-直接打开队列-演示数据", app: app)
        app.buttons["队列选择 城市慢下来"].tap()
        XCTAssertTrue(app.buttons["打开播放页，城市慢下来"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["全屏播放"].exists, "听歌模式中的队列切歌应保留当前界面")
        app.buttons["打开播放页，城市慢下来"].tap()
        for label in ["上一首", "下一首", "字幕", "队列", "评论与点赞"] {
            XCTAssertTrue(app.buttons[label].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons[label].isHittable, "\(label) 应无需滚动即可点击")
        }
        let dock = app.otherElements["player-controls"]
        XCTAssertTrue(dock.exists)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(dock.frame))
        capture("播放器-固定控制与评论入口-演示数据", app: app)
        app.buttons["更多操作"].tap()
        app.buttons["查看 UP 主投稿"].tap()
        XCTAssertTrue(app.navigationBars["UP 主投稿"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["播放 雨落在窗沿"].waitForExistence(timeout: 5))
        capture("播放器-查看作者其他内容-演示数据", app: app)
        app.buttons["完成"].tap()
        app.buttons["下一首"].tap()
        XCTAssertTrue(app.staticTexts["雨落在窗沿"].waitForExistence(timeout: 5))
        app.buttons["上一首"].tap()
        XCTAssertTrue(app.staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["评论与点赞"].tap()
        XCTAssertTrue(app.staticTexts["慢下来，听见晚风里的片刻。"].waitForExistence(timeout: 5))
        capture("播放器-评论阅读-演示数据", app: app)
        app.buttons["完成"].tap()
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.buttons["跳转到 让晚风经过耳边"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["下一首"].isHittable)
        XCTAssertTrue(app.buttons["队列"].isHittable)
        capture("播放器-歌词与固定控制-演示数据", app: app)
        app.buttons["队列"].tap()
        app.buttons["队列选择 晚风里的片刻"].tap()
        XCTAssertTrue(app.staticTexts["晚风里的片刻"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["评论与点赞"].isHittable)
        app.buttons["收起"].tap()
        XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].waitForExistence(timeout: 5))
    }

    @MainActor func testMiniPlayerDoesNotOverlapTabsAcrossPagesAndRotation() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
            app.buttons["为你推荐"].firstMatch.tap()
        } else {
            XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
            app.buttons["查看更多为你推荐"].tap()
        }
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].waitForExistence(timeout: 5))
        if UIDevice.current.userInterfaceIdiom == .pad { app.buttons["首页"].tap() }
        else { app.navigationBars.buttons.element(boundBy: 0).tap() }
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft] {
            XCUIDevice.shared.orientation = orientation
            // 手机浏览页按现有策略保持竖屏；平板跟随设备旋转。
            let landscape = UIDevice.current.userInterfaceIdiom == .pad && orientation == .landscapeLeft
            let rotated = expectation(for: NSPredicate { _, _ in
                let frame = app.windows.firstMatch.frame
                return landscape ? frame.width > frame.height : frame.height > frame.width
            }, evaluatedWith: app)
            wait(for: [rotated], timeout: 10)
            for title in ["发现", "搜索", "资料库", "我的"] {
                let tab = UIDevice.current.userInterfaceIdiom == .pad ? app.buttons[title].firstMatch : app.tabBars.buttons[title].firstMatch
                XCTAssertTrue(tab.isHittable); tab.tap()
                let mini = app.otherElements["mini-player"].firstMatch
                XCTAssertTrue(mini.waitForExistence(timeout: 5))
                if UIDevice.current.userInterfaceIdiom == .pad {
                    // iPad 顶部标签在可访问树中是按钮，不归入 TabBar。
                    for label in ["发现", "搜索", "资料库", "我的"] {
                        XCTAssertFalse(mini.frame.intersects(app.buttons[label].firstMatch.frame), "迷你播放器不应覆盖导航按钮")
                    }
                } else {
                    XCTAssertFalse(mini.frame.intersects(app.tabBars.firstMatch.frame), "迷你播放器不应覆盖导航栏")
                }
                XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].isHittable)
                let layout = orientation == .portrait ? "竖屏" : landscape ? "横屏" : "设备横置保持竖屏"
                capture("迷你播放器-\(layout)-\(title)-演示数据", app: app)
            }
            app.buttons["打开播放页，晚风里的片刻"].tap()
            XCTAssertTrue(app.buttons["收起"].waitForExistence(timeout: 5))
            app.buttons["收起"].tap()
        }
    }

    @MainActor func testNowPlayingLyricsSwitchCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        selectTab("设置", app: app)
        let row = app.switches["在主标题显示当前歌词"].firstMatch
        for _ in 0..<5 {
            if row.exists && row.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(row.exists && row.isHittable)
        XCTAssertEqual(row.value as? String, "1")
        capture("CarPlay-逐句歌词开启-演示设置", app: app)
        let control = row.switches.firstMatch
        (control.exists ? control : row).tap()
        XCTAssertEqual(row.value as? String, "0")
        capture("CarPlay-逐句歌词关闭-演示设置", app: app)
        (control.exists ? control : row).tap()
        XCTAssertEqual(row.value as? String, "1")
    }

    @MainActor func testAccountCommentsAndAllDiscoveryPagesCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--demo-account", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        capture("发现-首页上部-演示数据", app: app)
        app.swipeUp(); capture("发现-首页下部-演示数据", app: app); app.swipeDown()
        for title in ["为你推荐", "音乐榜", "音乐区热门", "新鲜投稿"] {
            let more = app.buttons["查看更多" + title]
            if !more.isHittable { app.swipeUp() }
            more.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("发现-" + title + "-演示数据", app: app)
            app.navigationBars[title].buttons.element(boundBy: 0).tap()
        }
        selectTab("我的", app: app)
        XCTAssertTrue(app.staticTexts["界面示例用户"].waitForExistence(timeout: 5))
        capture("我的-已登录-演示数据", app: app)
        for title in ["收藏夹", "点赞的视频", "观看历史", "我的关注", "我的投稿"] {
            app.buttons[title].tap()
            let pageTitle = title == "我的投稿" ? "UP 主投稿" : title
            XCTAssertTrue(app.navigationBars[pageTitle].waitForExistence(timeout: 5))
            capture("我的-" + title + "-演示数据", app: app)
            if title == "收藏夹" {
                app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "音乐收藏 · 示例")).firstMatch.tap()
                XCTAssertTrue(app.navigationBars["收藏夹内容"].waitForExistence(timeout: 5))
                capture("我的-收藏夹内容-演示数据", app: app)
                app.navigationBars.buttons.element(boundBy: 0).tap()
            }
            if title == "我的关注" {
                app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例 UP 主")).firstMatch.tap()
                XCTAssertTrue(app.navigationBars["UP 主投稿"].waitForExistence(timeout: 5))
                capture("我的-UP主投稿-演示数据", app: app)
                app.navigationBars.buttons.element(boundBy: 0).tap()
            }
            if title == "点赞的视频" {
                app.buttons["播放 晚风里的片刻"].tap()
            }
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        app.buttons["打开播放页，晚风里的片刻"].tap()
        let comments = app.buttons["评论与点赞"]
        if !comments.isHittable { app.swipeUp() }
        XCTAssertTrue(comments.waitForExistence(timeout: 5)); comments.tap()
        XCTAssertTrue(app.staticTexts["慢下来，听见晚风里的片刻。"].waitForExistence(timeout: 5))
        capture("互动-评论列表-演示数据", app: app)
        app.buttons["点赞视频"].tap()
        XCTAssertTrue(app.buttons["已点赞"].waitForExistence(timeout: 5))
        let draft = app.textFields["发送评论，需要登录"]
        draft.tap(); draft.typeText("UI 演示评论")
        capture("互动-输入评论-演示数据", app: app)
        app.buttons["发送"].tap()
        XCTAssertTrue(app.staticTexts["UI 演示评论"].waitForExistence(timeout: 5))
        capture("互动-发送成功-演示数据", app: app)
        app.buttons["完成"].tap(); app.buttons["收起"].tap()
        selectTab("我的", app: app)
        let signOut = app.buttons["退出登录"]
        for _ in 0..<4 {
            if signOut.frame.maxY >= app.otherElements["mini-player"].firstMatch.frame.minY { app.swipeUp() }
        }
        XCTAssertLessThan(signOut.frame.maxY, app.otherElements["mini-player"].firstMatch.frame.minY)
        capture("我的-底部操作与迷你播放器-演示数据", app: app)
        signOut.tap()
        let login = app.buttons["登录本地示例"]
        XCTAssertTrue(login.waitForExistence(timeout: 5))
        capture("我的-未登录-演示数据", app: app)
        login.tap()
        XCTAssertTrue(app.images["登录二维码"].waitForExistence(timeout: 5))
        capture("登录-二维码-演示数据", app: app)
        app.buttons["取消"].tap()
    }

    @MainActor func testSearchStatesAndSettingsCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        selectTab("搜索", app: app)
        capture("搜索-浏览分类-演示数据", app: app)
        for (query, marker) in [("music", "选择 晚风里的片刻"), ("empty", "无结果"), ("error", "搜索失败"), ("loading", "正在搜索")] {
            let search = app.textFields["搜索视频、音乐、UP 主"]
            if app.buttons["清空搜索"].exists { app.buttons["清空搜索"].tap() }
            search.tap(); search.typeText(query + "\n")
            if query == "music" { XCTAssertTrue(app.buttons[marker].waitForExistence(timeout: 5)) }
            else if query == "empty" { XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "结果")).firstMatch.waitForExistence(timeout: 5)) }
            else { XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 5)) }
            capture("搜索-" + query + "-演示状态", app: app)
        }
        app.buttons["清空搜索"].tap()
        selectTab("设置", app: app)
        capture("设置-上部-演示数据", app: app)
        for key in ["音质", "响度标准化", "缓存上限", "播放器样式", "歌词舞台"] {
            let picker = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", key)).firstMatch
            for _ in 0..<3 { if !picker.isHittable { app.swipeUp() } }
            picker.tap(); capture("设置-" + key + "选择-演示数据", app: app)
            if app.navigationBars.buttons.count > 0 { app.navigationBars.buttons.element(boundBy: 0).tap() }
            else { app.tap() }
        }
        capture("设置-下部-演示数据", app: app)
        let network = app.buttons["网络与系统代理"]
        if !network.isHittable { app.swipeUp() }; network.tap()
        XCTAssertTrue(app.textViews["系统代理域名白名单"].waitForExistence(timeout: 5))
        capture("设置-网络代理-演示数据", app: app)
        app.buttons["完成"].tap()
    }

    @MainActor func testPluginInstallSettingsSourceSwitchAndBrowse() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        let pluginLink = app.buttons["settings-plugins"]
        XCTAssertTrue(pluginLink.waitForExistence(timeout: 5))
        XCTAssertTrue(pluginLink.isHittable, "设置首屏不滚动就能打开插件")
        capture("设置-首屏插件入口-演示数据", app: app)
        pluginLink.tap()
        XCTAssertTrue(app.navigationBars["安装插件"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["media.example.test"].exists)
        capture("插件-安装权限-演示数据", app: app)
        app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 5))
        capture("插件-列表-演示数据", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.tap()
        let server = app.textFields["服务地址"]
        XCTAssertTrue(server.waitForExistence(timeout: 5))
        let location = app.staticTexts["plugin-install-location"]
        XCTAssertTrue(location.waitForExistence(timeout: 5))
        XCTAssertTrue(location.label.hasSuffix("/installed/fixture"))
        server.tap(); server.typeText("https://media.example.test")
        let hideKeyboard = app.buttons["隐藏键盘"]
        if hideKeyboard.exists && hideKeyboard.isHittable { hideKeyboard.tap() }
        app.switches["启用示例"].tap()
        let save = app.buttons["保存"]
        for _ in 0..<3 {
            if save.exists && save.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(save.exists && save.isHittable)
        save.tap()
        capture("插件-设置-演示数据", app: app)
        selectTab("发现", app: app)
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        let oldPlayer = app.buttons["打开播放页，晚风里的片刻"]
        XCTAssertTrue(oldPlayer.waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let pluginTab = app.buttons["source-tab-fixture"]
        XCTAssertTrue(pluginTab.waitForExistence(timeout: 5))
        let builtInTab = app.buttons["source-tab-demo"]
        let sources = app.scrollViews["source-tabs"]
        let mode = app.buttons["切换播放模式"]
        XCTAssertTrue(builtInTab.isHittable && pluginTab.isHittable, "空间足够时两个音源都可直接点击")
        XCTAssertGreaterThanOrEqual(sources.frame.width, app.navigationBars.firstMatch.frame.width - 32, "音源切换占满内容区域的一整行")
        XCTAssertGreaterThanOrEqual(pluginTab.frame.minY, mode.frame.maxY, "音源与模式操作分行显示")
        capture("插件-音源独占整行-演示数据", app: app)
        pluginTab.tap()
        XCTAssertTrue(app.navigationBars["示例插件首页"].waitForExistence(timeout: 5))
        XCTAssertTrue(oldPlayer.waitForNonExistence(timeout: 5), "切换平台应清除上一平台暂停中的曲目")
        XCTAssertTrue(pluginTab.isSelected)
        XCTAssertTrue(app.buttons["播放 插件示例歌曲"].exists)
        capture("插件-首页-演示数据", app: app)
        app.buttons["更多示例"].tap()
        XCTAssertTrue(app.navigationBars["示例分页"].waitForExistence(timeout: 5))
        capture("插件-分页-演示数据", app: app)
    }

    @MainActor func testCompactRootPagesKeepContentAndActionsVisible() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 10))
        capture("紧凑首页-发现", app: app)
        app.buttons["搜索"].firstMatch.tap()
        let search = app.textFields["搜索视频、音乐、UP 主"]
        XCTAssertTrue(search.waitForExistence(timeout: 5) && search.isHittable)
        capture("紧凑首页-搜索", app: app)
        selectTab("资料库", app: app)
        XCTAssertTrue(app.buttons["添加到资料库"].waitForExistence(timeout: 5))
        capture("紧凑首页-资料库", app: app)
        selectTab("我的", app: app)
        XCTAssertTrue(app.buttons["open-settings"].waitForExistence(timeout: 5))
        capture("紧凑首页-我的", app: app)
    }

    @MainActor func testPluginTrackRowsExplainUnavailableAudio() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "--plugin-track-problems", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        let pluginLink = app.buttons["settings-plugins"]
        XCTAssertTrue(pluginLink.waitForExistence(timeout: 5)); pluginLink.tap()
        let install = app.buttons["安装"]
        XCTAssertTrue(install.waitForExistence(timeout: 5)); install.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 5))
        selectTab("发现", app: app)
        let pluginTab = app.buttons["source-tab-fixture"]
        XCTAssertTrue(pluginTab.waitForExistence(timeout: 5)); pluginTab.tap()
        XCTAssertTrue(pluginTab.isSelected)
        XCTAssertTrue(app.buttons["播放 会员示例曲目"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["播放 会员示例曲目"].value as? String, "需要 VIP")
        let missing = app.buttons["播放 无音源示例曲目"]
        XCTAssertTrue(missing.exists)
        XCTAssertNotEqual(missing.value as? String, "音源不可用")
        missing.tap()
        let play = app.buttons["播放"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5)); play.tap()
        let unavailable = expectation(for: NSPredicate(format: "value == %@", "音源不可用"), evaluatedWith: missing)
        wait(for: [unavailable], timeout: 5)
        capture("插件-曲目不可播原因", app: app)
    }

    @MainActor func testLanguageSwitchUpdatesPagesAndSettings() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["我的"].waitForExistence(timeout: 15))
        selectTab("设置", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "语言")).firstMatch.tap()
        app.buttons["English"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Appearance")).firstMatch.exists)
        capture("设置-English", app: app)
        selectTab("Discover", app: app)
        XCTAssertTrue(app.staticTexts["Recommended"].waitForExistence(timeout: 5))
        selectTab("Library", app: app)
        let songs = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Songs")).firstMatch
        XCTAssertTrue(songs.waitForExistence(timeout: 5))
        songs.tap()
        XCTAssertTrue(app.navigationBars["Songs"].waitForExistence(timeout: 5))
        capture("资料库-English", app: app)
        selectTab("Settings", app: app)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Language")).firstMatch.tap()
        app.buttons["简体中文"].tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        capture("设置-简体中文", app: app)
        app.buttons["dismiss-settings"].tap()
        XCTAssertTrue(app.tabBars.buttons["我的"].isSelected)
    }

    @MainActor func testPlayerStylesAndLibraryPagesCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        capture("首页-演示数据", app: app)
        selectTab("资料库", app: app)
        for title in ["歌曲", "专辑", "作者", "喜欢", "最近播放"] {
            let link = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
            XCTAssertTrue(link.waitForExistence(timeout: 10))
            link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("本地-\(title)-演示数据", app: app)
            if title == "专辑" {
                app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "城市的声音 · 示例")).firstMatch.tap()
                XCTAssertTrue(app.buttons["播放 晚风里的片刻"].waitForExistence(timeout: 5))
                capture("本地-专辑详情-演示数据", app: app)
                app.navigationBars.buttons.element(boundBy: 0).tap()
            }
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        selectTab("设置", app: app)
        capture("设置-演示数据", app: app)
        selectTab("发现", app: app)
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        app.buttons["打开播放页，晚风里的片刻"].tap()
        XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["队列"].isHittable)
        capture("播放器-封面-演示数据", app: app)
        for (name, id) in [("黑胶", "vinyl"), ("磁带", "cassette")] {
            app.buttons["播放界面样式"].tap()
            if app.buttons["播放器样式"].exists { app.buttons["播放器样式"].tap() }
            app.buttons[name].tap()
            XCTAssertTrue(app.descendants(matching: .any)["player-artwork-\(id)"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["下一首"].isHittable)
            XCTAssertTrue(app.buttons["队列"].isHittable)
            capture("播放器-\(name)-演示数据", app: app)
        }
        app.buttons["字幕"].tap()
        for (name, id) in [("Flow · 流动", ""), ("Steps · 阶梯", "partita"), ("Tilt · 倾诉", "tilt")] {
            app.buttons["播放界面样式"].tap()
            if app.buttons["歌词舞台"].exists { app.buttons["歌词舞台"].tap() }
            app.buttons[name].tap()
            XCTAssertTrue(app.descendants(matching: .any)["lyric-style-\(id)"].waitForExistence(timeout: 5))
            capture("歌词-\(name)-演示数据", app: app)
        }
        app.buttons["播放界面样式"].tap(); app.buttons["播放器样式"].tap(); app.buttons["磁带"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["player-artwork-cassette"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["跳转到 让晚风经过耳边"].exists)
        app.buttons["切换到实时视频"].tap()
        XCTAssertTrue(app.staticTexts["此音源不支持视频播放"].waitForExistence(timeout: 5))
        app.buttons["video-open-music"].tap()
        XCTAssertTrue(app.buttons["播放界面样式"].waitForExistence(timeout: 5))
        app.buttons["播放界面样式"].tap(); app.buttons["歌词舞台"].tap(); app.buttons["Tilt · 倾诉"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["lyric-style-tilt"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["此音源不支持视频播放"].exists)
        app.buttons["播放界面样式"].tap(); app.buttons["播放器样式"].tap(); app.buttons["封面"].tap()
        XCTAssertTrue(app.buttons["切换到实时视频"].waitForExistence(timeout: 5))
        capture("播放器-歌词样式立即展示并返回封面-演示数据", app: app)
    }

    @MainActor func testLocalLibraryPlaylistCreateAddRemoveAndRename() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let libraryTab = app.tabBars.buttons["资料库"]
        XCTAssertTrue(libraryTab.waitForExistence(timeout: 15))
        libraryTab.tap()
        let playlist = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "晚间散步 · 示例")).firstMatch
        XCTAssertTrue(playlist.waitForExistence(timeout: 10))
        capture("资料库-演示数据", app: app)
        app.buttons["新建歌单"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("UI 回归歌单")
        capture("歌单-创建弹窗-演示数据", app: app)
        app.alerts.buttons["创建"].tap()
        let created = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "UI 回归歌单")).firstMatch
        XCTAssertTrue(created.waitForExistence(timeout: 5))
        created.tap()
        app.buttons["添加歌曲"].tap()
        let addTrack = app.buttons["选择 晚风里的片刻"]
        XCTAssertTrue(addTrack.waitForExistence(timeout: 5))
        addTrack.tap()
        capture("添加歌曲-演示数据", app: app)
        app.buttons["添加 1 首"].tap()
        let row = app.buttons["播放 晚风里的片刻"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        capture("新建歌单内容-演示数据", app: app)
        app.buttons["曲目操作 晚风里的片刻"].tap()
        capture("歌单-曲目菜单-演示数据", app: app)
        app.buttons["从歌单移除"].tap()
        XCTAssertTrue(app.staticTexts["暂无歌曲"].waitForExistence(timeout: 5))
        app.navigationBars["UI 回归歌单"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(created.waitForExistence(timeout: 5))
        app.buttons["歌单操作 UI 回归歌单"].tap()
        app.buttons["重命名"].tap()
        capture("歌单-重命名弹窗-演示数据", app: app)
        let rename = app.alerts.textFields.firstMatch
        XCTAssertTrue(rename.waitForExistence(timeout: 5))
        rename.tap(); rename.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "UI 回归歌单".count) + "已重命名")
        app.alerts.buttons["保存"].tap()
        let renamed = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "已重命名")).firstMatch
        XCTAssertTrue(renamed.waitForExistence(timeout: 5))
        app.buttons["歌单操作 已重命名"].tap()
        app.buttons["删除歌单"].tap()
        capture("歌单-删除确认-演示数据", app: app)
        app.buttons["删除歌单"].tap()
        XCTAssertTrue(renamed.waitForNonExistence(timeout: 5))
    }

    @MainActor func testPluginCheckPresentationRequiresConfirmationAndRecoversFromFailure() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-check-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        let github = app.buttons["plugin-install-github"]
        let checking = app.staticTexts["plugin-checking"]
        let install = app.buttons["安装"]
        for repo in ["fixture/failure", "fixture/success"] {
            XCTAssertTrue(github.waitForExistence(timeout: 5))
            github.tap()
            let alert = app.alerts.firstMatch
            let field = alert.textFields.firstMatch
            XCTAssertTrue(field.waitForExistence(timeout: 5))
            field.tap()
            if repo == "fixture/success" { field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "fixture/failure".count)) }
            field.typeText(repo)
            alert.buttons["检查"].tap()
            XCTAssertTrue(checking.waitForExistence(timeout: 3))
            XCTAssertFalse(github.isHittable)
            XCTAssertFalse(install.exists)
            capture("插件-居中检查-\(repo.hasSuffix("failure") ? "失败路径" : "成功路径")", app: app)
            XCTAssertTrue(checking.waitForNonExistence(timeout: 10))
            if repo == "fixture/failure" {
                XCTAssertTrue(app.staticTexts["插件检查失败"].exists)
                XCTAssertFalse(install.exists)
                capture("插件-检查失败弹窗", app: app)
                app.buttons["dismiss-plugin-check-error"].tap()
            } else {
                XCTAssertTrue(install.waitForExistence(timeout: 5))
                capture("插件-权限确认", app: app)
                app.buttons["取消"].tap()
                XCTAssertTrue(install.waitForNonExistence(timeout: 5))
            }
        }
        XCTAssertTrue(github.isHittable)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.exists)
    }

    @MainActor func testPlatformNodeRequiresAddressAndPassword() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        let address = app.textFields["HTTPS 节点地址"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        XCTAssertTrue(app.secureTextFields["节点密码"].exists)
        XCTAssertFalse(app.buttons["接入节点"].isEnabled)
        capture("设置-MB-Server-未接入", app: app)
    }

    @MainActor func testPluginsStarsImportAsksForAPublicList() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        let open = app.buttons["plugin-import-stars"]
        XCTAssertTrue(open.waitForExistence(timeout: 5)); open.tap()
        let field = app.textFields["plugin-stars-address"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("https://example.test/list")
        app.buttons["plugin-stars-read"].tap()
        let problem = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "请输入公开的 GitHub 星标列表地址")).firstMatch
        XCTAssertTrue(problem.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["plugin-stars-confirm"].exists)
        capture("插件-星标列表导入弹窗", app: app)
        app.buttons["关闭"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 5))
    }

    @MainActor func testPluginVideoSearchKeepsMusicAndVideoResultsSeparate() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        selectTab("设置", app: app)
        app.buttons["settings-plugins"].tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 5)); app.buttons["安装"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.waitForExistence(timeout: 5))
        selectTab("发现", app: app)
        chooseMode("视频模式", app: app)
        selectTab("搜索", app: app)
        let videos = app.segmentedControls["search-scope"].buttons["视频"]
        XCTAssertFalse(videos.exists)
        selectTab("发现", app: app)
        app.buttons["source-tab-fixture"].tap()
        selectTab("搜索", app: app)
        XCTAssertTrue(videos.waitForExistence(timeout: 5))
        let field = app.textFields["搜索视频、音乐、UP 主"]
        field.tap(); field.typeText("music\n")
        let musicResult = app.buttons["选择 music · 插件结果"]
        XCTAssertTrue(musicResult.waitForExistence(timeout: 5))
        videos.tap()
        XCTAssertFalse(musicResult.exists)
        field.tap(); field.typeText("clip\n")
        XCTAssertTrue(app.buttons["选择 clip · 视频结果"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["返回浏览"].exists)
        app.buttons["加载更多"].tap()
        let next = app.buttons["选择 clip · 更多视频"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        capture("插件-独立视频搜索", app: app)
        app.segmentedControls["search-scope"].buttons["音乐"].tap()
        XCTAssertTrue(musicResult.waitForExistence(timeout: 5))
        videos.tap()
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["返回浏览"].exists)
        next.tap()
        XCTAssertTrue(app.buttons["返回浏览"].waitForExistence(timeout: 5))
    }

    @MainActor func testPluginEntitiesPlaylistSelectionAndManagementCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "--plugin-demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["我的"].waitForExistence(timeout: 15))
        selectTab("设置", app: app)
        let plugins = app.buttons["settings-plugins"]
        for _ in 0..<5 { if !plugins.isHittable { app.swipeUp() } }; plugins.tap()
        XCTAssertTrue(app.buttons["安装"].waitForExistence(timeout: 5)); app.buttons["安装"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例插件")).firstMatch.tap()
        app.switches["启用音源"].tap()
        capture("插件-停用音源-演示数据", app: app)
        app.switches["启用音源"].tap()
        selectTab("发现", app: app)
        app.buttons["source-tab-fixture"].tap()
        XCTAssertTrue(app.navigationBars["示例插件首页"].waitForExistence(timeout: 5))
        for (title, detail) in [("示例专辑", "专辑"), ("示例作者", "作者"), ("示例播客", "播客"), ("示例电台", "电台")] {
            let link = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
            for _ in 0..<3 { if !link.isHittable { app.swipeUp() } }
            link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("插件-" + detail + "详情-演示数据", app: app)
            if detail == "专辑" { app.buttons["收藏专辑"].tap(); XCTAssertTrue(app.buttons["取消收藏专辑"].waitForExistence(timeout: 5)) }
            if detail == "作者" { app.buttons["关注作者"].tap(); XCTAssertTrue(app.buttons["取消关注作者"].waitForExistence(timeout: 5)) }
            if detail == "播客" { app.buttons["订阅播客"].tap(); XCTAssertTrue(app.buttons["取消订阅"].waitForExistence(timeout: 5)) }
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        let playlist = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "示例歌单")).firstMatch
        if !playlist.isHittable { app.swipeDown() }; playlist.tap()
        XCTAssertTrue(app.buttons["添加平台歌曲"].waitForExistence(timeout: 5)); app.buttons["添加平台歌曲"].tap()
        let field = app.textFields["搜索音源"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap(); field.typeText("music\n")
        XCTAssertTrue(app.buttons["选择 music · 插件结果"].waitForExistence(timeout: 5))
        app.buttons["选择 music · 插件结果"].tap()
        capture("插件-歌单搜索选歌-演示数据", app: app)
        app.buttons["添加 1 首"].tap()
        XCTAssertTrue(app.buttons["播放 music · 插件结果"].waitForExistence(timeout: 5))
        capture("插件-歌单内容-演示数据", app: app)
        app.buttons["曲目操作 music · 插件结果"].tap()
        capture("插件-歌单曲目菜单-演示数据", app: app)
        app.buttons["从歌单移除"].tap()
        XCTAssertTrue(app.buttons["播放 music · 插件结果"].waitForNonExistence(timeout: 5))
        app.buttons["重命名歌单"].tap(); capture("插件-重命名歌单-演示数据", app: app); app.buttons["取消"].tap()
        app.buttons["删除平台歌单"].tap(); capture("插件-删除歌单确认-演示数据", app: app); app.buttons["取消"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        selectTab("我的", app: app)
        for title in ["平台歌单", "喜欢的歌曲", "最近播放", "收藏专辑", "订阅播客", "电台"] {
            let link = app.buttons[title]
            if !link.isHittable { app.swipeUp() }; link.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            capture("插件-我的-" + title + "-演示数据", app: app)
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
    }

    @MainActor func testLibrarySystemSheetsQueueEditAndEffectsCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["资料库"].waitForExistence(timeout: 15)); selectTab("资料库", app: app)
        app.buttons["添加到资料库"].tap(); capture("本地-添加菜单-演示数据", app: app)
        app.buttons["导入音频"].tap()
        let cancel = app.descendants(matching: .any).matching(identifier: "取消").firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10)); capture("本地-导入音频系统选择器", app: app); try dismissDocumentPicker(app)
        app.buttons["添加到资料库"].tap(); app.buttons["导入文件夹"].tap()
        XCTAssertTrue(cancel.waitForExistence(timeout: 10)); capture("本地-导入文件夹系统选择器", app: app); try dismissDocumentPicker(app)
        let songs = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "歌曲")).firstMatch
        songs.tap()
        app.buttons["曲目操作 晚风里的片刻"].tap(); capture("本地-曲目菜单-演示数据", app: app)
        app.buttons["导出音频"].tap()
        capture("本地-导出音频分享-演示数据", app: app)
        let shareClose = app.buttons["关闭"].firstMatch
        if shareClose.waitForExistence(timeout: 5) {
            shareClose.tap()
        } else {
            app.swipeDown()
            if shareClose.waitForExistence(timeout: 3) { shareClose.tap() }
        }
        selectTab("发现", app: app)
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        app.buttons["打开播放页，晚风里的片刻"].tap()
        app.buttons["队列"].tap(); app.buttons["编辑"].tap(); capture("播放-编辑队列-演示数据", app: app)
        app.buttons["完成"].firstMatch.tap()
        if app.buttons["完成"].exists { app.buttons["完成"].firstMatch.tap() }
        if app.buttons["收起"].exists { app.buttons["收起"].tap() }
        selectTab("设置", app: app)
        let spark = app.switches["歌词火花"]
        for _ in 0..<3 { if !spark.isHittable { app.swipeUp() } }
        capture("设置-播放效果开关-演示数据", app: app)
        for title in ["歌词火花", "默认打开歌词", "间奏显示热评", "进度条烟雾", "低音雾气背景", "从封面展开"] {
            let row = app.switches[title].firstMatch
            XCTAssertTrue(row.exists)
            let control = row.switches.firstMatch
            let target = control.exists ? control : row
            if target.value as? String != "1" { target.tap() }
            XCTAssertEqual(row.value as? String, "1")
        }
        capture("设置-播放效果已开启-演示数据", app: app)
        app.buttons["dismiss-settings"].tap()
        app.buttons["打开播放页，晚风里的片刻"].tap()
        XCTAssertTrue(app.buttons["跳转到 让晚风经过耳边"].waitForExistence(timeout: 5))
        capture("播放-默认歌词舞台-演示数据", app: app)
    }

    @MainActor func testGuestArtworkOpensLiveVideoAndReturnsToMusic() throws {
        guard ProcessInfo.processInfo.environment["MB_LIVE_MEDIA_UI"] == "1" else {
            throw XCTSkip("真实游客联网验证仅在显式开启 MB_LIVE_MEDIA_UI 时执行")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 30))
        capture("真实游客-首页", app: app)
        selectTab("搜索", app: app)
        let search = app.textFields["搜索视频、音乐、UP 主"]
        search.tap(); search.typeText("夜曲\n")
        let result = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 ")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 30))
        capture("真实游客-搜索结果", app: app)
        chooseMode("听歌模式", app: app)
        result.tap()
        let mini = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "打开播放页，")).firstMatch
        XCTAssertTrue(mini.waitForExistence(timeout: 10)); mini.tap()
        let controls = app.otherElements["player-controls"]
        let pause = controls.buttons["暂停"]
        XCTAssertTrue(pause.waitForExistence(timeout: 45)); pause.tap()
        // 暂停后操作样式，避免 XCTest 等待持续播放动画时跨到下一首。
        if !app.buttons["切换到实时视频"].exists {
            app.buttons["播放界面样式"].tap(); app.buttons["播放器样式"].tap(); app.buttons["封面"].tap()
        }
        app.buttons["切换到实时视频"].tap()
        let picture = app.descendants(matching: .any)["视频画面"].firstMatch
        XCTAssertTrue(picture.waitForExistence(timeout: 45))
        XCTAssertTrue(app.staticTexts["正在缓冲"].waitForNonExistence(timeout: 45))
        let frameReady = expectation(for: NSPredicate(format: "value == %@", "视频画面已显示"), evaluatedWith: picture)
        wait(for: [frameReady], timeout: 45)
        capture("真实游客-点击封面实时视频", app: app)
        app.buttons["video-open-music"].tap()
        XCTAssertTrue(app.buttons["切换到实时视频"].waitForExistence(timeout: 5))
        app.buttons["观看当前视频"].tap()
        XCTAssertTrue(picture.waitForExistence(timeout: 45))
        XCTAssertTrue(app.staticTexts["正在缓冲"].waitForNonExistence(timeout: 45))
        let secondFrameReady = expectation(for: NSPredicate(format: "value == %@", "视频画面已显示"), evaluatedWith: picture)
        wait(for: [secondFrameReady], timeout: 45)
        capture("真实游客-再次切换视频", app: app)
        app.buttons["video-open-music"].tap()
        XCTAssertTrue(controls.buttons["播放"].waitForExistence(timeout: 5))
        capture("真实游客-切回封面并暂停", app: app)
    }

    @MainActor func testAccessiblePlayerAndNavigationCapture() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["查看更多为你推荐"].waitForExistence(timeout: 15))
        app.buttons["查看更多为你推荐"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
        for title in ["发现", "搜索", "资料库", "我的"] {
            selectTab(title, app: app)
            let mini = app.otherElements["mini-player"].firstMatch
            XCTAssertTrue(mini.waitForExistence(timeout: 5))
            XCTAssertFalse(mini.frame.intersects(app.tabBars.firstMatch.frame))
            XCTAssertTrue(app.buttons["打开播放页，晚风里的片刻"].isHittable)
            capture("外观与字体-\(title)-演示数据", app: app)
        }
        app.buttons["打开播放页，晚风里的片刻"].tap()
        for label in ["下一首", "字幕", "队列"] {
            let control = app.buttons[label]
            for _ in 0..<4 { if !control.isHittable { app.swipeUp() } }
            XCTAssertTrue(control.isHittable)
        }
        capture("外观与字体-播放器-演示数据", app: app)
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.buttons["跳转到 让晚风经过耳边"].exists)
        capture("外观与字体-歌词-演示数据", app: app)
        for _ in 0..<4 { if !app.buttons["收起"].isHittable { app.swipeDown() } }
        app.buttons["收起"].tap()
        selectTab("设置", app: app)
        capture("外观与字体-设置-演示数据", app: app)
    }

    @MainActor private func selectTab(_ title: String, app: XCUIApplication) {
        if title == "设置" || title == "Settings" {
            if app.buttons["dismiss-settings"].exists { return }
            selectTab(title == "设置" ? "我的" : "Account", app: app)
            let button = app.buttons["open-settings"]
            XCTAssertTrue(button.waitForExistence(timeout: 5)); button.tap()
            XCTAssertTrue(app.buttons["dismiss-settings"].waitForExistence(timeout: 5))
            return
        }
        if app.descendants(matching: .any)["settings-sheet"].exists {
            for _ in 0..<5 {
                if app.buttons["dismiss-settings"].isHittable { break }
                let back = app.navigationBars.buttons["BackButton"].firstMatch
                if back.exists { back.tap() }
            }
        }
        if app.buttons["dismiss-settings"].exists { app.buttons["dismiss-settings"].tap() }
        if app.buttons["dismiss-plugins"].exists { app.buttons["dismiss-plugins"].tap() }
        let tab = UIDevice.current.userInterfaceIdiom == .pad ? app.buttons[title].firstMatch : app.tabBars.buttons[title].firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
    }

    @MainActor private func dismissDocumentPicker(_ app: XCUIApplication) throws {
        let button = app.buttons["取消"].firstMatch
        if button.waitForExistence(timeout: 3) { button.tap() }
        else {
            let other = app.otherElements["取消"].firstMatch
            XCTAssertTrue(other.waitForExistence(timeout: 5))
            let frame = other.frame
            XCTAssertGreaterThan(frame.width, 0)
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
        }
        let ready = expectation(for: NSPredicate { _, _ in app.buttons["添加到资料库"].isHittable }, evaluatedWith: app)
        wait(for: [ready], timeout: 5)
    }

    @MainActor private func capture(_ name: String, app: XCUIApplication) {
        // 采集屏幕坐标，避免 iPad 旋转后应用截图仍沿用旧方向而裁掉右侧。
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    @MainActor func testDiscoveryQueueModesAndProxyValidation() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let more = app.buttons["查看更多为你推荐"]
        XCTAssertTrue(more.waitForExistence(timeout: 15))
        more.tap()
        let first = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        first.tap()
        let mini = app.buttons["打开播放页，晚风里的片刻"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5))
        mini.tap()
        app.buttons["队列"].tap()
        XCTAssertTrue(app.navigationBars["播放队列 · 3"].waitForExistence(timeout: 5))
        capture("播放-队列-演示数据", app: app)
        app.buttons["队列选择 城市慢下来"].tap()
        XCTAssertTrue(app.staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["字幕"].tap()
        XCTAssertTrue(app.buttons["跳转到 让晚风经过耳边"].waitForExistence(timeout: 5))
        app.buttons["收起"].tap()
        chooseMode("视频模式", app: app)
        XCTAssertTrue(first.isHittable, "有暂停曲目时切模式不应跳转")
        XCTAssertFalse(app.buttons["全屏播放"].exists)
        XCTAssertTrue(app.buttons["打开播放页，城市慢下来"].exists)
        capture("视频-推荐浏览-演示数据", app: app)
        first.tap()
        XCTAssertTrue(app.buttons["全屏播放"].waitForExistence(timeout: 5))
        capture("视频-简介-演示数据", app: app)
        app.buttons["评论"].tap()
        XCTAssertTrue(app.staticTexts["慢下来，听见晚风里的片刻。"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.sliders["视频播放进度"].isHittable)
        capture("视频-评论固定画面-演示数据", app: app)
        app.buttons["队列"].tap()
        XCTAssertTrue(app.buttons["队列选择 城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["队列选择 城市慢下来"].tap()
        XCTAssertTrue(app.staticTexts["城市慢下来"].waitForExistence(timeout: 5))
        app.buttons["全屏播放"].tap()
        XCTAssertTrue(app.buttons["退出全屏"].waitForExistence(timeout: 5))
        capture("视频-横屏全屏-演示状态", app: app)
        app.buttons["退出全屏"].tap()
        XCTAssertTrue(app.buttons["返回浏览"].waitForExistence(timeout: 5))
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(app.navigationBars["为你推荐"].waitForExistence(timeout: 5))
        XCTAssertTrue(first.exists)
        chooseMode("听歌模式", app: app)
        XCTAssertTrue(first.isHittable)
        selectTab("设置", app: app)
        let network = app.buttons["网络与系统代理"]
        for _ in 0..<5 { if !network.isHittable { app.swipeUp() } }
        network.tap()
        let editor = app.textViews["系统代理域名白名单"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        editor.typeText("https://bad.example")
        app.buttons["保存"].tap()
        XCTAssertTrue(app.staticTexts["请输入域名或 *.域名；不能包含 URL、端口、账号或全局通配符"].waitForExistence(timeout: 5))
        capture("网络-校验错误-演示数据", app: app)
        app.buttons["完成"].tap()
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "iOS-首页与迷你播放器"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        selectTab("搜索", app: app)
        let search = app.textFields["搜索视频、音乐、UP 主"]
        if !search.isHittable { app.swipeDown() }
        search.tap()
        search.typeText("music\n")
        XCTAssertTrue(app.buttons["选择 晚风里的片刻"].waitForExistence(timeout: 5))
        chooseMode("视频模式", app: app)
        XCTAssertTrue(app.buttons["选择 晚风里的片刻"].isHittable)
        XCTAssertFalse(app.buttons["返回浏览"].exists)
        app.buttons["选择 晚风里的片刻"].tap()
        XCTAssertTrue(app.staticTexts["此音源不支持视频播放"].waitForExistence(timeout: 5))
    }

    @MainActor private func chooseMode(_ label: String, app: XCUIApplication) {
        if app.buttons[label].firstMatch.isHittable { app.buttons[label].firstMatch.tap() }
        else {
            app.buttons["切换播放模式"].tap()
            app.buttons[label].tap()
        }
    }

    @MainActor func testTabletRotationKeepsBrowserAndPlayer() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("仅验证平板布局") }
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
        for orientation in [UIDeviceOrientation.landscapeLeft, .portrait] {
            XCUIDevice.shared.orientation = orientation
            let card = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
            XCTAssertTrue(card.waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["首页"].isHittable)
            let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            screenshot.name = orientation == .portrait ? "iPad-竖屏" : "iPad-横屏"
            screenshot.lifetime = .keepAlways
            add(screenshot)
            app.buttons["为你推荐"].firstMatch.tap()
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.waitForExistence(timeout: 5))
            capture(orientation == .portrait ? "iPad-竖屏-推荐页" : "iPad-横屏-推荐页", app: app)
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch.tap()
            app.buttons["打开播放页，晚风里的片刻"].tap()
            XCTAssertTrue(app.buttons["队列"].waitForExistence(timeout: 5))
            capture(orientation == .portrait ? "iPad-竖屏-播放器" : "iPad-横屏-播放器", app: app)
            app.buttons["收起"].tap()
            app.buttons["首页"].tap()
        }
    }

    @MainActor func testTabletVideoDetailsFollowOrientation() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("仅验证平板布局") }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--demo", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.buttons["首页"].waitForExistence(timeout: 15))
        XCUIDevice.shared.orientation = .portrait
        chooseMode("视频模式", app: app)
        let card = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放 晚风里的片刻")).firstMatch
        XCTAssertTrue(card.isHittable)
        XCTAssertFalse(app.buttons["全屏播放"].exists)
        card.tap()
        let seek = app.sliders["视频播放进度"]
        let comments = app.buttons["评论"]
        XCTAssertTrue(comments.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(comments.frame.midY, seek.frame.maxY)
        capture("iPad-竖屏-视频简介-演示数据", app: app)
        comments.tap()
        let comment = app.staticTexts["慢下来，听见晚风里的片刻。"]
        XCTAssertTrue(comment.waitForExistence(timeout: 5))
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(comment.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(comments.frame.minX, seek.frame.maxX)
        capture("iPad-横屏-视频评论-演示数据", app: app)
        app.buttons["返回浏览"].tap()
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCUIDevice.shared.orientation = .portrait
    }
}
