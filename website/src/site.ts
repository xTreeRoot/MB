/** 定义山海网站品牌、介绍、源码地址及构建时的发布与插件数据。 */
import type { Lang } from './i18n'
import type { Plugin } from './plugins'

export const repo = 'https://github.com/xTreeRoot/ShanhaiMusic'

export type Asset = { name: string; size: number; browser_download_url: string }
export type Release = { tag_name: string; published_at: string; assets: Asset[] }

declare const __RELEASE__: Release
// The latest release, as it was when the site was built (see vite.config.ts).
export const release = __RELEASE__

declare const __PLUGINS__: Plugin[]
// The plugins on GitHub, as they were when the site was built.
export const builtPlugins = __PLUGINS__
export const mygo = 'https://mygo.egoist.dev'

export const site = {
  name: '山海',
  title: {
    en: 'Shanhai · Every platform, one place',
    zh: '山海 · 万端归一',
  } satisfies Record<Lang, string>,
  description: {
    en: 'Shanhai brings music, video and information together through plugins, with native desktop, iOS and Android clients.',
    zh: '山海 · 万端归一。通过插件连接音乐、视频与资讯，提供桌面、iOS 和 Android 原生客户端。',
  } satisfies Record<Lang, string>,
}
