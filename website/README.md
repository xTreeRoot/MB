# 山海网站

山海 · 万端归一。网站使用 TanStack Start 与 Solid，基于上游 MyGo Music 网站开发，在构建时预渲染为静态文件，可部署到 Cloudflare Workers 静态资源服务。

```sh
# 在仓库根目录安装依赖
bun install
# 在 website 目录执行
bun run dev
bun run build
bun run deploy
```

构建产物位于 `website/dist/client`。下载页在构建时读取 [xTreeRoot/MB](https://github.com/xTreeRoot/MB) 的最新 GitHub Release；发布新版后需重新构建网站。可通过 `GH_TOKEN` 或 `GITHUB_TOKEN` 为构建提供 API 访问凭据，不应将凭据写入源码。

网站品牌、标题、介绍与源码地址在 `src/site.ts` 维护。本次仅同步网站源码，未执行部署。
