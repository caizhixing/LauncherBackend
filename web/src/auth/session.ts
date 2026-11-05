// auth/session.ts —— token 存取；存储键冻结（D1：localStorage，7 天免登，后端有效期即上限）
export const TOKEN_STORAGE_KEY = 'feedback_admin_token'

export const session = {
  set(token: string): void {
    localStorage.setItem(TOKEN_STORAGE_KEY, token)
  },
  get(): string | null {
    return localStorage.getItem(TOKEN_STORAGE_KEY)
  },
  clear(): void {
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  },
}
