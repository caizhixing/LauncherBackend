import { describe, expect, it } from 'vitest'
import { DEFAULT_QUERY } from '../url/feedbackQuery'
import type { FeedbackQueryState } from '../url/feedbackQuery'
import { EMPTY_STATE_COPY, emptyStateText } from './useFeedbackList'

// 行为 3.5（PRD feedback-web-admin §4 任务 3）：空态判定纯函数
const FILTER_SINGLES: Array<[string, Partial<FeedbackQueryState>]> = [
  ['appName', { appName: 'Launcher+' }],
  ['appVersion', { appVersion: '1.2.0' }],
  ['categories', { categories: ['BUG'] }],
  ['status=0', { status: 0 }],
  ['status=1', { status: 1 }],
  ['startDate', { startDate: '2026-01-01' }],
  ['endDate', { endDate: '2026-01-31' }],
  ['keyword', { keyword: '闪退' }],
]

describe('EmptyStateTest', () => {
  it('无筛选且 total=0 → 「暂无反馈」', () => {
    expect(EMPTY_STATE_COPY.noData).toBe('暂无反馈')
    expect(emptyStateText(DEFAULT_QUERY, 0)).toBe('暂无反馈')
  })

  it.each(FILTER_SINGLES)('有筛选（%s）且 total=0 → 「没有符合条件的反馈」', (_field, patch) => {
    const query: FeedbackQueryState = { ...DEFAULT_QUERY, ...patch }
    expect(EMPTY_STATE_COPY.noMatch).toBe('没有符合条件的反馈')
    expect(emptyStateText(query, 0)).toBe('没有符合条件的反馈')
  })

  it('total>0 不属空态（page 超界由视图回退表格默认空文案，§2.5）', () => {
    expect(emptyStateText(DEFAULT_QUERY, 5)).toBeNull()
    expect(emptyStateText({ ...DEFAULT_QUERY, keyword: 'x' }, 5)).toBeNull()
  })
})
