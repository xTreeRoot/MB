/** 配置 MB 桌面应用标识、打包目录及 MB 和 MyGo Music 社区插件的安装协议。 */
export default ({ command }: { command: string }) => ({
  name: "MB",
  identifier: "com.xtreeroot.mb",
  slug: "mb",
  version: "0.1.8",
  out: "build",
  urlSchemes:
    command === "dev" ? ["mb-dev", "mygo-music-dev"] : ["mb", "mygo-music"],
  macos: {
    minimumSystemVersion: "13.0",
  },
  linux: {
    comment: "A music player for your files and more",
    categories: ["AudioVideo", "Audio", "Player"],
    webview: false,
  },
});
