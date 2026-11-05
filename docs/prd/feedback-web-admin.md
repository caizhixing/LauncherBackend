# PRD：用户反馈管理控制台 Web 前端（feedback-web-admin）

- **milestone**: feedback-web-admin-v1
- **日期 / 对谈轮次**: 2026-11-05 / 3（按 multi-agent-pr-flow 模板重构；用户同日确认 §6 全部决策）
- **状态**: frozen（用户 2026-11-05 确认；frozen 后接口改动须走变更记录并同步所有受影响 issue）
- **上游**: `docs/prd/feedback-backend.md`（frozen）。本 PRD **只消费不修改**后端任何契约。

---

## 1. 背景与目标

后端 v1 已交付（登录/提交/列表/标记/删除），管理端 Web 前端是一期 out-of-scope，管理员目前只能用
`docs/api.http` 手工查反馈。本 PRD 定义管理控制台前端：登录 → 筛选/检索/分页浏览 → 查看详情 →
标记状态 → 删除，跑通「可用」闭环。

- 技术栈（已冻结，见 `DECISIONS.md #Web 管理端技术栈选型`）：Vite + Vue 3 + TypeScript +
  Element Plus + vue-router，SPA，源码在仓库 `web/`，开发期 Vite 代理 `/api`，生产 Nginx 同源反代。
- 视觉基线：**Element Plus 默认主题**，`web/src/styles/tokens.css` 仅覆盖中文字体栈；表格密度走
  Element Plus 官方全局尺寸（main.ts 装配 `size: 'small'`）；不自造 `--el-*` 令牌、不写组件类选择器
  覆写、不引入 Tailwind / iconfont CDN（图标用 `@element-plus/icons-vue`）。见文末变更记录 2026-11-05-1。
- 成功标准：管理员在浏览器完成全部日常操作；§4 行为清单全绿。

Out-of-scope：统计图表、批量操作、CSV 导出、回复/备注、图片（`images` 恒空数组，UI 不展示）、
多管理员/角色、i18n、暗色主题、静默续期（后端无 refresh）、**任何后端代码改动**。

## 2. 冻结接口与数据模型

> 本节是全部 issue 的公共契约。worker 之间不得各自发明；要改这里 = 改所有相关 issue 契约 + 变更记录。

### 2.1 消费的后端契约（引用 `docs/prd/feedback-backend.md` §2，摘要约束）

- 5 个接口：`POST /api/login`（公开）、`POST /api/feedback`（公开，App 端用，**前端不用**）、
  `GET /api/feedback/list`、`PUT /api/feedback/{id}/status`、`DELETE /api/feedback/{id}`（后三个 Bearer 鉴权）
- 统一响应体 `{code, message, data}`；**非 2xx 也带该响应体**，前端一律按 body 的 `code` 分流，不按 HTTP status
- 错误码：0 / 40001 / 40100 / 40101 / 40102 / 40401 / 42900 / 50000
- list 的 9 个 query 参数：`page/pageSize/appName/appVersion/category/status/startDate/endDate/keyword`；
  `category` 逗号分隔多选（大小写敏感）；日期 `yyyy-MM-dd` 含端点全天；`keyword` trim 后空 = 未传
- 字段 camelCase；`createdAt/updatedAt` 已是 Asia/Shanghai 的 `yyyy-MM-dd HH:mm:ss` 字符串，**前端原样渲染，
  禁止 `new Date()` 重排时区**；`deviceModel/osVersion/contact` 为 null 时**整个字段省略**（不出现键）
- `PUT status` 与 `DELETE` 成功均返回 `data: null`（拿不到新 `updatedAt`，见 D11）

### 2.2 工程与目录契约

```
web/                            # Vue SPA 根（Gradle 侧零改动，不进任何 build.gradle.kts）
├─ package.json / package-lock.json   # 依赖版本以 lock 文件为准，提交入库
├─ vite.config.ts               # server.proxy['/api'] → http://127.0.0.1:8080
├─ src/
│  ├─ main.ts / App.vue / router.ts
│  ├─ styles/tokens.css         # 仅：中文字体栈覆盖（表格密度走 EP 官方全局 size，见 §1）
│  ├─ api/{types.ts, http.ts, auth.ts, feedback.ts}
│  ├─ auth/{session.ts, guard.ts}
│  ├─ url/feedbackQuery.ts      # URL ↔ 筛选状态 编解码纯函数
│  ├─ stores/useFeedbackList.ts # 列表状态（可注入 api，供单测）
│  ├─ views/{LoginView.vue, FeedbackListView.vue}
│  └─ components/{FeedbackFilters.vue, FeedbackTable.vue, StatusTag.vue, FeedbackDetailDialog.vue}
```

- 门禁命令（`package.json` scripts，worker 验证基线）：`npm run typecheck`（vue-tsc）、
  `npm run build`（vite build）、`npm run test`（vitest run）。**不跑任何 Gradle 任务。**
- `.gitignore` 追加：`web/node_modules/`、`web/dist/`
- 版本锁定：初始化时联网 `npm install` 一次生成 lock 文件；此后 worktree 内 `npm install --offline`
  走 npm 本机 cache（D14）。

### 2.3 前端冻结代码契约（worker 公共接缝，包名 `web/src`）

```ts
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
```

```ts
// api/http.ts —— 唯一发请求的模块；fetch 可注入（构造/工厂参数），供单测
export class ApiError extends Error {
  constructor(readonly code: number, message: string) { super(message) }
}
export interface HttpClient { /* get/post/put/delete，均解 envelope */ }
export function createHttpClient(fetchFn: typeof fetch, opts: {
  getToken: () => string | null
  onUnauthorized: () => void   // 收到 40100/40101 时先清 token 再跳登录（由 guard 装配处注册）
}): HttpClient
// 行为：code===0 → 返回 data（保留 T|null 语义）；code!==0 → 抛 ApiError(code, message)
//      网络失败 / body 非 JSON / 缺 envelope → 抛 ApiError(50000, '服务暂不可用，请稍后重试')
//      需鉴权的请求自动注入 `Authorization: Bearer <token>`；无 token 直接走 onUnauthorized，不发请求
```

```ts
// url/feedbackQuery.ts —— URL 是筛选状态唯一事实源；编解码纯函数
export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100] as const
export interface FeedbackQueryState {
  page: number; pageSize: (typeof PAGE_SIZE_OPTIONS)[number]
  appName?: string; appVersion?: string
  categories?: FeedbackCategory[]          // 序列化为逗号拼接（后端契约）
  status?: FeedbackStatus
  startDate?: string; endDate?: string     // yyyy-MM-dd，结束不早于开始（UI 本地校验拦截）
  keyword?: string                         // 原样保留，trim 交给后端
}
export function encodeQuery(s: FeedbackQueryState): Record<string, string>  // 空值省略键
export function decodeQuery(p: Record<string, string>): FeedbackQueryState  // 非法值回默认（D18）
```

```ts
// auth/session.ts —— token 存取；存储键冻结（D1 决定 storage 类型）
export const TOKEN_STORAGE_KEY = 'feedback_admin_token'
// auth/guard.ts —— 纯函数：routeGuard(hasToken, to) → 放行 | redirect('/login?returnUrl=' + to.fullPath)
//                  已登录访问 /login → redirect('/feedback')
// router.ts —— 两条路由 /login、/feedback；守卫接线在此文件（issue #1 交付，#2/#3 不改）
// api/auth.ts —— login(req) → LoginResponse（#2 交付）
// api/feedback.ts —— listFeedback(query)（#3 交付）；updateFeedbackStatus(id, status)、
//                    deleteFeedback(id)（#4 追加，不改已有函数签名）
// stores/useFeedbackList.ts —— 状态 + 动作（fetch/切换/删除），api 经构造参数注入供 fake 单测
```

### 2.4 字段展示规则（P2 列表页 + F1 详情抽屉）

| 字段 | 列表 | 详情 | 空值 |
|---|---|---|---|
| `id` | `#123` | 显示 | — |
| `title` | 单行省略 + tooltip 全文 | 全文 | — |
| `content` | **不显示** | 全文保留换行；`keyword` 命中处高亮（D10） | — |
| `category` | 中文标签（StatusTag 风格） | 中文标签 | — |
| `appName`/`appVersion` | 合并一列 `Launcher+ 1.2.0` | 分两行 | — |
| `deviceModel`/`osVersion` | 不显示 | 合并一行 `Pixel 7 · Android 15` | `—` |
| `deviceId` | 不显示 | 全文 + 复制按钮 | — |
| `contact` | 不显示 | 全文 + 复制按钮 | `—` |
| `status` | 标签 + 行操作 | 标签 + 快捷操作 | — |
| `images` | **不显示** | **不显示** | — |
| `createdAt` | 显示（原样字符串） | 显示 | — |
| `updatedAt` | 不显示 | 显示 | — |

列表默认列（D8）：`#id` / 标题 / 分类 / 应用 / 状态 / 创建时间 / 操作。1024px 宽不横向滚动
（表格自身滚动除外，D9 窄屏）。

### 2.5 页面行为细则

**登录（P1）**：用户名/密码必填（本地拦截不发请求）；提交中按钮禁用；`40102` → 「用户名或密码错误」
（不区分用户名/密码错）；`40001` → 直接展示后端 `message`；`42900` → 「尝试过于频繁，请稍后再试」；
`50000`/断网 → 「服务暂不可用，请稍后重试」。成功 → 存 token → 跳 `returnUrl` 或 `/feedback`。

**筛选（P2）**：控件与语义按 §2.1（appName/appVersion 精确输入、category 多选、status 三态、
日期区间含端点、keyword）；变更即时生效 + 300ms 防抖 + 回第 1 页（D2/D3）；筛选与页码同步进 URL，
刷新/后退保持；提供「清空筛选」。前端不提供排序控件（后端固定 `createdAt DESC`）。

**分页**：`pageSize` 取 `PAGE_SIZE_OPTIONS`，默认 20；显示真实 `total`；`page` 超界显示空态
（后端返回空 list + 正常 total），不自动跳页。

**标记状态**：行内与详情抽屉均可切换，双向 `0↔1`；成功 toast 并重拉当前页（D11）；失败保持原状态
并提示；`40401` → 「这条反馈已被删除」+ 刷新。

**删除**：二次确认（标题「删除反馈 #12」、正文含「删除后不可恢复」、危险色按钮）；成功 toast + 重拉
当前页；当前页因此删空且 `page > 1` → 自动回上一页；`40401` 同上。

**加载/空态/失败**：首屏骨架；翻页/筛选表格 loading 不闪白；空态区分「暂无反馈」（无筛选且 total=0）
与「没有符合条件的反馈 + 清空筛选」（有筛选）；接口失败 → 表格错误态 + 重试，筛选输入不清空；
`40100/40101` → 全局提示「登录已过期，请重新登录」→ 跳登录 → 回来保留筛选（returnUrl）。

### 2.6 降级与失败契约（按 `code` 分流）

| 依赖 | 失败方式 | 期望行为 |
|---|---|---|
| `POST /api/login` | `40102` / `42900` / `40001` / `50000`·断网 | §2.5 登录文案 |
| `GET /api/feedback/list` | `40100`/`40101` | 清 token → 跳登录（returnUrl 含筛选） |
| | `40001` | 展示 `message`（UI 产生了非法参数，属缺陷需修） |
| | `50000` / 断网 / 超时 | 表格错误态 + 重试，保留筛选 |
| `PUT` / `DELETE` | `40401` | 「这条反馈已被删除」+ 刷新列表 |
| | `40100`/`40101` | 跳登录（写操作丢失，重新操作） |
| | `42900` / `50000` | 「操作失败，请稍后重试」，本地状态不变 |
| 后端不可用 | 全部失败 | 各页可重试错误态，不白屏 |
| 静态部署 | 前端路由 404 | Nginx `try_files` 回退 `index.html`（P3 部署项，不进代码 issue） |

## 3. 任务拆分预案与合并顺序

| 序号 | issue 主题 | 触碰范围 | 依赖 / 冲突提示 |
|---|---|---|---|
| 1 | 契约镜像 + http 封装 + 路由守卫 | `web/src/`：api/{types,http}、auth/{session,guard}、router.ts、tokens.css、两占位视图（工程引导由主 agent 在 main 提交，见 §8） | 无；**全部后续 issue 的依赖** |
| 2 | 登录页 | `views/LoginView.vue`、`api/auth.ts`、登录错误文案映射 | 依赖 #1；与 #3 文件不相交，可并行 |
| 3 | 列表页读路径 | `views/FeedbackListView.vue`、`components/{FeedbackFilters,FeedbackTable,StatusTag}.vue`、`url/feedbackQuery.ts`、`stores/useFeedbackList.ts`、`api/feedback.ts`（仅 list 函数） | 依赖 #1；与 #2 并行；不碰 `router.ts` |
| 4 | 写路径 + 详情抽屉 | `components/FeedbackDetailDialog.vue`、删除确认交互、`api/feedback.ts` **追加** update/delete、`FeedbackTable.vue` 操作列挂点、store 追加动作 | 依赖 #3 合并（同文件追加）；与 #2 无关 |

- 合并顺序 = 序号顺序（1→2→3→4）；波次：W1{#1} → W2{#2,#3}（并发 2）→ W3{#4}。
- **所有 issue 一律禁止触碰 `src/main/kotlin/`、`build.gradle.kts`、`gradle/`、后端任何文件**（越界即 blocked）。

## 4. 逐任务行为清单（验收标准）

### 任务 1：契约镜像 + http 封装 + 路由守卫

- 行为 1.1：在工程引导（§8）之上交付后 `npm run typecheck`、`npm run build`、`npm run test`
  三命令全绿 —— 构建产物 `web/dist/index.html` 存在
- 行为 1.2：`code===0` 返回 `data`（含 `data:null`）；七个非 0 码各自抛 `ApiError(code, message)`
  —— `HttpClientTest.envelopeUnwrap`（注入 fake fetch，参数化）
- 行为 1.3：网络失败 / body 非 JSON / 缺 `code` 键 → 抛 `ApiError(50000, '服务暂不可用，请稍后重试')`
  —— `HttpClientTest.networkAndMalformedFallback`
- 行为 1.4：鉴权请求自动注入 `Authorization: Bearer <token>`；`getToken()` 为 null 时不发请求、
  调用 `onUnauthorized` —— `HttpClientTest.authHeaderInjection` / `missingTokenShortCircuit`
- 行为 1.5：响应 `code` 为 40100/40101 → 调用 `onUnauthorized` 一次且向调用方抛 `ApiError`
  —— `HttpClientTest.unauthorizedRouting`
- 行为 1.6：`routeGuard(false, '/feedback')` → 重定向 `/login?returnUrl=/feedback`；
  `routeGuard(true, '/login')` → 重定向 `/feedback`；`routeGuard(true, '/feedback')` → 放行
  —— `GuardTest`（纯函数）
- 行为 1.7：`session` 的 set/get/clear 读写 `TOKEN_STORAGE_KEY`，clear 后 get 返回 null
  —— `SessionTest`
- 行为 1.8：`tokens.css` 仅覆盖中文字体栈（无自造设计系统）；表格密度由 main.ts 的
  `app.use(ElementPlus, { size: 'small' })` 承载（EP 官方全局尺寸机制）；两占位视图可被路由渲染
  —— 静态检查

### 任务 2：登录页

- 行为 2.1：用户名或密码为空 → 本地提示「请输入用户名/密码」，不发请求 —— `LoginFormTest.validate`
- 行为 2.2：错误码→文案映射：40102→「用户名或密码错误」、42900→「尝试过于频繁，请稍后再试」、
  50000→「服务暂不可用，请稍后重试」、40001→透传后端 `message` —— `LoginCopyTest`（参数化）
- 行为 2.3：登录成功 → `session.set(token)` → 跳 `returnUrl`（无则 `/feedback`）—— `LoginFlowTest`
  （fake HttpClient）
- 行为 2.4：请求进行中提交按钮禁用（防重复提交）—— `LoginFlowTest.submitInFlight`
- 行为 2.5：登录页回车提交等价按钮点击 —— 手工冒烟（阶段 5）

### 任务 3：列表页读路径

- 行为 3.1：`encodeQuery`/`decodeQuery` 往返一致；`categories` 序列化为逗号拼接；空值省略键
  —— `FeedbackQueryTest.roundTrip`
- 行为 3.2：`decodeQuery` 对非法值（`page=0/-1/abc`、`pageSize=999`、`category=XX`、`status=2`、
  乱码日期）回默认值不抛异常 —— `FeedbackQueryTest.invalidFallsBackToDefault`（D18）
- 行为 3.3：筛选变更 → 重置 `page=1` 并更新 URL；防抖 300ms 内多次变更只发一次请求
  —— `UseFeedbackListTest.debounceAndResetPage`（注入 fake api + fake timer）
- 行为 3.4：请求参数与 `FeedbackQueryState` 一一对应（category 多选、日期、keyword 原样）
  —— `UseFeedbackListTest.requestParamsMatchState`（fake api 断言收到的 query）
- 行为 3.5：空态判定：无筛选且 total=0 → 「暂无反馈」；有筛选 → 「没有符合条件的反馈」
  —— `EmptyStateTest`（纯函数）
- 行为 3.6：`50000` → 错误态可重试且重试用当前筛选（fake api 第一次抛错第二次成功）
  —— `UseFeedbackListTest.retryKeepsFilters`
- 行为 3.7：时间列渲染值 === 后端字符串（无 Date 转换）；`pageSize` 选项 [10,20,50,100] 默认 20
  —— 静态检查 + `FeedbackQueryTest.defaults`

### 任务 4：写路径 + 详情抽屉

- 行为 4.1：状态切换双向调用 `PUT {status}`；成功后重拉当前页（fake api 断言调用序）
  —— `UseFeedbackListTest.toggleStatusReloadsPage`
- 行为 4.2：`nextPageAfterDelete(page, remainingCount)`：当前页删空且 page>1 → page-1；否则不变
  —— `PageCalcTest`（纯函数）
- 行为 4.3：删除必须经确认弹窗（含「不可恢复」文案）才发 `DELETE`；成功 toast + 按 4.2 规则刷新
  —— `UseFeedbackListTest.deleteRequiresConfirmAndReloads`
- 行为 4.4：`40401` → 「这条反馈已被删除」+ 刷新列表；其他失败本地状态不变 —— `UseFeedbackListTest.notFoundRefreshes`
- 行为 4.5：详情抽屉展示 §2.4 详情列全部字段；`images` 无对应 UI；`deviceId/contact` 有复制按钮；
  正文保留换行、keyword 命中高亮 —— 静态检查 + 手工冒烟（阶段 5）
- 行为 4.6：写请求收到 40100/40101 → 走全局 `onUnauthorized` 跳登录（复用 #1 行为 1.5 装配）
  —— 静态检查

## 5. 测试策略

- 栈：**vitest**（node 环境，无 jsdom；不挂载组件，不引 @testing-library）—— 依赖冻结在
  `package-lock.json`（D13）。
- 分层覆盖：纯函数（`feedbackQuery` 编解码 / `routeGuard` / 错误文案映射 / `nextPageAfterDelete` /
  `HttpClient` envelope 解包——注入 fake fetch）**100% 行覆盖**；`useFeedbackList` store 动作 ≥90%
  （fake api 注入）；Vue 组件 / 样式 / 路由装配 **n/a**（靠 `typecheck` + `build` + review rubric +
  阶段 5 手工冒烟）。
- 断言质量红线：无断言 / 恒真断言 / catch-all 一律 review fail。
- worker 里程碑 `compiled` = typecheck + build 绿；`tested` = vitest 绿 + 新代码覆盖率数字。
- **worker 不起后端、不联调**（D17）；端到端手工冒烟在阶段 5 集成门禁后由主 agent + 用户执行。

## 6. PRD 外决策清单（用户 2026-11-05 已确认，全部按推荐；可否决后走变更记录）

| # | 决策 | 推荐 | 备选 | 状态 |
|---|---|---|---|---|
| D1 | token 存储与免登 | `localStorage`，7 天免登（后端有效期即上限） | `sessionStorage`（关标签即登出） | 已确认 |
| D2 | 筛选生效方式 | 即时生效 + 300ms 防抖，无查询按钮 | 点「查询」才请求 | 已确认 |
| D3 | 改筛选后页码 | 回第 1 页 | 保持当前页 | 已确认 |
| D4 | `deviceId` 展示 | 详情全量 + 复制；列表不显示 | 列表截断显示 | 已确认 |
| D5 | `contact` 展示 | 列表不显示；详情全量 + 复制 | 列表也显示 | 已确认 |
| D6 | 删除确认强度 | 普通确认弹窗（含「不可恢复」） | 需输入 `#id` 才能删 | 已确认 |
| D7 | 状态文案口径 | 待处理 / 已完成（与后端 PRD 一致）；同步改 `docs/api.http:40` 注释 | 未处理 / 已处理 | 已确认 |
| D8 | 列表列 | §2.4 的 6 列 + 操作列 | 全字段平铺 | 已确认 |
| D9 | 窄屏支持 | 最低可用：表格横向滚动 + 筛选折叠 | 仅桌面 | 已确认 |
| D10 | 关键词高亮 | 详情正文高亮 | 不做 | 已确认 |
| D11 | 标状态后 | 重拉当前页（`PUT` 返回 `data:null`，拿不到新 updatedAt） | 局部改行（updatedAt 会旧） | 已确认 |
| D12 | 登录页记住用户名 | 记住用户名（不记密码） | 不记 | 已确认 |
| D13 | 前端测试栈 | vitest 仅纯函数/store（无 jsdom、无组件挂载） | 不引测试框架（行为清单无法断言，违背流程测试策略） | 已确认 |
| D14 | worktree 依赖安装 | 主 checkout 联网 `npm install` 一次；各 worktree `npm install --offline` 走 npm cache | 每 worktree 联网装 / robocopy 镜像 node_modules | 已确认 |
| D15 | 前端验证基线 | `typecheck` + `build` + `test` 三命令；不跑 Gradle | — | 已确认 |
| D16 | worker/reviewer 模型 | 沿用 `DECISIONS.md #模型选型`（worker=glm-5.3 缺省、reviewer=testbird/k3.1-pro） | 重开阶段 0 选型门 | 已确认 |
| D17 | 联调时机 | worker 不起后端；阶段 5 集成后统一手工冒烟 | 每个 worker 自行起后端联调（端口/环境冲突多） | 已确认 |
| D18 | URL 非法参数策略 | `decodeQuery` 回默认值不报错（URL 可被手改，与 API 层 40001 语义分开） | 映射成错误提示 | 已确认 |

## 7. 契约影响面（红线 18 核对项）

- **不触碰任何后端契约**：接口路径/参数名、统一响应结构、错误码表、`category`/`status` 值域、
  时间格式与 camelCase、`images` 语义、JWT 契约全部只读消费。
- 本次**新建**的前端契约：`web/src/api/types.ts` 镜像、`TOKEN_STORAGE_KEY`、路由 `/login`·`/feedback`、
  URL query 编码规则（§2.3）。涉契约 issue（#1 #3 #4）标题带 `contract` 前缀提醒 reviewer。
- 后端 CORS / Nginx 部署：同源方案已规避（`DECISIONS.md #Web 管理端技术栈选型`），无后端改动。

## 8. 状态协议

默认按 skill `references/status-protocol.md` 执行（claimed/compiled/tested/pr-open/rework-N、
blocked 即时上报、label 状态机主 agent 单写、review verdict 固定格式）。本项目前端特化：

- `compiled` 里程碑 = `npm run typecheck && npm run build` 绿（不跑 Gradle）；
- `tested` 里程碑 = `npm run test` 绿 + 新代码行覆盖率数字；
- 无设备验证里程碑（`verified` 不适用）；阶段 5 手工冒烟在集成门禁后执行；
- worktree 根沿用 `E:\work\LauncehrBackend-wt`，命名 `wt-issue-N` / `wt-review-P`（见
  `DECISIONS.md #worktree 根目录`）。

### 工程引导（阶段 0，主 agent 在 main 提交，不进 issue fan-out）

对齐 `DECISIONS.md #空仓库引导方式`：主 agent 只提交构建系统，业务代码全部走 issue。

- `web/` Vite + Vue 3 + TS 脚手架：`package.json` + `package-lock.json`（版本锁定）、
  `vite.config.ts`（含 `/api` → `http://127.0.0.1:8080` 代理）、`tsconfig`、npm scripts
  （typecheck / build / test）、`vitest` 占位测试一条、`.gitignore` 追加 `web/node_modules/`、
  `web/dist/`、占位 `main.ts` / `App.vue`（不含任何 §2.3 业务接缝代码）
- 一次性联网 `npm install` 生成 lock 并验证三命令绿（D14；需用户当场授权联网，此后全部离线）
- 引导验收：`npm run typecheck && npm run build && npm run test` 三绿 + `web/dist/index.html`
  产物存在 + 代理与 `.gitignore` 就位

## 变更记录

### 2026-11-05-1 · 行为 1.8 / §2.2 tokens.css 密度机制修订（用户确认，方案 A）

- 背景：PR #17 两轮 review 均卡在「以 `--el-*` 变量覆盖表格密度」——实测 element-plus 2.13
  theme-chalk 未暴露表格 padding CSS 变量，原文在实现层不可满足（worker grep 源码证实，
  reviewer 认可该事实基础）。
- 修订：tokens.css 只保留中文字体栈覆盖；表格密度改由 main.ts
  `app.use(ElementPlus, { size: 'small' })`（EP 官方全局尺寸机制）承载；禁止自造 `--el-*` 令牌
  与组件类选择器 padding 覆写。issue #13 触碰范围相应允许 main.ts 的 size 装配行。
- 影响面：纯实现机制替换，无用户可见行为变化（列表密度诉求不变）；D 决策不受影响；
  契约（后端接口/前端冻结代码签名）零触碰。受影响 issue：#13（行为 1.8）。
