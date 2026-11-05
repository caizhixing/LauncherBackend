// api/feedback.ts —— 读路径 API（issue #15 冻结签名：listFeedback(query) → Promise<FeedbackListResponse>）
// update/delete 由 issue #4 在本文件追加方法，不得破坏已有函数名与签名
import { ApiError } from './http'
import type { HttpClient } from './http'
import { ErrorCode } from './types'
import type { FeedbackListResponse } from './types'
import { encodeQuery } from '../url/feedbackQuery'
import type { FeedbackQueryState } from '../url/feedbackQuery'

export interface FeedbackApi {
  listFeedback(query: FeedbackQueryState): Promise<FeedbackListResponse>
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
  }
}
