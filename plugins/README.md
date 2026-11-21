# 山海插件

`builtin/` 保存随播放器分发的插件。每个平台是独立的标准插件目录，包含 `plugin.json`、入口脚本、翻译和相邻测试。当前内置插件是 `builtin/bilibili/`；播放器不直接调用其中的平台实现。

`builtin/assets.go` 只嵌入运行时文件。`internal/plugin.LoadBundled` 统一发现、写入软件数据目录并通过现有 JavaScript 宿主加载；平台目录不导入播放器页面。用户安装的同 ID 插件优先，插件数据继续保存在 `plugin-data/<id>`，移动源码目录不迁移账号或 Cookie。

## 职责边界

- 插件负责平台 API、签名、登录会话、分页、字段解析、媒体地址和字幕数据。接口依据留在各平台目录。
- 播放器负责窗口、队列、解码、播放时间轴、视频同步、字幕滚动、设置和安装更新。
- 平台需要新增交互时，先把需求抽象为 `internal/music` 的通用数据或可选接口，再在 `internal/plugin` 接入导出函数，由页面按能力决定是否显示。不能在播放器中根据 `bilibili` 或其他平台 ID 分支处理。
- 旧插件不必实现新增可选能力；新增平台不能修改其他平台的代码，也不为每个平台新增一套播放器。

## 山海已提供的通用扩展

以下能力最初用于内置哔哩哔哩，也供用户安装的其他插件使用。插件基础契约兼容的 MyGo Music 版本与山海的扩展能力不是同一个版本号，不能把山海扩展当成所有上游宿主都已实现。

| 插件导出 / 宿主接口 | 契约 | 播放器行为 |
| --- | --- | --- |
| `video({id, quality})` / `music.VideoSource` | 返回 `{url, headers?}`；`id` 与音频曲目相同。HTTP 请求头随视频下载传递 | 插件提供视频能力时显示视频，音频仍由共用播放器负责；主界面和状态栏预览跟随切歌、暂停和跳转 |
| `searchVideos({query, offset})`（iOS） | 可选；返回 `{tracks, more}`，`offset` 从 0 开始，按返回的原始 `tracks` 数量推进 | 视频模式下为支持该能力的音源显示独立的「视频」搜索；普通搜索继续调用 `search` |
| `cancelSignIn()` / `music.SignInCanceler` | 停止当前登录尝试、定时器和请求，使挂起的 `signIn()` 结束；未导出时取消为无操作 | 关闭登录弹窗后取消任务，并忽略迟到结果 |
| `lyrics({id})` | 沿用现有 `{lrc, translation}` 或 `{lines:[{start,end,text,words?}]}`；时间统一为毫秒 | 播放器和状态栏按音频时间轴显示；平台原始秒数在插件中转换 |
| 曲目 `unavailableReason` / `stream({id, quality})` | 曲目可声明 `vip`、`noSource`、`signIn`、`unavailable`。解析音频失败时可抛带相同 `code` 的 JavaScript `Error`；空 `url` 表示没有音源 | 列表按曲目来源和 ID 显示原因，不额外请求每行。仅在平台明确要求会员时使用 `vip`；旧版 `unavailable: true` 仍显示暂不可播放，不等同于 VIP |
| `Section.trackLayout: "videos"` | 可选横版封面卡片网格；保留标准 `tracks` 字段，省略时沿用原有布局 | 首页与浏览页显示 16:9 封面、时长和作者，点击直接播放所在分区队列 |
| `host.signInPrompt({qr,text})` | `qr` 可省略或为空，表示仅更新文字状态；提供时为扫码链接 | 通用登录弹窗展示准备/确认文字、二维码、刷新状态与取消操作 |
| `requestHeaders`、媒体 `headers` 和 `host.storage` | 沿用现有权限、请求头和隔离存储契约 | 统一处理媒体请求与本地持久化，页面不接触平台凭据 |

新增能力必须有不依赖具体平台的宿主回归。平台 API 和响应转换的测试放在插件目录，播放器页面与播放同步的测试留在播放器包。

## 自托管节点的会话同步

- `exportSession()` 返回 `{format, account_id, account_label?, session}`，没有已验证会话时返回 `null`。`account_id` 是平台稳定账号 ID；`session` 只包含审核过的可迁移字段，不能导出整个 `host.storage`。
- `validateSession({format, account_id, session})` 在线核对账号，返回标准账号对象或 `null`，不得写入实际会话。宿主再次检查当前节点和操作是否仍有效后，才调用 `commitSession({format, account_id, session, account})` 提交已验证的会话。有效本地会话或本机退出状态优先。
- 桌面宿主目前仅对随应用发布且导出完整接口的插件开放同步。同 ID 的手工安装包及节点目录的现有外部插件不具备同步资格。设备令牌、恢复密钥及加密传输由宿主管理，不暴露给插件。[协议与测试向量](https://github.com/xTreeRoot/MB/blob/server/server/EDGE.md)

## 开发与验证

```bash
npm run test:bilibili
CGO_ENABLED=0 go test ./plugins/...
CGO_ENABLED=0 go test ./internal/plugin ./internal/app
```

新增内置插件时新增目录、清单和入口脚本；当前嵌入规则自动包含各插件的 `plugin.json`、`index.js`、`locales/*.json`。需要其他运行时资源时只调整此资源清单，不把平台实现搬回播放器。`builtin/bilibili` 本身也可通过设置中的“从目录安装”安装，使用与社区插件相同的宿主。

设置中的“网络 → 系统代理白名单”按目标域名决定宿主 HTTP 请求是否使用系统代理，覆盖插件 API、封面及宿主下载的媒体；跳转后重新按目标域名判断，名单外的请求直连。默认名单仅包含 YouTube Music 相关域名。插件启动的外部程序使用各自的网络实现，不在宿主 HTTP 传输层中。
