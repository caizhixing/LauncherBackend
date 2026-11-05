// api/auth.ts —— 登录接缝（PRD §2.3：login(req) → LoginResponse，issue #14 交付）。
// 发请求一律经注入的 HttpClient.post（http.ts 冻结接缝；登录是公开接口 → auth:false）；
// 校验 / 文案映射 / 提交流程抽为纯函数与可注入编排，vitest node 环境直接可测（PRD §5）
import { ref } from 'vue'
import type { Ref } from 'vue'
import { ApiError } from './http'
import type { HttpClient } from './http'
import { ErrorCode } from './types'
import type { LoginRequest, LoginResponse } from './types'

/** 后端契约：POST /api/login（公开，不带 Bearer） */
export const LOGIN_URL = '/api/login'

/** D12：记住用户名（不记密码）的 localStorage 键，键名冻结 */
export const REMEMBERED_USERNAME_KEY = 'feedback_admin_username'

/** 行为 2.3 跳转语义：returnUrl 优先，无则 /feedback（guard.ts 生成的已编码 fullPath 原样透传） */
export const DEFAULT_LOGIN_REDIRECT = '/feedback'

export const LOGIN_COPY = {
  MISSING_USERNAME: '请输入用户名',
  MISSING_PASSWORD: '请输入密码',
  BAD_CREDENTIALS: '用户名或密码错误',
  RATE_LIMITED: '尝试过于频繁，请稍后再试',
  UNAVAILABLE: '服务暂不可用，请稍后重试',
} as const

export interface LoginFormErrors {
  username?: string
  password?: string
}

/** 行为 2.1：必填本地校验（纯函数）；空对象 = 可提交。用户名按 trim 判空，密码保留原样 */
export function validateLoginForm(username: string, password: string): LoginFormErrors {
  const errors: LoginFormErrors = {}
  if (username.trim() === '') errors.username = LOGIN_COPY.MISSING_USERNAME
  if (password === '') errors.password = LOGIN_COPY.MISSING_PASSWORD
  return errors
}

/** 行为 2.2：错误码 → 用户文案（纯函数）。40001 透传后端 message；其余非 0 码统一兜底文案 */
export function loginCopy(code: number, backendMessage: string): string {
  switch (code) {
    case ErrorCode.BAD_CREDENTIALS:
      return LOGIN_COPY.BAD_CREDENTIALS
    case ErrorCode.RATE_LIMITED:
      return LOGIN_COPY.RATE_LIMITED
    case ErrorCode.BAD_REQUEST:
      return backendMessage
    default:
      return LOGIN_COPY.UNAVAILABLE
  }
}

/** PRD §2.3 冻结签名：login(req) → LoginResponse；HttpClient 由调用方注入（router.ts 装配处） */
export function login(http: Pick<HttpClient, 'post'>, req: LoginRequest): Promise<LoginResponse | null> {
  return http.post<LoginResponse>(LOGIN_URL, req, { auth: false })
}

export interface LoginFlowDeps {
  http: Pick<HttpClient, 'post'>
  /** session 的 set（冻结接缝）：成功后落 token */
  setToken: (token: string) => void
  /** 跳转副作用（vue-router push 由装配处注入） */
  navigate: (to: string) => void
  /** 登录前的 returnUrl 查询值（已编码 fullPath）；null = 走默认 /feedback */
  returnUrl: () => string | null
}

export interface LoginFlow {
  readonly submitting: Ref<boolean>
  readonly error: Ref<string | null>
  readonly fieldErrors: Ref<LoginFormErrors>
  /** resolve true = 登录成功且已跳转；false = 本地拦截或请求失败（错误已写入 state） */
  submit(req: LoginRequest): Promise<boolean>
}

/** 登录提交流程（行为 2.1–2.4）：状态用 Vue ref，node 环境可直接断言 */
export function createLoginFlow(deps: LoginFlowDeps): LoginFlow {
  const submitting = ref(false)
  const error = ref<string | null>(null)
  const fieldErrors = ref<LoginFormErrors>({})

  const submit = async (req: LoginRequest): Promise<boolean> => {
    if (submitting.value) return false // 行为 2.4：进行中忽略重复提交
    fieldErrors.value = validateLoginForm(req.username, req.password)
    if (fieldErrors.value.username !== undefined || fieldErrors.value.password !== undefined) {
      error.value = null
      return false // 行为 2.1：本地拦截，不发请求
    }
    submitting.value = true
    error.value = null
    try {
      const data = await login(deps.http, req)
      if (data === null) {
        error.value = LOGIN_COPY.UNAVAILABLE // 契约外防御：code=0 但 data 缺失
        return false
      }
      deps.setToken(data.token)
      deps.navigate(deps.returnUrl() ?? DEFAULT_LOGIN_REDIRECT)
      return true
    } catch (e) {
      error.value = e instanceof ApiError ? loginCopy(e.code, e.message) : LOGIN_COPY.UNAVAILABLE
      return false
    } finally {
      submitting.value = false
    }
  }

  return { submitting, error, fieldErrors, submit }
}
