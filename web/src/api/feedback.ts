// api/feedback.ts —— 读/写路径 API（issue #15 交付 listFeedback；issue #16 追加 update/delete，
// 不改已有函数名与签名）
import { ApiError } from './http'
import type { HttpClient } from './http'
import { ErrorCode } from './types'
import type { FeedbackListResponse, FeedbackStatus } from './types'
import { encodeQuery } from '../url/feedbackQuery'
import type { FeedbackQueryState } from '../url/feedbackQuery'

export interface FeedbackApi {
  listFeedback(query: FeedbackQueryState): Promise<FeedbackListResponse>
  // issue #16 冻结签名：写接口成功均返回 data:null，封装层吞掉返回值 → Promise<void>
  updateFeedbackStatus(id: number, status: FeedbackStatus): Promise<void>
  deleteFeedback(id: number): Promise<void>
}

const LIST_PATH = '/api/feedback/list'

export function createFeedbackApi(http: HttpClient): FeedbackApi {
  return {
    // GET /api/feedback/list；query 参数名与后端九参数一致（§2.1），由 encodeQuery 产出
    // options 缺省 → http 层默认鉴权（list 需 Bearer）
    async listFeedback(query: FeedbackQueryState): Promise<FeedbackListResponse> {
      const qs = new URLSearchParams(encodeQuery(query)).toString()
      const data = await http.get<FeedbackListResponse>(qs === '' ? LIST_PATH : `${LIST_PATH}?${qs}`)
      // code===0 但 data 缺失属契约异常，归一 50000 兜底文案（与 http 层一致）
      if (data === null) throw new ApiError(ErrorCode.INTERNAL, '服务暂不可用，请稍后重试')
      return data
    },
    // PUT /api/feedback/{id}/status，body { status }（docs/api.http §4）。
    // 40100/40101 由共享 HttpClient 的全局 onUnauthorized 统一跳登录（行为 4.6，router.ts 装配处注册），本层不另起鉴权逻辑
    async updateFeedbackStatus(id: number, status: FeedbackStatus): Promise<void> {
      await http.put(`/api/feedback/${id}/status`, { status })
    },
    // DELETE /api/feedback/{id}；成功 data=null 吞掉返回值
    async deleteFeedback(id: number): Promise<void> {
      await http.delete(`/api/feedback/${id}`)
    },
  }
}
