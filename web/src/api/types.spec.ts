import { describe, expect, it } from 'vitest'
import { CATEGORY_LABEL, ErrorCode, STATUS_LABEL } from './types'

// 契约镜像防篡改（PRD feedback-web-admin §2.3 冻结值域）
describe('types mirror', () => {
  it('ErrorCode 与后端冻结错误码表一致', () => {
    expect(ErrorCode).toEqual({
      OK: 0,
      BAD_REQUEST: 40001,
      UNAUTHORIZED: 40100,
      TOKEN_EXPIRED: 40101,
      BAD_CREDENTIALS: 40102,
      NOT_FOUND: 40401,
      RATE_LIMITED: 42900,
      INTERNAL: 50000,
    })
  })

  it('CATEGORY_LABEL 覆盖四个冻结分类', () => {
    expect(CATEGORY_LABEL).toEqual({
      BUG: '功能异常',
      EXPERIENCE: '体验问题',
      SUGGESTION: '功能建议',
      OTHER: '其他',
    })
  })

  it('STATUS_LABEL 覆盖 0/1 两态', () => {
    expect(STATUS_LABEL).toEqual({ 0: '待处理', 1: '已完成' })
  })
})
