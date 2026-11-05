// stores/useFeedbackList.ts —— 列表状态 + 读路径动作（issue #15 / PRD §4 任务 3）
// api 经构造参数注入（fake 单测，PRD §5）；URL 同步由视图层接线，本模块不感知路由
import { reactive } from 'vue'
import { ApiError } from '../api/http'
import type { FeedbackListItem, FeedbackListResponse } from '../api/types'
import { DEFAULT_QUERY, PAGE_SIZE_OPTIONS, hasActiveFilters } from '../url/feedbackQuery'
import type { FeedbackQueryState } from '../url/feedbackQuery'

// D2：筛选即时生效（状态立即改）+ 300ms 防抖合并网络请求；测试用 fake timers 控制
export const DEBOUNCE_MS = 300

export const FALLBACK_ERROR_MESSAGE = '服务暂不可用，请稍后重试'

// 最小结构接口：issue #4 将在 api/feedback.ts 追加写方法，store 只依赖读路径，不受其影响
export interface FeedbackListApi {
  listFeedback(query: FeedbackQueryState): Promise<FeedbackListResponse>
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

  return {
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
    dispose(): void {
      cancelDebounce()
      requestSeq += 1 // 在途请求作废，防止卸载后写状态
    },
  }
}

export type FeedbackListStore = ReturnType<typeof useFeedbackList>
