# PRD：用户反馈系统后端（feedback-backend）

- **milestone**: feedback-backend-v1
- **日期 / 对谈轮次**: 2026-11-04 / 1（源自用户 PRD v1.0 对谈定稿）
- **状态**: frozen（用户 2026-11-04 确认；frozen 后接口改动须走变更记录并同步所有受影响 issue）

---

## 1. 背景与目标

为 App 提供统一的用户反馈收集能力：App 端匿名提交反馈，管理员登录后按条件筛选、检索、
分页浏览反馈，并标记处理状态。

一期范围（不含管理端 Web 前端、不含图片上传）：

- 一张反馈表，跑通「提交 → 筛选浏览 → 标记完成」完整闭环
- 单管理员，账号放配置文件（BCrypt 哈希）
- 图片二期做，表结构提前预留

Out-of-scope：App 用户体系、多管理员/角色、反馈回复/通知、图片上传、管理端 Web 前端、
M3 部署运维（systemd/Nginx/HTTPS 由部署阶段处理，不进代码 issue）。

## 2. 冻结接口与数据模型

> 本节是全部 issue 的公共契约。worker 之间不得各自发明；要改这里 = 改所有相关 issue 契约 + 变更记录。

### 2.1 数据模型（schema.sql，与仓库根 schema.sql 逐字一致）

```sql
CREATE TABLE `feedback` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(50)   NOT NULL,
  `content`      VARCHAR(1000) NOT NULL,
  `category`     VARCHAR(20)   NOT NULL,
  `app_name`     VARCHAR(50)   NOT NULL,
  `app_version`  VARCHAR(20)   NOT NULL,
  `device_model` VARCHAR(100)  DEFAULT NULL,
  `os_version`   VARCHAR(20)   DEFAULT NULL,
  `device_id`    VARCHAR(64)   NOT NULL,
  `contact`      VARCHAR(100)  DEFAULT NULL,
  `status`       TINYINT       NOT NULL DEFAULT 0,
  `images`       JSON          DEFAULT NULL,
  `created_at`   DATETIME      NOT NULL,
  `updated_at`   DATETIME      NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_filter` (`app_name`, `category`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

Exposed 表定义（`model/table/FeedbackTable.kt`）：列名与上表 snake_case 一一对应；
`images` 在 Exposed 侧声明为 text（MySQL JSON 列接受字符串写入，一期恒写 `"[]"`）。

### 2.2 枚举与值域（契约，红线 18）

- `FeedbackCategory`：`BUG` / `EXPERIENCE` / `SUGGESTION` / `OTHER`（DB 存枚举名字符串）
- `status`：JSON 契约为 Int，`0` = 待处理，`1` = 已完成；只允许这两个值

### 2.3 统一响应体与错误码（契约）

```json
{ "code": 0, "message": "success", "data": {} }
```

| code | HTTP | 含义 |
|---|---|---|
| 0 | 200 | 成功 |
| 40001 | 400 | 参数错误（message 说明字段与原因） |
| 40100 | 401 | 未登录 / Token 缺失或格式错误 |
| 40101 | 401 | Token 已过期 |
| 40102 | 401 | 用户名或密码错误 |
| 40401 | 404 | 反馈不存在 |
| 42900 | 429 | 提交过于频繁，请稍后再试 |
| 50000 | 500 | 服务器内部错误 |

- HTTP 接口契约：`POST /api/login`（公开）、`POST /api/feedback`（公开）、
  `GET /api/feedback/list`、`PUT /api/feedback/{id}/status`、`DELETE /api/feedback/{id}`（后三个鉴权）
- 请求/响应 JSON 字段一律 camelCase；时间字符串 `yyyy-MM-dd HH:mm:ss`（Asia/Shanghai）
- `data` 为 `null` 时输出 `"data": null`（保留字段）

### 2.4 冻结代码契约（并行 worker 的公共接缝）

包名 `com.caizhixing.feedback`，分层 route → service → repository。

```kotlin
// 统一响应与异常（issue #1 交付，全员引用）
@Serializable data class ApiResponse<T>(val code: Int, val message: String, val data: T?)
object ErrorCode { OK=0; BAD_REQUEST=40001; UNAUTHORIZED=40100; TOKEN_EXPIRED=40101;
                   BAD_CREDENTIALS=40102; NOT_FOUND=40401; RATE_LIMITED=42900; INTERNAL=50000 }
class ApiException(val code: Int, override val message: String) : RuntimeException(message)
// StatusPages 统一映射：ApiException → 其 code 对应 HTTP 状态；未捕获异常 → 50000/500

// 领域模型（issue #4 交付）
enum class FeedbackCategory { BUG, EXPERIENCE, SUGGESTION, OTHER }
enum class FeedbackStatus(val value: Int) { PENDING(0), DONE(1) }
data class Feedback(id, title, content, category, appName, appVersion, deviceModel?, osVersion?,
                    deviceId, contact?, status, images: List<String>, createdAt, updatedAt)

// 仓储（issue #4 建接口，#5/#6 只加方法；唯一数据入口）
interface FeedbackRepository {
    suspend fun insert(feedback: Feedback): Long
    suspend fun findById(id: Long): Feedback?
    // #5 追加： suspend fun findTotalAndList(filter: FeedbackListFilter, page: Int, pageSize: Int): Pair<Long, List<Feedback>>
    // #6 追加： suspend fun updateStatus(id: Long, status: FeedbackStatus, updatedAt: LocalDateTime): Boolean
    //          suspend fun delete(id: Long): Boolean
}
// 实现：Exposed suspend + newSuspendedTransaction(Dispatchers.IO)；表/列映射在本层完成
// 注意 Exposed 1.6 包根为 org.jetbrains.exposed.v1.{core,jdbc,javatime}（见 AGENTS.md）

// 列表筛选（issue #5）
data class FeedbackListFilter(appName?, appVersion?, categories: List<FeedbackCategory>?,
                              status: FeedbackStatus?, startAt: LocalDateTime?, endAt: LocalDateTime?, keyword: String?)

// 限流（issue #2 交付；内存固定窗口，天窗口按 Asia/Shanghai 自然日）
sealed interface RateLimitResult { data object Allowed : RateLimitResult;
                                    data class Denied(val retryAfterSeconds: Long) : RateLimitResult }
interface RateLimiter { fun tryAcquire(key: String): RateLimitResult }

// JWT（issue #3 交付）
data class IssuedToken(val token: String, val expiresIn: Long)   // expiresIn 秒，7 天 = 604800
sealed interface TokenVerification { data class Valid(val username: String) : TokenVerification;
                                     data object Expired; data object Invalid }

// 时间（issue #1 交付）
object AppTime { val ZONE: ZoneId = ZoneId.of("Asia/Shanghai");
                 fun now(clock: Clock): LocalDateTime; fun format(dt: LocalDateTime): String } // "yyyy-MM-dd HH:mm:ss"
// 全部 service 注入 java.time.Clock（Koin 提供），测试可替换

// 配置（issue #1 交付；环境变量优先，缺省从 application.conf 读）
data class AppConfig(adminUsername, adminPasswordHash, jwtSecret, dbUrl, dbUser, dbPassword,
                     port: Int = 8080, corsOrigins: List<String> = emptyList())
// 必填缺失或 JWT_SECRET < 32 字符 → 启动 fail fast 并打印缺失项清单
// Hikari initializationFailTimeout = -1（DB 挂不阻断启动，接口 50000，恢复自愈）

// DTO（model/dto/，@Serializable；null 可选字段序列化时省略——explicitNulls=false）
LoginRequest(username, password) / LoginResponse(token, expiresIn)
SubmitFeedbackRequest(title, content, category, appName, appVersion, deviceId,
                      deviceModel?, osVersion?, contact?) / SubmitFeedbackResponse(feedbackId)
UpdateStatusRequest(status: Int)
FeedbackListItem(全部 Feedback 字段 camelCase，images: List<String> 恒空数组)
FeedbackListResponse(total, page, pageSize, list)
```

路由注册约定：`fun Route.loginRoutes()` / `fun Route.feedbackRoutes()` 扩展函数分文件维护，
`Application` 装配处一行挂载。JSON 配置：`Json { explicitNulls = false; encodeDefaults = true;
ignoreUnknownKeys = true }`。

登录 IP 提取：优先 `X-Forwarded-For` 首个，回退 `remoteHost`（DECISIONS.md #登录限流取 IP 方式）。

### 2.5 接口行为细则（PRD v1.0 第 6 节全文有效）

登录：密码错/用户名错统一 40102；登录限流同 IP 5 次/分钟（42900）；无登出接口；
`expiresIn` = 604800。

提交反馈：校验表（title/content 1–50/1–1000、category 枚举、appName 1–50、appVersion 1–20、
deviceId 1–64、deviceModel ≤100、osVersion ≤20、contact ≤100，均必填按 PRD §6.2）；
非法返回 40001 且 message 指明字段与原因；服务端补全 `status=0`、`images=[]`、createdAt/updatedAt；
限流同 deviceId 1 次/分钟 + 5 次/天（42900）。

列表：page ≥1 默认 1；pageSize 1–100 默认 20；排序固定 `createdAt DESC`；筛选
appName/appVersion 精确、category 逗号分隔多选枚举、status 0|1、startDate/endDate
`yyyy-MM-dd` 含端点当日全天（Asia/Shanghai）、keyword ≤50 匹配标题或内容（trim 后空 = 未传）；
非法值一律 40001 不静默纠正；返回真实 total + list；page 超界返回空 list + 正常 total。

标记状态：`{ "status": 0|1 }`，非 0/1 或 id 非数字 → 40001；不存在 → 40401；更新 updatedAt。
删除：物理删除；不存在 → 40401；两者 data 均为 null。

### 2.6 降级契约（PRD §11 全文有效）

MySQL 失败：登录仍可用（不查库）；其余 50000；进程不崩溃，恢复自愈。
内存限流状态重启清零，接受。必填配置缺失 fail fast。图片二期：正文提交不因图片失败而失败（倾向，二期定）。

## 3. 任务拆分预案与合并顺序

| 序号 | issue 主题 | 触碰范围 | 依赖 / 冲突提示 |
|---|---|---|---|
| 1 | 项目骨架与基础设施 | Application/config/plugin/util/model(dto 基础)/resources | 无 |
| 2 | 内存限流器 | util/RateLimiter + 单测 | 无（与 #1 文件不相交，可并行） |
| 3 | 管理员登录 + JWT 鉴权 | service/route/plugin(Auth)/util(TokenService) | 依赖 #1 #2 合并 |
| 4 | 反馈表 + 提交接口 | model/table/model(领域)/repository/service/route | 依赖 #1 #2 合并 |
| 5 | 反馈列表查询 | route(解析纯函数)/repository(findTotalAndList)/service | 依赖 #4 合并 |
| 6 | 标记状态 + 删除 | repository(updateStatus/delete)/service/route | 依赖 #4 合并；与 #5 不同方法，可并行 |

合并顺序 = 序号顺序（1→2→3→4→5→6）。fan-out 分三波：W1{#1,#2} 并行 → 合并后
W2{#3,#4} 并行 → 合并后 W3{#5,#6} 并行。并发上限 2。

## 4. 逐任务行为清单（验收标准）

### 任务 1：项目骨架与基础设施
- 行为 1.1：必填环境变量（ADMIN_USERNAME/ADMIN_PASSWORD_HASH/JWT_SECRET/DB_URL/DB_USER/DB_PASSWORD）
  任一缺失时，进程启动失败且错误输出包含全部缺失项清单 —— `AppConfigTest.missingRequiredVariablesFailFastWithFullList`
- 行为 1.2：JWT_SECRET 少于 32 字符时启动失败并指明该字段 —— `AppConfigTest.shortJwtSecretRejected`
- 行为 1.3：PORT 未设置时默认 8080，CORS_ORIGINS 逗号分隔解析为列表 —— `AppConfigTest.defaultsApplied`
- 行为 1.4：AppTime 在 Asia/Shanghai 时区产出/格式化 `yyyy-MM-dd HH:mm:ss` —— `AppTimeTest`
- 行为 1.5：ApiException(40001/40101/40401/42900) 经 StatusPages 映射为对应 HTTP 状态 +
  统一响应体；未捕获异常映射 50000/500 —— `ErrorMappingTest`
- 行为 1.6：空服务器启动后健康访问（如未匹配路由返回 404，Ktor 默认，不套统一体——见 §6 决策 1）

### 任务 2：内存限流器
- 行为 2.1：同一 key 在窗口限额内放行，超限拒绝并返回 retryAfterSeconds —— `RateLimiterTest`
- 行为 2.2：分钟窗口跨过 60 秒边界后计数重置 —— `RateLimiterTest.minuteWindowResets`
- 行为 2.3：天窗口在 Asia/Shanghai 自然日 00:00 重置（非滚动 24h）—— `RateLimiterTest.dayWindowResetsAtMidnightShanghai`
- 行为 2.4：不同 key 计数互不影响 —— `RateLimiterTest.keysAreIsolated`
- 行为 2.5：过期窗口条目被清理，长时间运行无内存泄漏（条目数有界）—— `RateLimiterTest.expiredEntriesEvicted`
- 行为 2.6：时钟可注入（构造参数 Clock），测试可推进时间 —— 由上述测试共同体现

### 任务 3：管理员登录 + JWT 鉴权
- 行为 3.1：正确用户名 + 密码返回 code 0、token（HS256）与 expiresIn=604800 —— `AuthServiceTest.loginSuccess`
- 行为 3.2：用户名错误或密码错误均统一 40102（不区分）—— `AuthServiceTest.badCredentialsUnified`
- 行为 3.3：用户名/密码缺失或为空 → 40001 —— `LoginRequestTest`
- 行为 3.4：同 IP 第 6 次/分钟登录 → 42900；窗口过后恢复 —— `AuthServiceTest.loginRateLimited`
- 行为 3.5：签发的 token 校验为 Valid(username)；过期 token → 40101；格式错误/伪造 → 40100 —— `JwtTokenServiceTest`
- 行为 3.6：鉴权路由无 Authorization 头 → 40100；携带过期 → 40101；login/feedback 公开路由不受影响 —— 由 3.5 + 装配断言体现
- 行为 3.7：X-Forwarded-For 首 IP 优先作为限流 key，缺失回退 remoteHost —— `ClientIpResolverTest`

### 任务 4：反馈表 + 提交接口
- 行为 4.1：合法请求插入成功，返回 code 0 + feedbackId；服务端补全 status=PENDING、images=[]、createdAt=updatedAt（同一时钟值）—— `FeedbackServiceTest.submitSuccessFillsServerSideFields`
- 行为 4.2：校验矩阵逐条 40001：title 空/51 字、content 空/1001 字、category 非枚举、appName 空/51 字、appVersion 空/21 字、deviceId 空/65 字、deviceModel 101 字、osVersion 21 字、contact 101 字，message 指明字段 —— `FeedbackValidationTest`（参数化）
- 行为 4.3：同 deviceId 1 分钟内第 2 条 → 42900 —— `FeedbackServiceTest.submitRateLimitedPerMinute`
- 行为 4.4：同 deviceId 当天第 6 条 → 42900 —— `FeedbackServiceTest.submitRateLimitedPerDay`
- 行为 4.5：不同 deviceId 互不影响 —— `FeedbackServiceTest.rateLimitKeyedByDeviceId`
- 行为 4.6：FeedbackTable 列名/类型与 schema.sql 一一对应（表定义常量断言）—— `FeedbackTableTest`
- 行为 4.7：repository.insert 返回自增 id；findById 往返字段一致（含 snake_case→camelCase 映射）—— Repository 层 n/a（无 DB 单测），以表定义测试 + 编译 + 评审把关，此处显式标注

### 任务 5：反馈列表查询
- 行为 5.1：page 默认 1、pageSize 默认 20；page=0/负数、pageSize=0/101/负数 → 40001 —— `FeedbackListQueryParserTest.paginationBounds`
- 行为 5.2：category 非枚举成员（含逗号分隔中任一非法）→ 40001；合法多选去重保序解析 —— `FeedbackListQueryParserTest.categoryParsing`
- 行为 5.3：status 仅接受 0/1，其余 → 40001 —— `FeedbackListQueryParserTest.statusParsing`
- 行为 5.4：startDate/endDate 非 yyyy-MM-dd → 40001；endDate < startDate → 40001 —— `FeedbackListQueryParserTest.dateRangeValidation`
- 行为 5.5：日期区间语义为 [当日 00:00:00, 当日 23:59:59]（Asia/Shanghai）—— `FeedbackListQueryParserTest.dateRangeInclusiveBounds`
- 行为 5.6：keyword trim 后为空视为未传；>50 字 → 40001 —— `FeedbackListQueryParserTest.keywordHandling`
- 行为 5.7：service 组合筛选委托 filter 全字段（fake repository 断言收到的 filter）；返回 total=真实总数 + 当前页 list；page 超界 → 空 list + 正确 total —— `FeedbackServiceTest.listDelegatesFilterAndHandlesPageOverflow`
- 行为 5.8：排序固定 createdAt DESC（filter/repository 实现断言），不可配置 —— 随 5.7 断言 + 评审

### 任务 6：标记状态 + 删除
- 行为 6.1：status 传 0/1 之外（含非数字）→ 40001 —— `FeedbackServiceTest.updateStatusRejectsInvalidValue`
- 行为 6.2：id 存在 → 更新成功 data=null，updatedAt 刷新为当前时钟值，status 0↔1 双向 —— `FeedbackServiceTest.updateStatusTogglesAndRefreshesUpdatedAt`
- 行为 6.3：id 不存在 → 40401 —— `FeedbackServiceTest.updateStatusMissingIdThrows40401`
- 行为 6.4：id 非数字 → 40001 —— `FeedbackServiceTest.nonNumericIdRejected`
- 行为 6.5：删除存在 id → data=null；不存在 → 40401 —— `FeedbackServiceTest.deleteBehaves`

## 5. 测试策略

- 栈：kotlin-test + JUnit 5（DECISIONS.md #测试栈），手写 fake repository（强类型），Clock 注入。
- 覆盖目标：util 纯逻辑（限流器/时间/解析器/校验）100% 行覆盖；service 层 ≥90%；
  route 装配 / Repository（Exposed-JDBC）/ 资源文件 n/a（靠编译 + 评审 rubric）。
- 特殊测试点：限流窗口翻转（天窗口按上海自然日）、JWT 过期（注入时钟推进 7 天+）、
  校验边界值（50/51、1000/1001、100/101、20/21、64/65 字符）、失败路径全部有断言。
- 断言质量红线：无断言测试 / 恒真断言 / catch-all 一律 review fail。

## 6. PRD 外决策清单（用户 2026-11-04 已确认，可否决）

| # | 决策 | 理由 | 代码位置 |
|---|---|---|---|
| 1 | 未匹配路由返回 Ktor 默认 404（空体），不套统一响应体、不造新错误码 | PRD 错误码表无通用 404 码，造码即改契约 | plugin/StatusPages 装配 |
| 2 | 响应中可选字段为 null 时整个字段省略（explicitNulls=false） | 管理端少判空，与 images 恒空数组风格一致 | plugin/ContentNegotiation Json 配置 |
| 3 | 请求 JSON 含未知字段时忽略（ignoreUnknownKeys=true），不报 40001 | App 端升级加字段不打挂老服务 | 同上 |
| 4 | 登录限流 IP 取 X-Forwarded-For 首个，缺失回退 remoteHost | Nginx 前置下 remoteHost 全同 IP，限流失效 | util/ClientIpResolver |
| 5 | 限流天窗口 = Asia/Shanghai 自然日 00:00 重置（非滚动 24h） | 「每天 5 条」的用户直觉 | util/RateLimiter |
| 6 | DB 不可用不阻断启动（Hikari lazy），接口级 50000，恢复自愈 | 对齐 PRD §11 降级契约 | config/DatabaseConnect |

## 7. 契约影响面（红线 18 核对项）

本次为绿地项目，全部契约均为**新建**（无存量契约被修改）：接口路径与参数名（§2.3/§2.5）、
统一响应结构、错误码表、category/status 值域、时间格式与 camelCase、images 语义、JWT 契约。
涉及契约的 issue（#3 #4 #5 #6）在标题带 `contract` 前缀提醒 reviewer；任何 diff 触碰
上述值域/路径/码表即 review fail。

## 8. 状态协议

默认按 skill references/status-protocol.md 执行（claimed/compiled/tested/pr-open/rework-N、
blocked 即时上报、label 状态机主 agent 单写、review verdict 固定格式）。本项目无设备验证
里程碑（`verified` 不适用）。worktree 根：`E:\work\LauncehrBackend-wt`，命名 `wt-issue-N` / `wt-review-P`。
