// stores/useFeedbackList.ts —— 列表状态 + 读/写路径动作（issue #15 读路径 + issue #16 写路径）
// api 经构造参数注入（fake 单测，PRD §5）；URL 同步由视图层接线，本模块不感知路由
import { reactive } from 'vue'
import { ApiError } from '../api/http'
import { ErrorCode } from '../api/types'
import type { FeedbackListItem, FeedbackListResponse, FeedbackStatus } from '../api/types'
import { DEFAULT_QUERY, PAGE_SIZE_OPTIONS, hasActiveFilters } from '../url/feedbackQuery'
import type { FeedbackQueryState } from '../url/feedbackQuery'

// D2：筛选即时生效（状态立即改）+ 300ms 防抖合并网络请求；测试用 fake timers 控制
export const DEBOUNCE_MS = 300

export const FALLBACK_ERROR_MESSAGE = '服务暂不可用，请稍后重试'

// 最小结构接口：与 api/feedback.ts 冻结签名一致（list 为 #15 交付，update/delete 为 #16 追加）
export interface FeedbackListApi {
  listFeedback(query: FeedbackQueryState): Promise<FeedbackListResponse>
  updateFeedbackStatus(id: number, status: FeedbackStatus): Promise<void>
  deleteFeedback(id: number): Promise<void>
}

export interface FeedbackListState {
  query: FeedbackQueryState
  list: FeedbackListItem[]
  total: number
  loading: boolean
  loaded: boolean // 是否已有一次成功响应：首屏骨架与表格 loading 的分界（§2.5 不闪白）
  error: string | null
}

// 筛选变更补丁；清空某项时显式传 undefined
export type FilterPatch = Partial<
  Pick<FeedbackQueryState, 'appName' | 'appVersion' | 'categories' | 'status' | 'startDate' | 'endDate' | 'keyword'>
>

export const EMPTY_STATE_COPY = { noData: '暂无反馈', noMatch: '没有符合条件的反馈' } as const

// 行为 3.5 空态判定（纯函数）：total===0 才出空态文案；
// page 超界（list 空、total>0，§2.5）不属此判定，由视图回退表格默认空文案
export function emptyStateText(query: FeedbackQueryState, total: number): string | null {
  if (total !== 0) return null
  return hasActiveFilters(query) ? EMPTY_STATE_COPY.noMatch : EMPTY_STATE_COPY.noData
}

function cloneQuery(query: FeedbackQueryState): FeedbackQueryState {
  return {
    ...query,
    categories: query.categories !== undefined ? [...query.categories] : undefined,
  }
}

// —— 写路径（issue #16）——

// 40401 专案文案（§2.6）：目标反馈已被删除 → 提示 + 刷新列表
export const FEEDBACK_GONE_MESSAGE = '这条反馈已被删除'
// 42900/50000/网络等其余写失败文案（§2.6）：本地状态不变，仅提示
export const WRITE_FAILED_MESSAGE = '操作失败，请稍后重试'

// 行为 4.2（纯函数）：删除后下一页页码 —— 当前页删空且 page>1 → 回上一页；否则留在当前页
export function nextPageAfterDelete(page: number, remainingCount: number): number {
  return remainingCount === 0 && page > 1 ? page - 1 : page
}

// 写动作结果（UI 层据此提示，store 不感知 Element Plus）：
// - ok：写成功，列表已按 D11 / 行为 4.2 重拉
// - cancelled：用户取消确认，未发请求（行为 4.3）
// - unauthorized：40100/40101，共享 HttpClient 已触发全局 onUnauthorized 跳登录（行为 4.6），无需再提示
// - notFound：40401，列表已刷新，message 即提示文案（行为 4.4）
// - failed：其余失败，本地状态不变（§2.6）
export type WriteOutcome =
  | { ok: true }
  | { ok: false; kind: 'cancelled' }
  | { ok: false; kind: 'unauthorized' }
  | { ok: false; kind: 'notFound'; message: string }
  | { ok: false; kind: 'failed'; message: string }

// 「当前实例」登记（issue #16）：FeedbackListView 创建 store，FeedbackTable 的行内写动作需要
// 同一实例，而视图接线不在本 issue 触碰范围 → 表格经 activeFeedbackListStore() 取当前路由页内
// 最新创建的实例（父组件 setup 先于子组件执行，读到的一定是所属视图的 store）。
// dispose 时自清避免悬挂引用；集成期若改为视图显式传 store prop，本机制可整体移除。
let activeInstance: FeedbackListStore | null = null

export function activeFeedbackListStore(): FeedbackListStore | null {
  return activeInstance
}

export function useFeedbackList(api: FeedbackListApi) {
  const state = reactive<FeedbackListState>({
    query: cloneQuery(DEFAULT_QUERY),
    list: [],
    total: 0,
    loading: false,
    loaded: false,
    error: null,
  })

  let debounceTimer: ReturnType<typeof setTimeout> | null = null
  let requestSeq = 0

  function cancelDebounce(): void {
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer)
      debounceTimer = null
    }
  }

  async function fetchNow(): Promise<void> {
    cancelDebounce()
    const seq = ++requestSeq
    state.loading = true
    state.error = null
    try {
      const response = await api.listFeedback(cloneQuery(state.query))
      if (seq !== requestSeq) return // 迟到的旧响应不覆盖新请求（防抖/翻页竞态保护）
      state.list = response.list
      state.total = response.total
      state.loaded = true
    } catch (e) {
      if (seq !== requestSeq) return
      // http 层已把网络/协议失败归一为 ApiError；此处仅兜底非预期异常
      state.error = e instanceof ApiError ? e.message : FALLBACK_ERROR_MESSAGE
    } finally {
      if (seq === requestSeq) state.loading = false
    }
  }

  function scheduleFetch(): void {
    cancelDebounce()
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      void fetchNow()
    }, DEBOUNCE_MS)
  }

  // 写失败分流（§2.6 / 行为 4.4 / 4.6）：40100/40101 已由共享 http 层统一 onUnauthorized，
  // 此处只归类不动作；40401 → 刷新列表（该行已消失）；其余 → 本地状态不变
  function classifyWriteError(error: unknown): WriteOutcome {
    if (error instanceof ApiError) {
      if (error.code === ErrorCode.UNAUTHORIZED || error.code === ErrorCode.TOKEN_EXPIRED) {
        return { ok: false, kind: 'unauthorized' }
      }
      if (error.code === ErrorCode.NOT_FOUND) {
        void fetchNow()
        return { ok: false, kind: 'notFound', message: FEEDBACK_GONE_MESSAGE }
      }
    }
    return { ok: false, kind: 'failed', message: WRITE_FAILED_MESSAGE }
  }

  const store = {
    state,
    hasActiveFilters(): boolean {
      return hasActiveFilters(state.query)
    },
    emptyStateText(): string | null {
      return emptyStateText(state.query, state.total)
    },
    // URL → 状态（首进 / 浏览器后退前进）：立即请求，不防抖
    applyQuery(query: FeedbackQueryState): void {
      state.query = cloneQuery(query)
      void fetchNow()
    },
    // 筛选变更（D2/D3）：状态立即生效 + 回第 1 页，网络请求防抖合并
    setFilter(patch: FilterPatch): void {
      state.query = { ...cloneQuery(state.query), ...patch, page: 1 }
      scheduleFetch()
    },
    // 翻页：立即请求
    setPage(page: number): void {
      if (!Number.isSafeInteger(page) || page < 1 || page === state.query.page) return
      state.query = { ...cloneQuery(state.query), page }
      void fetchNow()
    },
    // 每页条数：仅接受 PAGE_SIZE_OPTIONS 内的值，生效时回第 1 页（与 D3 同语义）
    setPageSize(size: number): void {
      const pageSize = PAGE_SIZE_OPTIONS.find((option) => option === size)
      if (pageSize === undefined || pageSize === state.query.pageSize) return
      state.query = { ...cloneQuery(state.query), pageSize, page: 1 }
      void fetchNow()
    },
    // 清空筛选：保留 pageSize（视图偏好非筛选），回第 1 页，与筛选变更同样走防抖
    clearFilters(): void {
      state.query = { page: 1, pageSize: state.query.pageSize }
      scheduleFetch()
    },
    // 错误态重试：用当前 query 立即再拉（行为 3.6）
    retry(): void {
      void fetchNow()
    },
    // 行为 4.1：状态切换双向 0↔1 调 PUT；成功后重拉当前页（D11：写接口 data:null，拿不到新 updatedAt）
    async toggleStatus(item: FeedbackListItem): Promise<WriteOutcome> {
      const target: FeedbackStatus = item.status === 0 ? 1 : 0
      try {
        await api.updateFeedbackStatus(item.id, target)
      } catch (error) {
        return classifyWriteError(error)
      }
      await fetchNow()
      return { ok: true }
    },
    // 行为 4.3：确认弹窗经入参注入（UI 传 ElMessageBox 封装、测试传 fake，才可断言「未确认不发 DELETE」）；
    // 成功后按行为 4.2 规则重拉（当前页删空且 page>1 → 回上一页）
    async remove(item: FeedbackListItem, confirm: () => Promise<boolean>): Promise<WriteOutcome> {
      const confirmed = await confirm().catch(() => false)
      if (!confirmed) return { ok: false, kind: 'cancelled' }
      try {
        await api.deleteFeedback(item.id)
      } catch (error) {
        return classifyWriteError(error)
      }
      const remaining = state.list.filter((row) => row.id !== item.id).length
      const nextPage = nextPageAfterDelete(state.query.page, remaining)
      if (nextPage !== state.query.page) {
        state.query = { ...cloneQuery(state.query), page: nextPage }
      }
      await fetchNow()
      return { ok: true }
    },
    dispose(): void {
      cancelDebounce()
      requestSeq += 1 // 在途请求作废，防止卸载后写状态
      // 以 state 身份比对代替 store 自引用（避免 ReturnType 别名循环推导）；state 与 store 一一对应
      if (activeInstance !== null && activeInstance.state === state) activeInstance = null
    },
  }

  activeInstance = store
  return store
}

export type FeedbackListStore = ReturnType<typeof useFeedbackList>
