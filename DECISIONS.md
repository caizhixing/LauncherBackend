# DECISIONS.md — 决策日志（按业务问题索引，单写者：主 agent）

> 需求变更前先查本文件。每条记：日期、背景、选定方案、被否掉的备选。

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
- 选定：主 agent = glm-5.3（会话模型）；worker = glm-5.3（GLM 家族，跟随会话）；
  reviewer = `new-provider/k3$max`（k3 家族，最高推理档，2026-11-01 实测可作子 agent）。
  家族组合 GLM × k3 满足跨模型约束。
- 备选否决：deepseek-v4-pro 作子 agent（provider 硬拒 thinking 回传限制）；
  worker/reviewer 同用 glm-5.3（同家族，打断错误相关性目的失效）。

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
