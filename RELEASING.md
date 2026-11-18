# MB 打包

1. 在 main 完成实现，运行 `npm run test:bilibili`、`CGO_ENABLED=0 go test ./...` 与 `go tool mygo vet .`。
2. 更新 `mygo.config.ts` 的版本和 `CHANGELOG.md`，提交并推送 main。确认工作区干净，本地 HEAD 等于 origin/main。
3. macOS 本地执行 `go tool mygo build -sign - -skip-notarize -skip-dmg`，产物在 `build/darwin-arm64/MB.app`。
4. 核对包版本、签名，并检查应用启动后安装的内置策略与源码一致。

默认使用本地临时签名，没有配置开发者证书、公证或自动更新。正式分发前由维护者提供自己的签名配置，不复用上游作者身份。GitHub 手动打包工作流只从 main 读取源码并上传工作流产物，不自动发布。
