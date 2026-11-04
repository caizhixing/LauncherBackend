# DECISIONS.md — 决策日志（按业务问题索引，单写者：主 agent）

> 需求变更前先查本文件。每条记：日期、背景、选定方案、被否掉的备选。

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
