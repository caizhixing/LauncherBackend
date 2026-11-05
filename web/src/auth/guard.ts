// auth/guard.ts —— 纯函数：routeGuard(hasToken, to) → 放行 | redirect('/login?returnUrl=' + to.fullPath)
//                  已登录访问 /login → redirect('/feedback')
// 返回 null = 放行；返回字符串 = 重定向目标（对应 vue-router beforeEach 语义）
const LOGIN_PATH = '/login'
const HOME_PATH = '/feedback'

// 入参按 vue-router fullPath（已编码形态）理解：仅转义会破坏 query 结构的 & 与 #，
// 既有 %XX 原样保留 —— 登录页 query 解码一次即还原完整路径（§2.5 returnUrl 需含筛选）
function asQueryValue(fullPath: string): string {
  return fullPath.replaceAll('&', '%26').replaceAll('#', '%23')
}

export function routeGuard(hasToken: boolean, toPath: string): string | null {
  // 只按路径比较：/login?returnUrl=... 这类带 query 的登录页不得再次重定向（避免循环）
  const routePath = toPath.split(/[?#]/, 1)[0]
  if (!hasToken && routePath !== LOGIN_PATH) {
    return `${LOGIN_PATH}?returnUrl=${asQueryValue(toPath)}`
  }
  if (hasToken && routePath === LOGIN_PATH) {
    return HOME_PATH
  }
  return null
}
