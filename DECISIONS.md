# DECISIONS.md — 决策日志（按业务问题索引，单写者：主 agent）

> 需求变更前先查本文件。每条记：日期、背景、选定方案、被否掉的备选。

## #Web 管理端前端交付（multi-agent-pr-flow，2026-11-05）

- 交付：4 issue（#13-#16）3 波（W1{契约+http+守卫} → W2{登录页, 列表读路径} 并行 → W3{写路径+详情}），
  4 PR（#17/#18/#19/#20）全部 review pass 后按序合并；集成门禁 typecheck/build/test 三绿
  （128/128），后端文件零改动（git diff 核对 src/ 与构建文件 = 0）。npm 依赖 173 包锁定于
  web/package-lock.json（引导期一次联网安装，此后 worktree 全程 `npm install --offline`）。
- 契约修订 1 次（PRD 变更记录 2026-11-05-1）：行为 1.8「--el-* 变量覆盖表格密度」在 EP 2.13
  无官方变量可选（worker grep theme-chalk 证实），改为 main.ts `app.use(ElementPlus, { size: 'small' })`
  官方全局尺寸，tokens.css 仅留中文字体栈。用户拍板方案 A；评审循环按修订后契约重新计数。
- 契约升级 1 次（PR#18 r1，contract_touched=yes）：worker 把冻结签名 login(req) 改为 login(http, req)
  求测试注入。用户裁决方案 A：恢复冻结签名，PRD 不动；模式对齐 issue-15 的 createFeedbackApi 工厂注入。
- worker 实现层决策（随终验收追认）：无 token 短路在 onUnauthorized 后补抛 ApiError(40100)（防调用方
  误判成功）；routeGuard returnUrl 仅转义 &/#；login data=null 归一 ApiError(50000)；**表格与详情抽屉
  经 store 模块「当前实例登记」共享同一实例**（视图文件不在 #16 触碰范围所致；集成期可零行为损失
  换成显式 prop 传递，二期顺手做）；web/scripts/with-temp.mjs 重定向 TEMP 规避本机 esbuild 构建坑。
- 流程指标：合并率 4/4；返工 PR17×2（含契约修订重置）、PR18×1、PR19×1、PR20×0；blocked 0；
  空断言打回 0（assertion_quality 全 ok）；设计内人介入 3 次（PRD 定稿、密度契约修订、PR18 契约裁决）。
- 环境：git 代理实配为 127.0.0.1:7900（7897 已失效，mihomo 实际监听口）；mihomo 另有 7874 为
  内部 API 口不可作代理。

## #Web 管理端技术栈选型（2026-11-05，用户确认）

- 背景：后端 v1 已交付，但管理端 Web 前端是一期 out-of-scope（`docs/prd/feedback-backend.md` §1），
  管理员目前只能用 `docs/api.http` 手工查反馈，无法日常使用。
- 选定：**独立 SPA —— Vite + Vue 3 + TypeScript + Element Plus**，源码放同仓 `web/`（后端 Gradle 侧零改动）；
  开发期 Vite 代理 `/api` → `127.0.0.1:8080`，生产由 Nginx 同源反代 `web/dist`，**不启用 CORS**。
- 备选否决：Ktor SSR + kotlinx.html + HTMX（零构建链、单产物，但表格/日期区间/多选/对话框全要手写，
  二期加图片、回复、统计时会退化成自造轮子）；Compose Multiplatform Web/Wasm（复用 Compose 技能很诱人，
  但后台是长表单 + 长列表 + 中文 IME + 大段文本选择，DOM 生态差距与 Wasm 工具链成本不划算）；
  Next/Nuxt SSR（无 SEO/首屏诉求，白多一个 Node 进程要运维）；前端打进 fatJar（前端改一行要重打包后端，
  静态缓存头自管）。
- 关键实测事实：`install(CORS)` 当前只 `allowHeader(ContentType)`，而 Ktor 默认放行头只有
  `Accept`/`Accept-Language`/`Content-Language`，**`Authorization` 不在其中** → 浏览器跨域带 Bearer 会在
  预检被拒。同源部署（Vite proxy / Nginx 反代）即完全规避，无需改后端；若将来必须跨域，才补
  `allowHeader(HttpHeaders.Authorization)`（非契约变更）。
- 影响：`npm install` 需一次性联网（同 #依赖一次性联网），之后离线可构建；本机 node v24.18.0 /
  npm 11.16.0 已就绪。`web/` 与 `node_modules/` 需补进 `.gitignore`（P0 落地时处理）。
- 已确认（2026-11-05）：`docs/prd/feedback-web-admin.md` 转 **frozen**；§6 决策 D1–D18 用户整批按推荐
  确认（要点：token 用 localStorage 7 天免登；筛选即时生效 + 300ms 防抖 + 回第 1 页；deviceId/contact
  详情全量 + 复制、列表不显示；删除普通二次确认；状态文案「待处理/已完成」并同步修正 `docs/api.http`
  注释口径；vitest 仅纯函数/store 层；worker 不联调、阶段 5 统一手工冒烟；模型沿用本文件 #模型选型；
  URL 非法参数回默认值）。
- 前端交付走 multi-agent-pr-flow：主 agent 在 main 提交 web/ 工程引导（构建系统 + lock + 一次联网
  `npm install`，业务代码零行），业务代码 4 issue 3 波 fan-out（W1{契约+http+守卫} →
  W2{登录页, 列表读路径} → W3{写路径+详情}）；前端验证基线 = `typecheck`+`build`+`test`，不跑 Gradle；
  全部 issue 禁碰 `src/main/kotlin/` 与后端构建文件。

## #本地开发数据库（便携 MySQL，2026-11-04）

- 日期：2026-11-04
- 背景：本机无 MySQL 实例（3306 无监听、无 Program Files\MySQL）、无管理员权限；后端接口依赖
  MySQL 8（`schema.sql` 的 JSON 列 + utf8mb4），无库时只能验证到「登录可用、反馈接口 50000」。
- 选定：MySQL 8.0.28 `winx64.zip` 解压到仓库 `.mysql/mysql-8.0.28-winx64/`，数据目录 `.mysql/data/`，
  配置 `.mysql/my.ini`（port 3306、bind 127.0.0.1、utf8mb4、`default-time-zone='+08:00'`、mysqlx 关闭）；
  不注册 Windows 服务、不写注册表，`scripts/mysql-local.ps1` 提供 init/start/stop/status；
  root 口令 `123456`、库 `feedback`，仅本机开发用。
- 被否掉的备选：`choco install mysql` / `winget install Oracle.MySQL`（需管理员，且往 ProgramData 写系统级文件）；
  Docker Desktop（本机未安装，需 WSL，体积更大）；MariaDB 便携版（JSON 语义与 PRD「MySQL 8」契约不符）。
- 影响：`.mysql/` 已 gitignore，删目录即卸载；实例约 1.0 GB；**不是服务，开机不自启**，重启电脑后需
  `.\scripts\mysql-local.ps1 -Action start`；`run-local.ps1` 的 `DbPassword` 默认值随之为 `123456`。
- 附注（维护坑）：PowerShell 5.1 下脚本必须以 **UTF-8 BOM** 保存，否则中文串被按 GBK 解析导致语法错误；
  `Get-NetTCPConnection` 在受限环境会漏报在监听的端口，端口探测改用 `TcpClient`；`mysqld` 由
  `Win32_Process.Create` 启动以脱离父 shell（沙箱内该调用被拒时退回 `Start-Process`）。
- 附注（执行策略）：本机默认执行策略**拒绝未签名的 .ps1**（`Get-ExecutionPolicy`/注册表均报
  `RemoteSigned`，但连 C: 临时目录里新建的脚本都报"未对文件进行数字签名"，`-ExecutionPolicy Bypass`
  可放行），根因疑在机器级策略/CI 层，未定位到可改的 scope。因此三支脚本各配一个 `.cmd` 包装
  （`scripts/*.cmd`，内部 `powershell -NoProfile -ExecutionPolicy Bypass -File`），不改系统策略。
  `.cmd` 内注释必须纯 ASCII：cmd 按 OEM 代码页读文件，UTF-8 中文注释会被当成命令执行。
- 附注（TEMP 不可写）：本机用户 TEMP（`C:\Users\…\AppData\Local\Temp`）对某些进程不可写，
  mysqld 首次启动曾因此失败：`[InnoDB] Unable to create temporary file inside "…\Temp"; errno: 13`
  → `my.ini` 增加 `tmpdir=E:/work/LauncehrBackend/.mysql/tmp`（脚本在 init/start 时自动建目录），
  不再依赖系统 TEMP。同类报错优先查 `mysqld.err` 最后一条 `[ERROR]`，别只看"60 秒未监听"。

## #验收与 PRD 外决策追认（阶段 5 终门）

- 日期：2026-11-04（用户拍板：验收通过）
- 六 PR 全部 MERGED（#8/#7/#10/#9/#12/#11），main 集成门禁 `--offline clean build` 全绿
  （136 测试 0 失败，shadowJar 43.8 MB），里程碑 feedback-backend-v1 关闭。
- 用户追认 13 条流程中产生的 PRD 外决策（业务语义，代码位置以各 PR verdict 评论为准）：
  1) 校验先于限流，缺字段请求不消耗 IP 配额（40001>42900）；
  2) 请求体反序列化失败统一 40001 而非 50000；
  3) 空 query 语义：page/pageSize/category/status/日期传空串=40001，keyword 空串或全空白=视为未传；
  4) 重复同名 query 参数取首个值（Ktor Parameters 语义）；
  5) status 仅接受字面量 "0"/"1"，格式偏差一律 40001 不静默纠正；
  6) 日期 STRICT 解析（2026-02-29 等不存在日期直接 40001）；
  7) keyword 中 %/_ 按 SQL LIKE 通配符解释（不转义；如需按字面量匹配另开需求）；
  8) 错误文案：id: 必须为数字 / status: 必填 / status: 仅允许 0 或 1 / 反馈不存在；
  9) 状态接口缺字段（{} 或 null）归一 40001（可空 DTO 建模）；
  10) id 用 toLongOrNull：不 trim、负数合法（走 40401）、全角数字按其值解析；
  11) id 与 status 同时非法时先报 id 错误；
  12) 成功响应固定 {"code":0,"message":"success","data":null}（JsonNull 保 data 键）；
  13) 鉴权口径：有效签名 + subject 非空即放行，不比对管理员用户名。
- 附注：仓库 15:58 起新装 identity-guard pre-commit 钩子（用户侧），worker 按钩子指引以
  `-c user.name/email` 一次性身份提交，全局配置未动。

## #流程复盘（阶段 6，2026-11-04）

- 交付：6/6 PR 合并（合并率 100%），reviewer 全部 testbird/k3.1-pro 独立 worktree 亲跑复核。
- 评审/返工：9 次 reviewer 执行、3 轮返工（PR#8 r1 覆盖率/边界/启动断言；PR#9 r1 契约对齐
  ×3；PR#10 r1 反序列化分支测试），全部 round-2 pass；无一 PR 触发 3 轮冻结。
- 契约升级 1 次（PR#9 r1 fail + contract_touched=yes）按协议升级用户，用户批准按评审返工。
- agent 故障 2 起：W1 评审首轮 provider 寻址错误（skill 文档机型映射失效，双 reviewer 空转，
  改 testbird/k3.1-pro 后恢复）；W2 worker-#3 首派零副作用失败，重派成功。GitHub 时间线
  均为事实源，无脏现场。
- 人介入门（设计内）：模型选型、PRD 冻结、PR#9 契约升级、终验收，共 4 次；无计划外人介。
- 经验沉淀：中文 gh 内容必须 UTF-8 --body-file；Get-Content 需 -Encoding UTF8（本机默认
  GBK）；--jq 表达式会被 PowerShell 拆词，改 ConvertFrom-Json；跨波合并的 Application.kt
  装配冲突由主 agent 解决并保留双侧语义 + worktree 内全量测试验证。

## #PRD 冻结与拆分

- 日期：2026-11-04（用户确认，设计内门）
- 选定：`docs/prd/feedback-backend.md` frozen；拆分 6 issue 3 波
  （W1{骨架,限流器} → W2{登录JWT,反馈表+提交} → W3{列表,状态+删除}），合并顺序 = 序号。
  6 条 PRD 外决策（404 空体 / null 字段省略 / 未知字段忽略 / XFF 取 IP / 自然日窗口 /
  Hikari lazy）经用户确认生效。

## #流程豁免（multi-agent-pr-flow 执行期）

- 日期：2026-11-04（用户 2026-10-30 授权，随 skill 生效）
- 背景：multi-agent-pr-flow 需要主 agent/worker 操作 git（worktree/branch/push/PR/merge）。
- 选定：红线 15（git 操作）、红线 16（ADB/自动化验证）在本项目（LauncherBackend 仓库与全部
  worktree）执行 skill 期间放开。本项目为纯后端，无 ADB 场景。
- 备选否决：逐次请求授权（会打断 fan-out 流水线）。

## #模型选型（阶段 0 选型门）

- 日期：2026-11-04
- 背景：skill 要求用户亲自选定三角色模型，worker/reviewer 不得同家族。
- 选定：主 agent = glm-5.3（会话模型）；worker = glm-5.3（GLM 家族，跟随会话=子代理缺省模型）；
  reviewer = k3 家族最高档。家族组合 GLM × k3 满足跨模型约束。
- **本机寻址落地（2026-11-04 实测）**：用户选项沿用了 skill 文档标识 `new-provider/k3$max`（旧机
  Tapkit 配置）；本机 DSH profile 实际路由为 provider=`testbird`、model=`k3.1-pro`（本机唯一 k3
  家族模型，且未暴露推理档参数，以其默认最高档执行）。subagent 调用格式：
  `{provider:'testbird', model:'k3.1-pro'}`；worker 不传覆盖（缺省=会话 glm-5.3）。
- 备选否决：deepseek-v4-pro 作子 agent（skill 记录 provider 硬拒 thinking 限制，且与本机默认
  deepseek-flash 同家族风险）；worker/reviewer 同用 glm-5.3（同家族，打断错误相关性目的失效）。

## #依赖一次性联网

- 日期：2026-11-04（用户当场授权）
- 背景：新项目依赖（Ktor/Exposed/Koin/MySQL 驱动等）本地缓存全缺，`--offline` 首次构建不可能。
- 选定：仅引导阶段联网拉取全部依赖进本地缓存并锁定版本；此后所有编译/单测严格 `--offline`。
- 备选否决：全程禁网（流程在编译门禁处 blocked，无意义）；长期放开（违反离线红线）。

## #构建环境锁定

- 日期：2026-11-04
- 背景：空仓库需自选构建链；本机仅缓存 Gradle 8.13/9.5.0/9.6.0/9.7.1/9.8.2，JDK 仅
  Android Studio JBR 25。
- 选定：Gradle 9.8.2（wrapper 锁定）+ JBR 25 运行 + `jvmTarget=21` +
  `org.gradle.java.installations.auto-download=false`。
- 备选否决：Gradle 8.13（无法在 JDK 25 上运行的兼容风险）；自带 toolchain 下载（禁网）。

## #技术版本锁定（libs.versions.toml）

- 日期：2026-11-04（Maven Central / plugins.gradle.org 元数据实查）
- 选定：Kotlin 2.4.20、Ktor 3.6.1、Exposed 1.6.0、Koin 4.2.2、Shadow 9.6.1、
  HikariCP 7.1.0、mysql-connector-j 26.10.0、logback 1.6.6、kotlinx-serialization-json 1.12.0、
  bcrypt 0.10.2（PRD 指定 ≥0.10.2）、JUnit 5.14.4。
- 备选否决：Kotlin 2.5.0-Beta2 / 各 RC（非稳定）；JUnit 6.1.3（kotlin-test 与 JUnit 6 互操作
  未经验证，求稳）。

## #测试栈

- 日期：2026-11-04
- 背景：skill 缺省测试栈（JUnit4+Truth+Mockito+coroutines-test）是 Android 项目的；
  本项目为绿地后端，且单测必须 `--offline` 可跑、无 MySQL。
- 选定：kotlin-test + JUnit 5；跨边界依赖用手写 fake（强类型），不引 Mockito；
  不引 ktor-server-test-host / H2——契约逻辑全部抽为纯函数或 service+fake 可测。
  Repository（Exposed/JDBC）层单测 n/a，靠编译 + 评审 rubric 把关。
- 备选否决：H2 内存库跑 Repository 集成测（新增依赖、SQL 方言差异引入假绿）；
  ktor test-host 路由测试（重、离线风险、收益低）。

## #空仓库引导方式

- 日期：2026-11-04
- 背景：GitHub 远程为空仓库，worktree/branch 需要基线提交。
- 选定：主 agent 直接在 main 提交 bootstrap（构建系统 + 版本锁定 + PRD/DECISIONS/AGENTS/
  schema.sql + 依赖冒烟测试），业务代码全部走 issue fan-out，主 agent 不写业务逻辑。
- 备选否决：把骨架也交给 worker（空仓库无法建 worktree，鸡生蛋问题）。

## #包名与项目结构

- 日期：2026-11-04
- 选定：包名 `com.caizhixing.feedback`（对齐仓库 owner）；单模块；
  目录按 PRD 第 9 节（config/plugin/route/service/repository/model/util）。
- 备选否决：`com.launcher.feedback`（与 App 端 launcher 概念混淆）。

## #限流语义

- 日期：2026-11-04
- 背景：PRD 只给阈值（提交 1/分 + 5/天 per deviceId；登录 5/分 per IP），窗口语义未定义。
- 选定：内存固定窗口计数器；分钟窗口按 epoch 分钟对齐，天窗口按 Asia/Shanghai 自然日 00:00
  重置（用户直觉的「每天」）；限流结果用 sealed 类型（Allowed/Denied），不用 Boolean。
- 备选否决：滚动窗口（实现复杂、内存清理成本高，单机场景收益低）。

## #登录限流取 IP 方式

- 日期：2026-11-04
- 背景：PRD 说 HTTPS 由 Nginx 终结，但未说如何取客户端 IP。
- 选定：优先 `X-Forwarded-For` 首个 IP，缺失时回退 `remoteHost`。
- 备选否决：只信 remoteHost（Nginx 前置时全部请求同 IP，限流失效）。

## #DB 故障降级实现

- 日期：2026-11-04
- 背景：PRD §11 要求 MySQL 挂时登录仍可用、其余 50000、进程不崩、恢复自愈。
- 选定：Hikari `initializationFailTimeout = -1`（池启动不试连，借连接时才暴露失败），
  接口层统一 50000；登录不查库（PRD 已定），天然可用。
- 备选否决：启动时试连失败即退出（违反 §11）；健康检查线程（超出范围）。

## #仓库可见性

- 日期：2026-11-04（用户确认）
- 选定：保持 PUBLIC（新项目无用户数据，密钥全走环境变量不入库）。
- 备选否决：改 PRIVATE（skill 隐私建议，用户选择保持公开）。

## #worktree 根目录

- 日期：2026-11-04（用户确认）
- 选定：`E:\work\LauncehrBackend-wt`（与主 checkout 同级隔离），命名 `wt-issue-N` / `wt-review-P`。
- 备选否决：仓库内 `wt/`（污染主 checkout 状态）。
