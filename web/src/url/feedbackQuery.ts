// url/feedbackQuery.ts —— URL 是筛选状态唯一事实源；编解码纯函数（PRD feedback-web-admin §2.3）
// 解析宽松（D18）：URL 可被手改，非法值一律回默认值不抛异常，与 API 层 40001 严格校验是两回事
import type { FeedbackCategory, FeedbackStatus } from '../api/types'

export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100] as const

export interface FeedbackQueryState {
  page: number
  pageSize: (typeof PAGE_SIZE_OPTIONS)[number]
  appName?: string
  appVersion?: string
  categories?: FeedbackCategory[] // 序列化为逗号拼接（后端契约 §2.1 的 category 参数）
  status?: FeedbackStatus
  startDate?: string // yyyy-MM-dd，结束不早于开始（UI 本地校验拦截）
  endDate?: string
  keyword?: string // 原样保留，trim 交给后端
}

export const DEFAULT_QUERY: FeedbackQueryState = { page: 1, pageSize: 20 }

const CATEGORY_VALUES: readonly FeedbackCategory[] = ['BUG', 'EXPERIENCE', 'SUGGESTION', 'OTHER']

export function isFeedbackCategory(value: string): value is FeedbackCategory {
  return (CATEGORY_VALUES as readonly string[]).includes(value)
}

function isFilled(value: string | undefined): value is string {
  return value !== undefined && value !== ''
}

// 有任一筛选项即视为「有筛选」（空态文案与清空按钮的判定输入，行为 3.5）
export function hasActiveFilters(query: FeedbackQueryState): boolean {
  return (
    isFilled(query.appName) ||
    isFilled(query.appVersion) ||
    (query.categories?.length ?? 0) > 0 ||
    query.status !== undefined ||
    isFilled(query.startDate) ||
    isFilled(query.endDate) ||
    isFilled(query.keyword)
  )
}

// 空值省略键；与默认值相同的 page/pageSize 同样省略，保持 URL 干净（roundTrip 语义不变）
export function encodeQuery(s: FeedbackQueryState): Record<string, string> {
  const out: Record<string, string> = {}
  if (s.page !== DEFAULT_QUERY.page) out.page = String(s.page)
  if (s.pageSize !== DEFAULT_QUERY.pageSize) out.pageSize = String(s.pageSize)
  if (isFilled(s.appName)) out.appName = s.appName
  if (isFilled(s.appVersion)) out.appVersion = s.appVersion
  if (s.categories !== undefined && s.categories.length > 0) out.category = s.categories.join(',')
  if (s.status !== undefined) out.status = String(s.status)
  if (isFilled(s.startDate)) out.startDate = s.startDate
  if (isFilled(s.endDate)) out.endDate = s.endDate
  if (isFilled(s.keyword)) out.keyword = s.keyword
  return out
}

function parsePositiveInt(raw: string | undefined): number | null {
  if (raw === undefined || !/^\d+$/.test(raw)) return null
  const n = Number(raw)
  return Number.isSafeInteger(n) && n >= 1 ? n : null
}

// 严格 yyyy-MM-dd 且必须是真实日历日（2026-02-30 之类回默认，D18）。
// 不用 Date.parse：V8 会把越界日回卷成下月日期（2026-02-30 → 3 月 2 日）而不返回 NaN
function parseDate(raw: string | undefined): string | undefined {
  if (raw === undefined || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return undefined
  const year = Number(raw.slice(0, 4))
  const month = Number(raw.slice(5, 7))
  const day = Number(raw.slice(8, 10))
  if (month < 1 || month > 12 || day < 1) return undefined
  const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
  const daysInMonth = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day <= daysInMonth[month - 1] ? raw : undefined
}

function parseCategories(raw: string | undefined): FeedbackCategory[] | undefined {
  if (raw === undefined) return undefined
  const parts = raw.split(',')
  if (!parts.every((part) => isFeedbackCategory(part))) return undefined
  return parts
}

// 非法值回默认不抛异常（D18）
export function decodeQuery(p: Record<string, string>): FeedbackQueryState {
  const page = parsePositiveInt(p.page) ?? DEFAULT_QUERY.page
  const rawPageSize = parsePositiveInt(p.pageSize)
  const pageSize = PAGE_SIZE_OPTIONS.find((option) => option === rawPageSize) ?? DEFAULT_QUERY.pageSize
  const status: FeedbackStatus | undefined = p.status === '0' ? 0 : p.status === '1' ? 1 : undefined
  return {
    page,
    pageSize,
    appName: isFilled(p.appName) ? p.appName : undefined,
    appVersion: isFilled(p.appVersion) ? p.appVersion : undefined,
    categories: parseCategories(p.category),
    status,
    startDate: parseDate(p.startDate),
    endDate: parseDate(p.endDate),
    keyword: isFilled(p.keyword) ? p.keyword : undefined,
  }
}
