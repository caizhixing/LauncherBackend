// api/http.ts —— 唯一发请求的模块；fetch 可注入（构造/工厂参数），供单测。
// 只走相对路径 /api（同源部署，DECISIONS.md #Web 管理端技术栈选型）
import { ErrorCode } from './types'
import type { ApiResponse } from './types'

export class ApiError extends Error {
  constructor(readonly code: number, message: string) { super(message) }
}

/** 鉴权开关：缺省 true（管理端除 login 外全部鉴权）；公开接口显式传 auth: false */
export interface RequestOptions {
  auth?: boolean
}

export interface HttpClient {
  /** get/post/put/delete，均解 envelope（行为见 createHttpClient 注释） */
  get<T>(url: string, options?: RequestOptions): Promise<T | null>
  post<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T | null>
  put<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T | null>
  delete<T>(url: string, options?: RequestOptions): Promise<T | null>
}

const FALLBACK_MESSAGE = '服务暂不可用，请稍后重试'

// 行为：code===0 → 返回 data（保留 T|null 语义）；code!==0 → 抛 ApiError(code, message)
//      网络失败 / body 非 JSON / 缺 envelope → 抛 ApiError(50000, '服务暂不可用，请稍后重试')
//      需鉴权的请求自动注入 `Authorization: Bearer <token>`；无 token 直接走 onUnauthorized，不发请求
//      （无 token 分支在 onUnauthorized 后抛 ApiError(40100)，调用方一律按失败收场）
export function createHttpClient(
  fetchFn: typeof fetch,
  opts: {
    getToken: () => string | null
    onUnauthorized: () => void // 收到 40100/40101 时先清 token 再跳登录（由 guard 装配处注册）
  },
): HttpClient {
  const request = async <T>(
    method: string,
    url: string,
    body: unknown,
    auth: boolean,
  ): Promise<T | null> => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (auth) {
      const token = opts.getToken()
      if (token === null) {
        opts.onUnauthorized()
        throw new ApiError(ErrorCode.UNAUTHORIZED, '未登录或登录已过期')
      }
      headers.Authorization = `Bearer ${token}`
    }
    let response: Response
    try {
      response = await fetchFn(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw new ApiError(ErrorCode.INTERNAL, FALLBACK_MESSAGE)
    }
    // 后端契约：非 2xx 也带响应体，一律按 body 的 code 分流，不按 HTTP status
    let parsed: unknown
    try {
      parsed = await response.json()
    } catch {
      throw new ApiError(ErrorCode.INTERNAL, FALLBACK_MESSAGE)
    }
    if (
      typeof parsed !== 'object' ||
      parsed === null ||
      typeof (parsed as { code?: unknown }).code !== 'number'
    ) {
      throw new ApiError(ErrorCode.INTERNAL, FALLBACK_MESSAGE)
    }
    const envelope = parsed as ApiResponse<unknown>
    if (envelope.code === ErrorCode.OK) {
      return envelope.data as T | null
    }
    if (envelope.code === ErrorCode.UNAUTHORIZED || envelope.code === ErrorCode.TOKEN_EXPIRED) {
      opts.onUnauthorized()
    }
    throw new ApiError(envelope.code, envelope.message)
  }

  return {
    get: (url, options) => request('GET', url, undefined, options?.auth ?? true),
    post: (url, body, options) => request('POST', url, body, options?.auth ?? true),
    put: (url, body, options) => request('PUT', url, body, options?.auth ?? true),
    delete: (url, options) => request('DELETE', url, undefined, options?.auth ?? true),
  }
}
