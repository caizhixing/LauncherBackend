// router.ts —— 两条路由 /login、/feedback；守卫接线在此文件（PRD §2.3，issue #1 交付，#2/#3 不改）
import { createRouter, createWebHistory } from 'vue-router'
import { createHttpClient } from './api/http'
import { routeGuard } from './auth/guard'
import { session } from './auth/session'
import FeedbackListView from './views/FeedbackListView.vue'
import LoginView from './views/LoginView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 两条业务路由为冻结契约；根路径仅做别名重定向，避免直达 / 时空白
    { path: '/', redirect: '/feedback' },
    { path: '/login', component: LoginView },
    { path: '/feedback', component: FeedbackListView },
  ],
})

router.beforeEach((to) => {
  const redirect = routeGuard(session.get() !== null, to.fullPath)
  if (redirect !== null) return redirect
})

// 共享 HttpClient 在「guard 装配处」注册 onUnauthorized（PRD §2.3）：
// 40100/40101 → 先清 token，再复用 routeGuard 生成含 returnUrl 的登录重定向（§2.5/§2.6）
export const http = createHttpClient(fetch.bind(globalThis), {
  getToken: session.get,
  onUnauthorized: () => {
    session.clear()
    const redirect = routeGuard(false, router.currentRoute.value.fullPath)
    if (redirect !== null) void router.push(redirect)
  },
})

export default router
