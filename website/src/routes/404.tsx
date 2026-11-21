/** 提供山海网站未找到页面的路由与标题。 */
import { createFileRoute } from '@tanstack/solid-router'
import { NotFound } from '../components/NotFound'

export const Route = createFileRoute('/404')({
  head: () => ({ meta: [{ title: 'Page not found · Shanhai' }] }),
  component: NotFound,
})
