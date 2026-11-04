# LauncherBackend · 用户反馈系统后端

App 端匿名提交反馈、管理员登录筛选/检索/分页浏览并标记处理状态的后端服务。

- 技术栈：Kotlin 2.4 + Ktor 3.6（Netty）+ Exposed 1.6 + MySQL 8 + Koin + JWT
- 需求文档：`docs/prd/feedback-backend.md`（冻结接口、行为清单、决策记录）
- 决策日志：`DECISIONS.md`（需求变更前必查）
- 建表：`schema.sql`（空库手工执行一次）

## 本地构建

```bash
./gradlew --offline build        # 编译 + 单测（依赖已全部进入本地缓存）
./gradlew --offline shadowJar    # fatJar
```

## 运行前必填环境变量

`ADMIN_USERNAME` / `ADMIN_PASSWORD_HASH`（BCrypt, cost≥12）/ `JWT_SECRET`（≥32 字符）/
`DB_URL` / `DB_USER` / `DB_PASSWORD`；可选 `PORT`（默认 8080）、`CORS_ORIGINS`。
缺失时启动 fail fast 并打印缺失清单。生产环境禁止将真实值提交进仓库。
