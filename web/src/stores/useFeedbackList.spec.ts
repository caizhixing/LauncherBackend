import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/http'
import type { FeedbackListResponse, FeedbackListItem, FeedbackStatus } from '../api/types'
import { DEFAULT_QUERY } from '../url/feedbackQuery'
import type { FeedbackQueryState } from '../url/feedbackQuery'
import { DEBOUNCE_MS, useFeedbackList, activeFeedbackListStore } from './useFeedbackList'
import type { FeedbackListApi } from './useFeedbackList'

// 行为 3.3 / 3.4 / 3.6（PRD feedback-web-admin §4 任务 3）+ 行为 4.1-4.4（任务 4）：fake api 注入 + fake timers
const ITEM: FeedbackListItem = {
  id: 12,
  title: '启动闪退',
  content: '打开就闪退',
  category: 'BUG',
  appName: 'Launcher+',
  appVersion: '1.2.0',
  deviceId: 'dev-1',
  status: 0,
  images: [],
  createdAt: '2026-11-01 09:30:00',
  updatedAt: '2026-11-01 10:00:00',
}

function pageData(total = 1): FeedbackListResponse {
  return { total, page: 1, pageSize: 20, list: [ITEM] }
}

type Respond = (query: FeedbackQueryState, callIndex: number) => Promise<FeedbackListResponse>

function makeFakeApi(respond: Respond = async () => pageData()): {
  api: FeedbackListApi
  calls: FeedbackQueryState[]
  order: string[] // 'list' | 'update:<id>:<status>' | 'delete:<id>'：行为 4.1 断言调用序
  setRespond: (fn: Respond) => void
  failUpdateWith: (error: unknown) => void
  failDeleteWith: (error: unknown) => void
} {
  const calls: FeedbackQueryState[] = []
  const order: string[] = []
  let impl = respond
  let updateError: unknown = null
  let deleteError: unknown = null
  const api: FeedbackListApi = {
    listFeedback(query) {
      // JSON 克隆：记录发起请求那一刻的快照，隔离后续状态变更
      calls.push(JSON.parse(JSON.stringify(query)) as FeedbackQueryState)
      order.push('list')
      return impl(query, calls.length)
    },
    async updateFeedbackStatus(id: number, status: FeedbackStatus): Promise<void> {
      order.push(`update:${id}:${status}`)
      if (updateError !== null) throw updateError
    },
    async deleteFeedback(id: number): Promise<void> {
      order.push(`delete:${id}`)
      if (deleteError !== null) throw deleteError
    },
  }
  return {
    api,
    calls,
    order,
    setRespond: (fn) => (impl = fn),
    failUpdateWith: (error) => (updateError = error),
    failDeleteWith: (error) => (deleteError = error),
  }
}

describe('UseFeedbackListTest', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('applyQuery 立即请求且参数即 query（不走防抖）', async () => {
    const { api, calls } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery({ page: 2, pageSize: 50, keyword: '闪退' })
    expect(calls).toHaveLength(1)
    expect(calls[0]).toEqual({ page: 2, pageSize: 50, keyword: '闪退' })
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    expect(calls).toHaveLength(1)
  })

  it('debounceAndResetPage：筛选变更重置 page=1；300ms 内多次变更只发一次请求', async () => {
    const { api, calls } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery({ page: 7, pageSize: 20 })
    store.setFilter({ keyword: '崩' })
    expect(store.state.query.page).toBe(1) // D3：改筛选回第 1 页
    expect(store.state.query.keyword).toBe('崩') // D2：状态即时生效
    expect(calls).toHaveLength(1) // 防抖窗口内未发新请求
    await vi.advanceTimersByTimeAsync(150)
    store.setFilter({ keyword: '崩溃', status: 0 }) // 窗口内再次变更 → 重置防抖
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    expect(calls).toHaveLength(2) // 两次筛选变更合并为一次请求
    expect(calls[1]).toEqual({ ...DEFAULT_QUERY, keyword: '崩溃', status: 0 })
  })

  it('requestParamsMatchState：请求参数与 FeedbackQueryState 一一对应', async () => {
    const { api, calls } = makeFakeApi()
    const store = useFeedbackList(api)
    store.setFilter({
      appName: 'Launcher+',
      appVersion: '1.2.0',
      categories: ['BUG', 'SUGGESTION'],
      status: 1,
      startDate: '2026-01-01',
      endDate: '2026-01-31',
      keyword: '崩溃 报告',
    })
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    expect(calls).toHaveLength(1)
    expect(calls[0]).toEqual({
      page: 1,
      pageSize: 20,
      appName: 'Launcher+',
      appVersion: '1.2.0',
      categories: ['BUG', 'SUGGESTION'],
      status: 1,
      startDate: '2026-01-01',
      endDate: '2026-01-31',
      keyword: '崩溃 报告',
    })
  })

  it('retryKeepsFilters：50000 → 错误态；重试用当前筛选且成功后恢复', async () => {
    const { api, calls, setRespond } = makeFakeApi()
    setRespond(async () => {
      throw new ApiError(50000, '服务暂不可用，请稍后重试')
    })
    const store = useFeedbackList(api)
    store.applyQuery({ page: 2, pageSize: 20, categories: ['BUG'] })
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.error).toBe('服务暂不可用，请稍后重试')
    expect(store.state.list).toEqual([])
    expect(store.state.loaded).toBe(false)

    setRespond(async () => pageData(1))
    store.retry()
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual({ page: 2, pageSize: 20, categories: ['BUG'] }) // 重试用当前筛选
    expect(store.state.error).toBeNull()
    expect(store.state.list).toEqual([ITEM])
    expect(store.state.total).toBe(1)
    expect(store.state.loaded).toBe(true)
  })

  it('首屏 loading 语义：loaded 前骨架态，成功后 loading=false / loaded=true', async () => {
    let resolveFetch!: (response: FeedbackListResponse) => void
    const { api } = makeFakeApi(() => new Promise((resolve) => (resolveFetch = resolve)))
    const store = useFeedbackList(api)
    store.applyQuery(DEFAULT_QUERY)
    expect(store.state.loading).toBe(true)
    expect(store.state.loaded).toBe(false)
    resolveFetch(pageData(3))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.loading).toBe(false)
    expect(store.state.loaded).toBe(true)
    expect(store.state.total).toBe(3)
    expect(store.state.list).toEqual([ITEM])
  })

  it('迟到的旧响应不覆盖新请求（成功与失败两个分支）', async () => {
    const pending: Array<{ resolve: (r: FeedbackListResponse) => void; reject: (e: unknown) => void }> = []
    const { api } = makeFakeApi(
      () =>
        new Promise<FeedbackListResponse>((resolve, reject) => {
          pending.push({ resolve, reject })
        }),
    )
    const store = useFeedbackList(api)
    store.setPage(2) // 请求 A，暂不 resolve
    store.setPage(3) // 请求 B（seq 更新）
    pending[1].resolve({ total: 0, page: 3, pageSize: 20, list: [] }) // B 先回
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.total).toBe(0)
    pending[0].resolve({ total: 99, page: 2, pageSize: 20, list: [ITEM] }) // A 迟到成功
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.total).toBe(0) // 未被 A 覆盖
    expect(store.state.loading).toBe(false)

    store.setPage(4) // 请求 C
    store.retry() // 请求 D（seq 更新）
    pending[2].reject(new ApiError(50000, 'stale')) // C 迟到失败
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.error).toBeNull() // 迟到的失败不置错误态
    pending[3].resolve(pageData(7))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.total).toBe(7)
    expect(store.state.error).toBeNull()
  })

  it('setPage：相同/非法页码不请求；翻页立即请求', async () => {
    const { api, calls } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery(DEFAULT_QUERY)
    store.setPage(1) // 相同页码
    store.setPage(0) // 非法
    store.setPage(Number.NaN)
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    expect(calls).toHaveLength(1)
    store.setPage(3)
    expect(store.state.query.page).toBe(3)
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toHaveLength(2)
    expect(calls[1].page).toBe(3)
  })

  it('setPageSize：合法选项生效并回第 1 页；非法值忽略', async () => {
    const { api, calls } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery({ page: 4, pageSize: 20 })
    store.setPageSize(999) // 不在选项内
    store.setPageSize(20) // 与当前相同
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    expect(calls).toHaveLength(1)
    store.setPageSize(100)
    expect(store.state.query.pageSize).toBe(100)
    expect(store.state.query.page).toBe(1)
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual({ page: 1, pageSize: 100 })
  })

  it('clearFilters：清空全部筛选项、保留 pageSize、回第 1 页（走防抖）', async () => {
    const { api, calls } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery({ page: 6, pageSize: 50, keyword: '闪退', status: 1, categories: ['BUG'] })
    store.clearFilters()
    expect(store.state.query).toEqual({ page: 1, pageSize: 50 })
    expect(calls).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual({ page: 1, pageSize: 50 })
  })

  it('dispose：取消防抖中的请求', async () => {
    const { api, calls } = makeFakeApi()
    const store = useFeedbackList(api)
    store.setFilter({ keyword: 'x' })
    store.dispose()
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    expect(calls).toHaveLength(0)
  })

  it('非 ApiError 异常兜底为 50000 文案', async () => {
    const { api } = makeFakeApi(async () => {
      throw new Error('boom')
    })
    const store = useFeedbackList(api)
    store.applyQuery(DEFAULT_QUERY)
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.error).toBe('服务暂不可用，请稍后重试')
  })

  it('hasActiveFilters/emptyStateText 便捷读取与 state 同步（视图绑定入口）', async () => {
    const { api, setRespond } = makeFakeApi()
    const store = useFeedbackList(api)
    expect(store.hasActiveFilters()).toBe(false)
    store.applyQuery({ page: 1, pageSize: 20, keyword: 'x' })
    await vi.advanceTimersByTimeAsync(0)
    expect(store.hasActiveFilters()).toBe(true)
    expect(store.emptyStateText()).toBeNull() // total=1 → 非空态
    setRespond(async () => ({ total: 0, page: 1, pageSize: 20, list: [] }))
    store.retry()
    await vi.advanceTimersByTimeAsync(0)
    expect(store.emptyStateText()).toBe('没有符合条件的反馈')
    store.clearFilters()
    expect(store.hasActiveFilters()).toBe(false)
  })

  // —— issue #16 写路径（行为 4.1/4.2/4.3/4.4）——

  it('toggleStatusReloadsPage：双向 0↔1 调 PUT，成功后重拉当前页（调用序）', async () => {
    const { api, calls, order } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery({ page: 2, pageSize: 20, keyword: '闪退' })
    await vi.advanceTimersByTimeAsync(0)

    const pending: FeedbackListItem = { ...ITEM, status: 0 }
    expect(await store.toggleStatus(pending)).toEqual({ ok: true })
    expect(order).toEqual(['list', 'update:12:1', 'list'])
    expect(calls[1]).toEqual({ page: 2, pageSize: 20, keyword: '闪退' }) // D11：重拉当前页

    const done: FeedbackListItem = { ...ITEM, id: 13, status: 1 }
    await store.toggleStatus(done)
    expect(order[3]).toBe('update:13:0') // 反向 1→0 亦触发（双向）
  })

  it('deleteRequiresConfirmAndReloads：未确认（含 confirm 抛出）不发 DELETE；确认后按 4.2 回退页码', async () => {
    const { api, calls, order } = makeFakeApi(async () => ({ total: 21, page: 2, pageSize: 20, list: [ITEM] }))
    const store = useFeedbackList(api)
    store.applyQuery({ page: 2, pageSize: 20 })
    await vi.advanceTimersByTimeAsync(0)

    expect(await store.remove(ITEM, async () => false)).toEqual({ ok: false, kind: 'cancelled' })
    expect(await store.remove(ITEM, () => Promise.reject(new Error('closed')))).toEqual({ ok: false, kind: 'cancelled' })
    expect(order).toEqual(['list']) // 未确认：不 DELETE、不刷新

    // 当前页只剩这一条（删空）且 page>1 → 回第 1 页再拉（行为 4.2）
    expect(await store.remove(ITEM, async () => true)).toEqual({ ok: true })
    expect(order).toEqual(['list', 'delete:12', 'list'])
    expect(store.state.query.page).toBe(1)
    expect(calls[1]).toEqual({ page: 1, pageSize: 20 })
  })

  it('deleteRequiresConfirmAndReloads：当前页未删空 → 页码不变重拉当前页', async () => {
    const other: FeedbackListItem = { ...ITEM, id: 13 }
    const { api, calls } = makeFakeApi(async () => ({ total: 21, page: 2, pageSize: 20, list: [ITEM, other] }))
    const store = useFeedbackList(api)
    store.applyQuery({ page: 2, pageSize: 20 })
    await vi.advanceTimersByTimeAsync(0)
    expect(await store.remove(other, async () => true)).toEqual({ ok: true })
    expect(store.state.query.page).toBe(2)
    expect(calls[1].page).toBe(2)
  })

  it('notFoundRefreshes：40401 → 「这条反馈已被删除」+ 刷新；其余失败本地状态不变', async () => {
    const { api, order, failUpdateWith } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery(DEFAULT_QUERY)
    await vi.advanceTimersByTimeAsync(0)
    expect(store.state.list).toEqual([ITEM])

    failUpdateWith(new ApiError(40401, '反馈不存在'))
    expect(await store.toggleStatus(ITEM)).toEqual({ ok: false, kind: 'notFound', message: '这条反馈已被删除' })
    await vi.advanceTimersByTimeAsync(0) // 40401 的刷新为异步触发
    expect(order).toEqual(['list', 'update:12:1', 'list'])

    // 42900 → 通用失败文案；不刷新、本地状态不变（§2.6）
    failUpdateWith(new ApiError(42900, '稍后再试'))
    const snapshot = JSON.stringify(store.state.list)
    expect(await store.toggleStatus(ITEM)).toEqual({ ok: false, kind: 'failed', message: '操作失败，请稍后重试' })
    expect(order).toEqual(['list', 'update:12:1', 'list', 'update:12:1'])
    expect(JSON.stringify(store.state.list)).toBe(snapshot)

    // 40100/40101 → 归类 unauthorized：全局 onUnauthorized 已由 http 层触发（行为 4.6），store 不再动作
    failUpdateWith(new ApiError(40101, '过期'))
    expect(await store.toggleStatus(ITEM)).toEqual({ ok: false, kind: 'unauthorized' })
    expect(order).toEqual(['list', 'update:12:1', 'list', 'update:12:1', 'update:12:1'])
  })

  it('notFoundRefreshes：delete 40401 同样提示 + 刷新；非 ApiError 兜底 failed', async () => {
    const { api, order, failDeleteWith } = makeFakeApi()
    const store = useFeedbackList(api)
    store.applyQuery(DEFAULT_QUERY)
    await vi.advanceTimersByTimeAsync(0)

    failDeleteWith(new ApiError(40401, '反馈不存在'))
    expect(await store.remove(ITEM, async () => true)).toEqual({
      ok: false,
      kind: 'notFound',
      message: '这条反馈已被删除',
    })
    await vi.advanceTimersByTimeAsync(0)
    expect(order).toEqual(['list', 'delete:12', 'list'])

    failDeleteWith(new Error('boom'))
    expect(await store.remove(ITEM, async () => true)).toEqual({ ok: false, kind: 'failed', message: '操作失败，请稍后重试' })
  })

  it('activeFeedbackListStore：登记最新实例，dispose 自清（表格取实例入口）', () => {
    const { api } = makeFakeApi()
    const first = useFeedbackList(api)
    expect(activeFeedbackListStore()).toBe(first)
    const second = useFeedbackList(api)
    expect(activeFeedbackListStore()).toBe(second)
    second.dispose()
    expect(activeFeedbackListStore()).toBeNull()
    first.dispose() // 非 active 实例 dispose 不影响登记
    expect(activeFeedbackListStore()).toBeNull()
  })
})
