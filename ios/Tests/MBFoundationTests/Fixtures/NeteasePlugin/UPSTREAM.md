# 网易云插件回归夹具

来自 [sogud/mygo-music-netease v0.1.14](https://github.com/sogud/mygo-music-netease/releases/tag/v0.1.14)，提交 `7b8cb7a7203170afbfee5cb132e34624f69013c4`。

`index.js`、`plugin.json` 和许可证从用户手机实际安装的公开插件读取，未复制账号或存储；已与上游该提交的 `index.js` 逐字节比对一致。入口 SHA-256：`63705cc522743590f4062160ab8bbf9541f879546916b79031211e796513e424`。

代码保持原样，用于验证宿主的文字提示、扫码、Cookie 响应和重新登录兼容性。网络回归使用虚构二维码和会话；手动联网验收只生成二维码并取消，不读取个人凭据。
