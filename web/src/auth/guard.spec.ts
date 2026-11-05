import { describe, expect, it } from 'vitest'
import { routeGuard } from './guard'

// 行为 1.6（PRD feedback-web-admin §4 任务 1）：纯函数
describe('GuardTest', () => {
  it('routeGuard(false, "/feedback") → 重定向 /login?returnUrl=/feedback', () => {
    expect(routeGuard(false, '/feedback')).toBe('/login?returnUrl=/feedback')
  })

  it('routeGuard(true, "/login") → 重定向 /feedback', () => {
    expect(routeGuard(true, '/login')).toBe('/feedback')
  })

  it('routeGuard(true, "/feedback") → 放行', () => {
    expect(routeGuard(true, '/feedback')).toBeNull()
  })

  it('routeGuard(false, "/login") → 放行（未登录允许进登录页）', () => {
    expect(routeGuard(false, '/login')).toBeNull()
  })

  it('returnUrl 携带筛选 query 时不破坏登录页 query 结构（& 转义，§2.5）', () => {
    expect(routeGuard(false, '/feedback?page=2&status=1')).toBe('/login?returnUrl=/feedback?page=2%26status=1')
  })

  it('未登录访问带 returnUrl 的 /login 不再重定向（避免循环）', () => {
    expect(routeGuard(false, '/login?returnUrl=%2Ffeedback')).toBeNull()
  })
})
