/** 配置 MB 桌面应用标识、打包目录及开发和正式版本的独立链接协议。 */
export default ({ command }: { command: string }) => ({
  name: "MB",
  identifier: "com.xtreeroot.mb",
  slug: "mb",
  version: "0.1.3",
  out: "build",
  urlSchemes: [command === "dev" ? "mb-dev" : "mb"],
  macos: {
    minimumSystemVersion: "13.0",
  },
  linux: {
    comment: "A music player for your files and more",
    categories: ["AudioVideo", "Audio", "Player"],
    webview: false,
  },
});
