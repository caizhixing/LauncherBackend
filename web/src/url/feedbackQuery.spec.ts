import { describe, expect, it } from 'vitest'
import { DEFAULT_QUERY, PAGE_SIZE_OPTIONS, decodeQuery, encodeQuery, hasActiveFilters } from './feedbackQuery'
import type { FeedbackQueryState } from './feedbackQuery'

// 行为 3.1 / 3.2 / 3.7（PRD feedback-web-admin §4 任务 3）：URL 编解码纯函数
const FULL_QUERY: FeedbackQueryState = {
  page: 3,
  pageSize: 50,
  appName: 'Launcher+',
  appVersion: '1.2.0',
  categories: ['BUG', 'SUGGESTION'],
  status: 1,
  startDate: '2026-01-01',
  endDate: '2026-01-31',
  keyword: '崩溃 报告',
}

describe('FeedbackQueryTest', () => {
  describe('roundTrip', () => {
    it('全字段编码：键名与后端九参数一致，categories 逗号拼接，空值省略键', () => {
      expect(encodeQuery(FULL_QUERY)).toEqual({
        page: '3',
        pageSize: '50',
        appName: 'Launcher+',
        appVersion: '1.2.0',
        category: 'BUG,SUGGESTION',
        status: '1',
        startDate: '2026-01-01',
        endDate: '2026-01-31',
        keyword: '崩溃 报告',
      })
    })

    it('全字段往返一致', () => {
      expect(decodeQuery(encodeQuery(FULL_QUERY))).toEqual(FULL_QUERY)
    })

    it('默认状态：编码为空对象（全部键省略），解码回默认', () => {
      expect(encodeQuery(DEFAULT_QUERY)).toEqual({})
      expect(decodeQuery({})).toEqual(DEFAULT_QUERY)
    })

    it('与默认值相同的 page/pageSize 省略，非默认保留；往返一致', () => {
      const query: FeedbackQueryState = { page: 1, pageSize: 10, keyword: '闪退' }
      const encoded = encodeQuery(query)
      expect(encoded).toEqual({ pageSize: '10', keyword: '闪退' })
      expect(decodeQuery(encoded)).toEqual(query)
    })

    it('status=0 是有效筛选，编码不省略', () => {
      const query: FeedbackQueryState = { page: 1, pageSize: 20, status: 0 }
      expect(encodeQuery(query)).toEqual({ status: '0' })
      expect(decodeQuery({ status: '0' })).toEqual(query)
    })

    it('单项筛选各自往返一致（覆盖每个可选键）', () => {
      const singles: FeedbackQueryState[] = [
        { page: 2, pageSize: 20, appName: 'Launcher+' },
        { page: 1, pageSize: 20, appVersion: '1.2.0' },
        { page: 1, pageSize: 20, categories: ['OTHER'] },
        { page: 1, pageSize: 20, startDate: '2026-02-28' },
        { page: 1, pageSize: 20, endDate: '2026-12-31' },
      ]
      for (const query of singles) {
        expect(decodeQuery(encodeQuery(query))).toEqual(query)
      }
    })

    it('空字符串视为空值省略', () => {
      expect(encodeQuery({ page: 1, pageSize: 20, appName: '', keyword: '' })).toEqual({})
    })
  })

  describe('invalidFallsBackToDefault', () => {
    it.each([
      ['page=0', { page: '0' }],
      ['page=-1', { page: '-1' }],
      ['page=abc', { page: 'abc' }],
      ['page=1.5', { page: '1.5' }],
      ['pageSize=999', { pageSize: '999' }],
      ['pageSize=0', { pageSize: '0' }],
      ['category=XX', { category: 'XX' }],
      ['category 部分非法（BUG,XX）整体回默认', { category: 'BUG,XX' }],
      ['status=2', { status: '2' }],
      ['status=true', { status: 'true' }],
      ['startDate=乱码', { startDate: '乱码' }],
      ['endDate=2026-02-30（不存在的日历日）', { endDate: '2026-02-30' }],
      ['startDate=2026-1-1（格式不严）', { startDate: '2026-1-1' }],
    ])('%s → 回默认值且不抛异常', (_name, params) => {
      expect(decodeQuery(params)).toEqual(DEFAULT_QUERY)
    })

    it('合法字段保留、非法字段单独回默认（互不影响）', () => {
      expect(decodeQuery({ page: '4', category: 'XX', status: '1' })).toEqual({
        ...DEFAULT_QUERY,
        page: 4,
        status: 1,
      })
    })

    it('空字符串参数视为未传', () => {
      expect(decodeQuery({ appName: '', keyword: '' })).toEqual(DEFAULT_QUERY)
    })
  })

  describe('defaults', () => {
    it('pageSize 选项 [10,20,50,100]，默认 page=1 / pageSize=20（行为 3.7）', () => {
      expect([...PAGE_SIZE_OPTIONS]).toEqual([10, 20, 50, 100])
      expect(DEFAULT_QUERY).toEqual({ page: 1, pageSize: 20 })
      expect(decodeQuery({}).page).toBe(1)
      expect(decodeQuery({}).pageSize).toBe(20)
    })
  })

  describe('hasActiveFilters', () => {
    it('默认状态（含非默认页码/页大小）不算有筛选', () => {
      expect(hasActiveFilters(DEFAULT_QUERY)).toBe(false)
      expect(hasActiveFilters({ page: 5, pageSize: 100 })).toBe(false)
    })

    it('status=0 也是有效筛选', () => {
      expect(hasActiveFilters({ page: 1, pageSize: 20, status: 0 })).toBe(true)
    })
  })
})
