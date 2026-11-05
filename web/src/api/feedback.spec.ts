import { describe, expect, it } from 'vitest'
import { createFeedbackApi } from './feedback'
import { ApiError } from './http'
import type { HttpClient, RequestOptions } from './http'
import type { FeedbackListResponse } from './types'
import type { FeedbackQueryState } from '../url/feedbackQuery'

// 行为 3.4 的 URL 侧断言：九参数命名与后端一致（§2.1）；envelope 解包已在 http.spec（issue #13）覆盖
function makeHttp(getResult: FeedbackListResponse | null): { http: HttpClient; captured: Array<{ url: string; options?: unknown }> } {
  const captured: Array<{ url: string; options?: unknown }> = []
  const neverCalled = async (): Promise<null> => {
    throw new Error('list 路径不得调用 post/put/delete')
  }
  const http: HttpClient = {
    get: async <T>(_url: string, options?: RequestOptions): Promise<T | null> => {
      captured.push({ url: _url, options })
      return getResult as T | null
    },
    post: neverCalled,
    put: neverCalled,
    delete: neverCalled,
  }
  return { http, captured }
}

describe('FeedbackApiTest', () => {
  it('listFeedback 组装九参数 query string（categories 逗号拼接，参数名与后端一致）', async () => {
    const { http, captured } = makeHttp({ total: 0, page: 3, pageSize: 50, list: [] })
    const query: FeedbackQueryState = {
      page: 3,
      pageSize: 50,
      appName: 'Launcher+',
      appVersion: '1.2.0',
      categories: ['BUG', 'SUGGESTION'],
      status: 1,
      startDate: '2026-01-01',
      endDate: '2026-01-31',
      keyword: '崩溃',
    }
    await expect(createFeedbackApi(http).listFeedback(query)).resolves.toEqual({
      total: 0,
      page: 3,
      pageSize: 50,
      list: [],
    })
    expect(captured[0].url).toBe(
      '/api/feedback/list?page=3&pageSize=50&appName=Launcher%2B&appVersion=1.2.0' +
        '&category=BUG%2CSUGGESTION&status=1&startDate=2026-01-01&endDate=2026-01-31' +
        '&keyword=' +
        encodeURIComponent('崩溃'),
    )
    expect(captured[0].options).toBeUndefined() // options 缺省 → http 层默认鉴权（list 需 Bearer）
  })

  it('默认状态不带 query string', async () => {
    const { http, captured } = makeHttp({ total: 0, page: 1, pageSize: 20, list: [] })
    await createFeedbackApi(http).listFeedback({ page: 1, pageSize: 20 })
    expect(captured[0].url).toBe('/api/feedback/list')
  })

  it('status=0 正常出现在 query string', async () => {
    const { http, captured } = makeHttp({ total: 0, page: 1, pageSize: 20, list: [] })
    await createFeedbackApi(http).listFeedback({ page: 1, pageSize: 20, status: 0 })
    expect(captured[0].url).toBe('/api/feedback/list?status=0')
  })

  it('code===0 但 data=null → ApiError(50000) 兜底', async () => {
    const { http } = makeHttp(null)
    const error = await createFeedbackApi(http)
      .listFeedback({ page: 1, pageSize: 20 })
      .then(
        () => {
          throw new Error('expected the request to reject')
        },
        (e: unknown) => e,
      )
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(50000)
  })

  // —— issue #16 写路径 ——

  function makeWriteHttp(): {
    http: HttpClient
    captured: Array<{ method: 'PUT' | 'DELETE'; url: string; body?: unknown; options?: unknown }>
  } {
    const captured: Array<{ method: 'PUT' | 'DELETE'; url: string; body?: unknown; options?: unknown }> = []
    const http: HttpClient = {
      get: async (): Promise<null> => {
        throw new Error('写路径测试不得调用 get')
      },
      post: async (): Promise<null> => {
        throw new Error('写路径测试不得调用 post')
      },
      put: async (url: string, body?: unknown, options?: unknown): Promise<null> => {
        captured.push({ method: 'PUT', url, body, options })
        return null
      },
      delete: async (url: string, options?: unknown): Promise<null> => {
        captured.push({ method: 'DELETE', url, options })
        return null
      },
    }
    return { http, captured }
  }

  it('updateFeedbackStatus → PUT /api/feedback/{id}/status，body { status }（docs/api.http §4）', async () => {
    const { http, captured } = makeWriteHttp()
    await createFeedbackApi(http).updateFeedbackStatus(12, 1)
    expect(captured).toEqual([{ method: 'PUT', url: '/api/feedback/12/status', body: { status: 1 }, options: undefined }])
  })

  it('deleteFeedback → DELETE /api/feedback/{id}；成功 data=null 吞掉返回值', async () => {
    const { http, captured } = makeWriteHttp()
    const result = await createFeedbackApi(http).deleteFeedback(12)
    expect(result).toBeUndefined() // 冻结签名 Promise<void>：data=null 不外泄
    expect(captured).toEqual([{ method: 'DELETE', url: '/api/feedback/12', options: undefined }])
  })
})
