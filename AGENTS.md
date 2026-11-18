# MB 开发约定

本规则适用于仓库全部目录。MB 是 Go + MyGo 原生桌面播放器，目前仅内置哔哩哔哩。

## 工作与 Git

- 禁止 worktree。始终在 main 开发、验证、提交和推送；不创建功能分支。
- 上游更新遵循 `UPSTREAM.md`：保留 MB 现有功能，逐项审阅并合并上游修复；产品版本和插件宿主兼容版本独立维护，不通过整次 ours 合并或强制覆盖处理后续更新。
- 仅在 Codex 内使用 Computer Use，不控制用户电脑上的其他应用。
- 先读现有实现和真实接口合同，不猜测平台端点、状态、参数和字段。修改聚焦需求，不批量重构无关代码。
- 哔哩哔哩接口先对照 `/Users/xugenyin/file/bilibil-bot` 和 SocialCrawlerHub 的已验证实现；旧项目只读，不复制账号、Cookie 或档案。
- 凭据不硬编码、不提交仓库、不写日志。按用户要求，登录 Cookie 使用现有 host.storage 保存在软件数据目录；退出登录仅停用，重新登录先验证再复用，不使用系统钥匙串。
- 使用 xTreeRoot 对应的本地 Git 身份，提交信息使用中文。推送和发布依用户授权执行。
- 对用户用简体中文，直接报告改动、测试结果和未验证项；注释与文档保持准确。

## 平台与宿主策略

- 通用播放器依赖 `internal/music.Source`。平台私有 API、签名、分页、响应解析、会话、媒体地址和字幕转换都放入独立的 `plugins/builtin/<platform>`。
- 新平台通过独立目录、清单和 `internal/plugin.LoadBundled` 注册，不在通用业务与页面散落平台判断。只声明已实现能力。
- 为平台需求增加的播放器逻辑必须扩展为 `internal/music` 和 `internal/plugin` 的通用能力，供内置及外部插件复用；按 `plugins/README.md` 维护契约和不依赖平台的回归。
- 宿主差异使用 MyGo 已有能力或独立宿主策略，避免在通用业务中散落系统分支。
- 页面空间紧凑；不添加无意义的说明横幅、教程或重复标题。保留必要状态、业务操作和错误反馈。
- 本项目无 MySQL 和服务器部署任务；不照搬 SocialCrawlerHub 的数据库、服务器凭据和运维脚本。

## 实现和文件

- 遵守现有 Go 风格，修改后 gofmt。保持职责内聚，避免仅转发的无用包装。
- 新增或修改 TypeScript 文件须有首段用途 JSDoc；单文件最多 500 行。接口名以 I 开头，接口和字段提供中文注释；组件 Props 放同目录独立类型文件，并以 import type 引入。
- 业务参数和返回值保持类型完整，不使用 as any 或忽略报错掩盖契约问题。业务状态使用命名枚举或常量。
- TypeScript 具名箭头函数提供中文 JSDoc 和真实步骤注释；HTTP 请求函数以 Api 结尾并顶层具名导出。

## MyGo 与验证

- 修改 MyGo UI 前阅读 `.agents/skills/mygo-maintenance/SKILL.md` 和锁定版本源码，不猜 API。不得持久保存已过构建生命周期的 Element 或 Context。
- IO 放后台，UI 更新通过 Window.Update。对取消或已替换的登录忽略迟到响应。
- 只为能真实回归的行为写测试，例如输入动作、取消、分页、签名、字幕时间转换、播放队列、流解码和缓存。不要断言静态标签列表、默认表单顺序或 MyGo 本身的动画实现。
- UI 测试使用 ui.NewTester、newTestApp 和 fakeSource，通过可访问名称操作。新 UI 回归连续运行 -count=5，修复实际不稳定原因，不靠重试掩盖问题。
- 截图通过 MM_FRAMES 输出后检查，避免依赖真实时钟或仅凭像素断言。平台 Node 测试位于对应策略的 test 目录，Go 测试遵循上游相邻包约定。
- 提交前运行 `npm run test:bilibili`、`CGO_ENABLED=0 go test ./...` 和 `go tool mygo vet .`；构建桌面包，并核对包内源码与工作区一致。实际扫码与平台请求未验证时如实说明。
