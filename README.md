# MB

MB 是支持多音源插件的音乐和视频播放器。桌面端使用 Go 与 [MyGo](https://github.com/egoist/mygo) 构建，基于 [egoist/mygo-music](https://github.com/egoist/mygo-music) 提交 `298c88a03407844ac1c4eb76e3590edfe4577121` 开发，遵循 AGPL-3.0 许可证。

## 下载与分支

| 项目 | 源码分支 | 已发布版本 |
| --- | --- | --- |
| 桌面 | `main`（本分支） | [0.1.13](https://github.com/xTreeRoot/MB/releases/tag/mb-desktop-v0.1.13)：macOS Apple Silicon ZIP，未公证 |
| iPhone / iPad | [`ios`](https://github.com/xTreeRoot/MB/tree/ios) | [0.1.0（构建 17）](https://github.com/xTreeRoot/MB/releases/tag/mb-ios-v0.1.0)：未签名 IPA，需自行签名后安装 |
| Android | `android`（独立开发） | 暂无公开安装包 |
| 自托管节点 | [`server`](https://github.com/xTreeRoot/MB/tree/server/server) | 按[部署说明](https://github.com/xTreeRoot/MB/blob/server/server/README.md)构建 |

客户端安装包和 SHA-256 校验文件见 [GitHub Releases](https://github.com/xTreeRoot/MB/releases)。桌面和 iOS 分别使用 `mb-desktop-v<版本>`、`mb-ios-v<版本>` 标签，不与上游插件宿主兼容版本混用。桌面更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 桌面功能

- 哔哩哔哩 App 扫码登录、扫码状态更新、二维码过期刷新和取消登录。
- 游客首页推荐、视频搜索、视频音频播放；登录后可读取个人内容、发送评论和视频点赞。
- 个人中心、收藏夹、观看历史、关注列表和 UP 主投稿分页。
- 带每句开始与结束时间的字幕，转换为播放器毫秒时间轴并随播放滚动。
- 音乐与视频模式、播放队列、进度跳转、随机和循环播放、本地音频、缓存与音频下载。支持的媒体可以分段跳读；不支持分段时回退到顺序读取。遇到可恢复的流播错误时尝试从原位置继续。
- 按音源分标签，支持独立背景皮肤。列表会标注已知不可播原因，例如需要会员或没有可用音源。
- 中文和英文界面、macOS 托盘与刘海播放器。

登录 Cookie 保存在软件数据目录的 `plugin-data/bilibili/storage.json` 中。退出登录只停用 Cookie，重启后也保持退出状态；再次登录时先验证已保存的 Cookie，有效则直接复用，失效才重新扫码。字幕能否获取取决于视频提供的字幕和账号权限；缺少字幕时会返回明确状态。

## 管理插件

- 在插件页可从 GitHub 仓库、ZIP 或本地目录安装插件，也可启用、停用或删除已安装的外部插件。
- 「一键导入/覆盖」支持粘贴 GitHub Stars 列表地址，预览并确认后批量导入或覆盖；MB 主仓库和 MB 插件模板不计入导入。
- 从 GitHub 安装的插件可「检查更新」或「全部更新」。GitHub 相关域名默认加入系统代理白名单。

## 自托管节点

- 桌面端在「设置 → 接入节点」输入自托管服务器域名或 HTTPS 地址及密码。应用没有默认节点；只接入信任的服务器，**节点管理员可以解密同步会话**。接入协议见 [`server/EDGE.md`](https://github.com/xTreeRoot/MB/blob/server/server/EDGE.md)。
- 插件设置可从节点的签名目录安装缺失的插件；仍需确认权限。节点不可用、签名或包校验失败都不会替换已有插件或启用停用项。
- 内置哔哩哔哩支持同步登录会话；已有有效本机登录优先，可手动同步当前账号。退出本机不会删除云端，云端删除是单独操作。外部插件暂只支持安装，不迁移其登录数据。
- 账号资料同步与登录会话同步是两条独立路径。桌面和 iOS 的资料接口只加密同步已验证账号的 ID 和显示名，不上传 Cookie，也不会把同步到的资料当作登录。该功能依赖 `/v1/data`，目前公开的 `server` 分支尚未包含这个接口，真实账号跨端同步尚未验收。

## 开发与验证

需要 Go 1.27.3 或更新版本、Node.js 22.6 或更新版本。桌面应用无需安装网站依赖；MyGo 会使用本机 Node.js 或 Bun 读取配置。

```bash
go tool mygo dev
CGO_ENABLED=0 go test ./...
go tool mygo vet .
npm run test:bilibili
```

macOS 本地打包：

```bash
go tool mygo build -sign - -skip-notarize
```

产物位于 `build/darwin-arm64/MB.app`。默认使用本地临时签名；正式公证发布需要维护者自行配置 Apple 签名和公证凭据。

## 平台策略

通用播放器只依赖 `internal/music.Source`。自带插件集中在 `plugins/builtin/<platform>`，每个平台有自己的清单、网络权限、会话和 API 实现，通过通用的 `internal/plugin.LoadBundled` 加载。当前仅内置哔哩哔哩；源码、测试和平台资料均独立于播放器页面。

新增平台时新增独立目录并声明其已实现能力，运行时文件通过 `plugins/builtin/assets.go` 嵌入。通用页面、播放器和登录弹窗不根据平台名称调用私有 API。为平台需求增加的视频、登录取消等逻辑必须扩展为可复用的播放器能力，用户安装的 MyGo 音源插件也可通过同一契约接入。能力契约见 [插件开发约定](plugins/README.md)，插件示例保留在 `examples/demo-plugin`。

正式包同时支持 `mb://install-plugin?repo=owner/repo` 和上游社区的 `mygo-music://install-plugin?repo=owner/repo` 安装链接；开发包使用各自的 `-dev` 协议。插件兼容版本按已采用的 MyGo Music `0.1.25` 宿主契约检查，与 MB 的产品版本独立。上游历史、更新流程和本地功能优先规则见 [UPSTREAM.md](UPSTREAM.md)。

哔哩哔哩能力通过平台策略适配已有 MyGo 能力入口。扫码登录使用官方 `qrcode/generate`、`qrcode/poll`，优先读取 `Set-Cookie`，兼容官方 `passport.biligame.com/crossDomain` 回调。字幕接口返回的秒数转换为毫秒后交给原生歌词组件。

## 来源与许可证

保留 MyGo Music 的 Go 模块路径、上游许可证和历史变更记录，便于核对来源与后续维护。`website/` 是保留的上游网站源码，不参与 MB 桌面打包；本次未发布网站。源项目的歌词舞台动画参考 [folia-major](https://github.com/chthollyphile/folia-major)。

源码仓库：[xTreeRoot/MB](https://github.com/xTreeRoot/MB)。许可证见 [LICENSE](LICENSE)。
