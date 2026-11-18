# 页面核对与验证

产品版本 0.1.0，当前 build 5；桌面功能对照 main `27d4337`。本轮底栏与歌词设置截图见 [build 5 图库](Build5/index.html) 和 [编号索引](Build5/SCREENSHOTS.md)。[原有完整图库](index.html) 与 [原有索引](SCREENSHOTS.md) 保留 build 4 的历史验证。

截图来自 XCTest 在 iPhone 18 Pro / iPad Pro 13-inch (M5) 模拟器上的实际操作。账号、歌曲、评论、插件均为隔离演示数据，系统分享页使用演示静音 WAV。它们用于检查页面、导航、弹窗与状态，不代表真实账号互动、真机媒体播放或第三方平台验收。每张图的测试名称、设备、采集时间与 SHA-256 记录在 [manifest.json](manifest.json)。

## Build 5（2026-11-18）

Swift 行为/合同回归 122 项通过（Foundation 96、B 站 26），新增系统正在播放歌词、暂停/拖动/换曲/迟到响应与设置迁移回归。iPhone 与 iPad 各两项相关 UI 测试通过：四个 Tab 的迷你播放器不覆盖导航、播放页可打开和收起、歌词开关可切换。手机普通页面按现有策略保持竖屏，平板验证横竖屏。新增 20 张演示截图；歌词设置截图不代表真实车机显示效果。

结果包为本地 `.build/Build5PhoneFinal.xcresult`、`.build/Build5TabletScreen.xcresult`。Release IPA 结构、ZIP、可执行权限检查通过，114 个生产源码/工程/脚本文件摘要与打包时的工作区一致。真实 CarPlay 和手机锁屏刷新尚待实测。

## Build 4 历史结果（2026-11-18）

Swift 行为/合同回归 115 项通过（Foundation 89、B 站 26）；SwiftUI 宿主编译、同步后工程的通用 iPhone/iPad `build-for-testing` 与 arm64 Release 打包通过。手机完整 UI 9 项、平板横竖屏 1 项通过，导出 96 张截图。测试包：`phone-final.xcresult`、`tablet-final.xcresult`，保留在本地 `.build/results/`。

Release IPA 结构/ZIP/可执行权限校验通过；生产源码 114 个文件的摘要与同步目录逐项一致；共 142 个源码/测试/工程/说明文件逐字节一致。仓库不包含个人 Team、描述文件、证书或 IPA。QuickJS 上游 C 文件保留原样，编译有整数转换警告；自有代码空白检查通过，上游文件不做无关格式改写。

## 覆盖范围

- 发现：首页上下部、四个更多页、搜索结果/加载/空白/失败。
- 我的：未登录、二维码、登录后、收藏夹及内容、点赞、历史、关注、投稿；评论列表/输入/发送结果。
- 播放：封面/黑胶/磁带，Flow/Steps/Tilt，歌词默认展开、播放效果开关、队列及编辑，视频全屏和不支持视频状态。
- 本地资料库：分类、专辑详情、歌单创建/重命名/删除/增删曲目，导入文件/文件夹系统选择器、导出音频分享面板。
- 插件：安装权限、列表、设置、启停、首页/分页，专辑/作者/歌单/播客/电台，个人内容、歌单搜索选歌及管理。
- 设置：外观/音质/响度/缓存/播放器和歌词样式，代理及校验错误，中英切换。
- iPad：首页、推荐页与播放器横竖屏。

## 复现

在 `ios/` 中执行；设备标识用本机 `xcrun simctl list devices available` 的输出替换：

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
swift test
swift build --target MBInterface
xcodebuild test -project MBiOS.xcodeproj -scheme MBiOS \
  -destination 'platform=iOS Simulator,id=IPHONE_SIMULATOR_UDID' \
  -parallel-testing-enabled NO \
  -skip-testing:MBiOSUITests/MBiOSUITests/testTabletRotationKeepsBrowserAndPlayer \
  -resultBundlePath .build/Phone.xcresult
xcodebuild test -project MBiOS.xcodeproj -scheme MBiOS \
  -destination 'platform=iOS Simulator,id=IPAD_SIMULATOR_UDID' \
  -only-testing:MBiOSUITests/MBiOSUITests/testTabletRotationKeepsBrowserAndPlayer \
  -resultBundlePath .build/Tablet.xcresult
xcrun xcresulttool export attachments --path .build/Phone.xcresult --output-path .build/phone-shots
xcrun xcresulttool export attachments --path .build/Tablet.xcresult --output-path .build/tablet-shots
python3 scripts/export-review.py .build/phone-shots .build/tablet-shots --output Review
bash scripts/package-ipa.sh
```

测试结果包与构建缓存不提交。真实联网验证、宿主差异与待做的真机验收见 [PARITY.md](../PARITY.md)。个人签名与安装步骤见 [INSTALL.md](../INSTALL.md)。
