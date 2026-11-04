# 项目级 AGENTS.md — feedback-backend（LauncherBackend）

本文件承载项目事实，优先级：用户当前指令 > 本文件 > `~/.agents/AGENTS.md`（全局）。

## 项目定位

用户反馈系统后端（PRD：`docs/prd/feedback-backend.md`）。绿地 Kotlin JVM 单模块项目，
无存量区/新区之分，全部代码按全局新区规范执行；无补丁标记需求。

## 构建事实

- **Gradle**: 9.8.2（wrapper 已锁定，本机已缓存；禁止换版本）
- **JDK**: 运行 Gradle 用 `JAVA_HOME=E:\android_studio\jbr`（OpenJDK 25）；编译目标 `jvmTarget=21`；
  `org.gradle.java.installations.auto-download=false`（gradle.properties 已锁）
- **命令**: 一律加 `--offline`。最小任务集：
  - 编译：`./gradlew --offline compileKotlin`
  - 单测：`./gradlew --offline test`
  - 集成门禁：`./gradlew --offline clean build`（含 shadowJar）
- 依赖版本全部锁定在 `gradle/libs.versions.toml`，禁止引入 toml 之外的新依赖（红线 1）
- **Exposed 1.6 包结构**（与旧文档不同，写码勿用旧包名）：核心 DSL 在
  `org.jetbrains.exposed.v1.core`，JDBC/Database/事务在 `org.jetbrains.exposed.v1.jdbc(.transactions)`，
  java-time 在 `org.jetbrains.exposed.v1.javatime`
- 依赖缓存已就绪：`--offline` 构建已验证可用；如需联网解析（正常流程不会），Gradle JVM 必须加
  `-Djava.net.preferIPv4Stack=true`（否则 TLS 握手被间歇掐断）
- 测试栈：kotlin-test + JUnit 5（5.14.4），不引入 Mockito / ktor-server-test-host / H2

## 模块地图

```
src/main/kotlin/com/caizhixing/feedback/
├── Application.kt        # 启动入口 + module 装配
├── config/               # AppConfig：环境变量加载与启动 fail fast 校验
├── plugin/               # Ktor 插件装配（CORS/ContentNegotiation/StatusPages/Auth/CallLogging）
├── route/                # 参数解析与响应封装，不含业务逻辑（校验抽纯函数/下沉 service）
├── service/              # 业务逻辑、字段校验、限流判断
├── repository/           # Exposed 访问，唯一数据入口（suspend + newSuspendedTransaction）
├── model/dto/            # 请求/响应模型（kotlinx.serialization）
├── model/table/          # Exposed 表定义
└── util/                 # 限流器、时间工具
```

依赖方向：route → service → repository，禁止跨层直调。

## 契约清单（红线 18，改动必须显式确认）

以 `docs/prd/feedback-backend.md` 第 2 节冻结接口为唯一契约源，含：

1. 接口路径与请求/响应参数名（PRD 第 6 节全部）
2. 统一响应结构 `code / message / data`
3. 错误码表（40001/40100/40101/40102/40401/42900/50000）
4. `category` 枚举值域（BUG/EXPERIENCE/SUGGESTION/OTHER）、`status` 值域（0/1）
5. 时间格式 `yyyy-MM-dd HH:mm:ss`（Asia/Shanghai）与 JSON camelCase
6. `images` 字段语义（一期恒空数组）
7. JWT 契约（HS256、7 天、Bearer）

## 决策

- 一切业务/技术决策先查 `DECISIONS.md`；PRD 未覆盖且影响用户可见行为的决策不得自行拍板，
  记入 PRD 第 6 节决策清单上报。

## 验证

- B 级改动（全部业务代码）：`--offline` 编译 + 单测必跑；无 Android 变体任务名。
- 禁止模拟器/ADB/UI 自动化（本项目无 App 界面，天然不涉及）。
