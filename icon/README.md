# 山海应用图标

`shanhai.png` 是三端共用的 1024 × 1024、不透明图标母版。用户选定了候选 1 的草帽熊猫头；图标保留原表情和草帽，裁去场景背景，使用统一底色。没有加入文字或改变角色表情。

参考画面来自[腾讯视频](https://v.qq.com/x/page/p3167rr3264.html)，[原始画面](https://vpic-cover.puui.qpic.cn/p3167rr3264/p3167rr3264_1755416574_hz.jpg/1280)。此记录仅说明素材来源。

在安装 Xcode 26 或更高版本的 macOS 上，从仓库根目录执行：

```sh
go run ./icon
```

该命令同步生成桌面正式版与开发版、iOS、Android、网站图标和 favicon。macOS 使用 Icon Composer 文档处理系统图标圆角；iOS 母版保持不透明且不预裁圆角。
