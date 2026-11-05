import { describe, expect, it } from 'vitest'
import { nextPageAfterDelete } from './useFeedbackList'

// 行为 4.2（PRD feedback-web-admin §4 任务 4）：删除后页码纯函数
describe('PageCalcTest', () => {
  it('当前页删空且 page>1 → 回上一页', () => {
    expect(nextPageAfterDelete(2, 0)).toBe(1)
    expect(nextPageAfterDelete(5, 0)).toBe(4)
  })

  it('page=1 删空 → 留在第 1 页（无上一页可回）', () => {
    expect(nextPageAfterDelete(1, 0)).toBe(1)
  })

  it('当前页未删空 → 页码不变', () => {
    expect(nextPageAfterDelete(2, 1)).toBe(2)
    expect(nextPageAfterDelete(3, 18)).toBe(3)
  })
})
