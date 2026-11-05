// api/types.ts —— 后端契约的 TS 镜像，唯一事实源，字段与 §2.1 逐条对应
export const ErrorCode = {
  OK: 0, BAD_REQUEST: 40001, UNAUTHORIZED: 40100, TOKEN_EXPIRED: 40101,
  BAD_CREDENTIALS: 40102, NOT_FOUND: 40401, RATE_LIMITED: 42900, INTERNAL: 50000,
} as const

export interface ApiResponse<T> { code: number; message: string; data: T | null }
export interface LoginRequest { username: string; password: string }
export interface LoginResponse { token: string; expiresIn: number }
export type FeedbackCategory = 'BUG' | 'EXPERIENCE' | 'SUGGESTION' | 'OTHER'
export type FeedbackStatus = 0 | 1
export interface FeedbackListItem {
  id: number; title: string; content: string; category: FeedbackCategory
  appName: string; appVersion: string
  deviceModel?: string; osVersion?: string   // 可选：null 时后端省略键
  deviceId: string; contact?: string
  status: FeedbackStatus; images: string[]   // 一期恒空数组，UI 不展示
  createdAt: string; updatedAt: string       // 原样渲染
}
export interface FeedbackListResponse { total: number; page: number; pageSize: number; list: FeedbackListItem[] }

export const CATEGORY_LABEL: Record<FeedbackCategory, string> = {
  BUG: '功能异常', EXPERIENCE: '体验问题', SUGGESTION: '功能建议', OTHER: '其他',
}
export const STATUS_LABEL: Record<FeedbackStatus, string> = { 0: '待处理', 1: '已完成' }
