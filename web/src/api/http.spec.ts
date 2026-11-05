import { describe, expect, it } from 'vitest'
import { ApiError, createHttpClient } from './http'
import type { LoginResponse } from './types'

// 行为 1.2–1.5（PRD feedback-web-admin §4 任务 1）：注入 fake fetch，不依赖任何网络
type FetchCall = { url: string; init: RequestInit | undefined }

const FALLBACK_MESSAGE = '服务暂不可用，请稍后重试'

function jsonResponse(envelope: unknown, status = 200): Response {
  return new Response(JSON.stringify(envelope), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

interface HarnessOptions {
  token?: string | null
  respond?: (url: string, init: RequestInit | undefined) => Response | Promise<Response>
}

function makeHarness(options: HarnessOptions = {}) {
  const calls: FetchCall[] = []
  const respond =
    options.respond ?? (() => jsonResponse({ code: 0, message: 'success', data: null }))
  const fetchFn: typeof fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init })
    return respond(url, init)
  }
  let unauthorizedCount = 0
  const client = createHttpClient(fetchFn, {
    getToken: () => options.token ?? null,
    onUnauthorized: () => {
      unauthorizedCount += 1
    },
  })
  return { client, calls, unauthorizedCount: () => unauthorizedCount }
}

function headersOf(call: FetchCall): Record<string, string> {
  return (call.init?.headers ?? {}) as Record<string, string>
}

async function expectApiError(promise: Promise<unknown>, code: number, message?: string): Promise<void> {
  const err = await promise.then(
    () => {
      throw new Error('expected the request to reject')
    },
    (e: unknown) => e,
  )
  expect(err).toBeInstanceOf(ApiError)
  expect((err as ApiError).code).toBe(code)
  if (message !== undefined) expect((err as ApiError).message).toBe(message)
}

const ERROR_MESSAGES: Record<number, string> = {
  40001: 'title: 必填',
  40100: '未认证',
  40101: 'token 已过期',
  40102: '用户名或密码错误',
  40401: '反馈不存在',
  42900: '尝试过于频繁',
  50000: '服务内部错误',
}

describe('HttpClientTest', () => {
  describe('envelopeUnwrap', () => {
    it('code===0 returns data', async () => {
      const h = makeHarness({
        respond: () =>
          jsonResponse({ code: 0, message: 'success', data: { token: 'jwt-token', expiresIn: 604800 } }),
      })
      await expect(
        h.client.get<LoginResponse>('/api/feedback/list', { auth: false }),
      ).resolves.toEqual({ token: 'jwt-token', expiresIn: 604800 })
    })

    it('code===0 with data:null resolves null（保留 T|null 语义）', async () => {
      const h = makeHarness()
      await expect(
        h.client.post('/api/login', { username: 'admin', password: 'pw' }, { auth: false }),
      ).resolves.toBeNull()
    })

    it('非 2xx 也按 body 的 code 分流，不看 HTTP status（PRD §2.1）', async () => {
      const h = makeHarness({
        respond: () => jsonResponse({ code: 0, message: 'success', data: { total: 1 } }, 500),
      })
      await expect(h.client.get<{ total: number }>('/api/feedback/list', { auth: false })).resolves.toEqual({
        total: 1,
      })
    })

    it.each(Object.keys(ERROR_MESSAGES).map(Number))(
      'code=%i throws ApiError(code, message)',
      async (code) => {
        const h = makeHarness({
          respond: () => jsonResponse({ code, message: ERROR_MESSAGES[code], data: null }),
        })
        await expectApiError(h.client.get('/api/feedback/list', { auth: false }), code, ERROR_MESSAGES[code])
        expect(h.unauthorizedCount()).toBe(code === 40100 || code === 40101 ? 1 : 0)
      },
    )
  })

  describe('networkAndMalformedFallback', () => {
    it('network failure → ApiError(50000, fallback)', async () => {
      const h = makeHarness({
        respond: () => {
          throw new TypeError('Failed to fetch')
        },
      })
      await expectApiError(h.client.get('/api/feedback/list', { auth: false }), 50000, FALLBACK_MESSAGE)
    })

    it('non-JSON body → ApiError(50000, fallback)', async () => {
      const h = makeHarness({ respond: () => new Response('<html>Bad Gateway</html>', { status: 502 }) })
      await expectApiError(h.client.get('/api/feedback/list', { auth: false }), 50000, FALLBACK_MESSAGE)
    })

    it('missing code key → ApiError(50000, fallback)', async () => {
      const h = makeHarness({ respond: () => jsonResponse({ message: 'no code here' }) })
      await expectApiError(h.client.get('/api/feedback/list', { auth: false }), 50000, FALLBACK_MESSAGE)
    })

    it('JSON null body → ApiError(50000, fallback)', async () => {
      const h = makeHarness({ respond: () => jsonResponse(null) })
      await expectApiError(h.client.get('/api/feedback/list', { auth: false }), 50000, FALLBACK_MESSAGE)
    })
  })

  describe('authHeaderInjection', () => {
    it('authed request injects Authorization: Bearer <token>，且只走相对路径 /api', async () => {
      const h = makeHarness({
        token: 'tok-123',
        respond: () =>
          jsonResponse({ code: 0, message: 'success', data: { total: 0, page: 1, pageSize: 20, list: [] } }),
      })
      await expect(h.client.get('/api/feedback/list')).resolves.toEqual({
        total: 0,
        page: 1,
        pageSize: 20,
        list: [],
      })
      expect(h.calls).toHaveLength(1)
      expect(h.calls[0].url).toBe('/api/feedback/list')
      expect(h.calls[0].init?.method).toBe('GET')
      expect(headersOf(h.calls[0]).Authorization).toBe('Bearer tok-123')
    })

    it('public request (auth:false) carries no Authorization header', async () => {
      const h = makeHarness()
      await expect(
        h.client.post('/api/login', { username: 'admin', password: 'pw' }, { auth: false }),
      ).resolves.toBeNull()
      expect(headersOf(h.calls[0]).Authorization).toBeUndefined()
    })

    it('post/put serialize body as JSON with content-type', async () => {
      const h = makeHarness({ token: 'tok' })
      await expect(
        h.client.post('/api/login', { username: 'admin', password: 'pw' }, { auth: false }),
      ).resolves.toBeNull()
      await expect(h.client.put('/api/feedback/12/status', { status: 1 })).resolves.toBeNull()
      expect(h.calls[0].init?.body).toBe(JSON.stringify({ username: 'admin', password: 'pw' }))
      expect(h.calls[1].init?.body).toBe(JSON.stringify({ status: 1 }))
      expect(headersOf(h.calls[0])['Content-Type']).toBe('application/json')
      expect(headersOf(h.calls[1]).Authorization).toBe('Bearer tok')
    })

    it('delete maps to DELETE verb without body', async () => {
      const h = makeHarness({ token: 'tok' })
      await expect(h.client.delete('/api/feedback/12')).resolves.toBeNull()
      expect(h.calls[0].init?.method).toBe('DELETE')
      expect(h.calls[0].init?.body).toBeUndefined()
    })
  })

  describe('missingTokenShortCircuit', () => {
    it('getToken() 为 null → 不发请求、onUnauthorized 一次、抛 ApiError(40100)', async () => {
      const h = makeHarness({ token: null })
      await expectApiError(h.client.get('/api/feedback/list'), 40100)
      expect(h.calls).toHaveLength(0)
      expect(h.unauthorizedCount()).toBe(1)
    })
  })

  describe('unauthorizedRouting', () => {
    it.each([40100, 40101])('code=%i → onUnauthorized 恰好一次且向调用方抛 ApiError', async (code) => {
      const h = makeHarness({
        token: 'tok',
        respond: () => jsonResponse({ code, message: '请重新登录', data: null }, 401),
      })
      await expectApiError(h.client.get('/api/feedback/list'), code, '请重新登录')
      expect(h.unauthorizedCount()).toBe(1)
    })
  })
})
