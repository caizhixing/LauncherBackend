# LauncherBackend · 用户反馈系统后端

App 端匿名提交反馈、管理员登录筛选/检索/分页浏览并标记处理状态的后端服务。

- 技术栈：Kotlin 2.4 + Ktor 3.6（Netty）+ Exposed 1.6 + MySQL 8 + Koin + JWT
- 需求文档：`docs/prd/feedback-backend.md`（冻结接口、行为清单、决策记录）
- 决策日志：`DECISIONS.md`（需求变更前必查）
- 建表：`schema.sql`（空库手工执行一次）

## 本地构建

依赖已全部进入本地缓存，**所有 Gradle 命令加 `--offline`**（禁止联网下载）：

```bash
./gradlew --offline build        # 编译 + 单测
./gradlew --offline shadowJar    # fatJar → build/libs/feedback-backend-0.1.0-all.jar
```

Windows 用 `.\gradlew.bat --offline ...`；Gradle 用 `JAVA_HOME=E:\android_studio\jbr`（OpenJDK 25，编译目标 `jvmTarget=21`）。

## 本地启动（开发机）

前置：JDK 21+、MySQL 8（`schema.sql` 按 MySQL 8 编写）。本仓库已自带一个便携版 MySQL 8 实例（`.mysql/`，见下节）。

启动阶段**不校验数据库**（见 `DECISIONS.md #DB故障降级实现`），所以库没准备好时服务照样能起、登录接口也能用；
但提交 / 列表 / 标记 / 删除接口会在连接超时后统一返回 `50000`。

### 0. 本地 MySQL（便携实例，已初始化）

MySQL 8.0.28 解压在 `.mysql/mysql-8.0.28-winx64/`，数据目录 `.mysql/data/`，配置 `.mysql/my.ini`，
全部在 `.gitignore` 里（删掉 `.mysql/` 即彻底卸载，不注册 Windows 服务、不写注册表）。

```powershell
.\scripts\mysql-local.cmd -Action status   # 端口/进程/数据目录状态
.\scripts\mysql-local.cmd -Action start    # 启动 127.0.0.1:3306
.\scripts\mysql-local.cmd -Action stop     # 关闭
.\scripts\mysql-local.cmd -Action init     # 只在数据目录不存在时跑一次：初始化 + 设口令 + 建库 + 建表
```

> 本机默认执行策略会拒绝**未签名**的 `.ps1`（`Get-ExecutionPolicy` 报 `RemoteSigned`，但实际按需签名执行），
> 所以入口统一用 `.cmd` 包装（内部 `powershell -ExecutionPolicy Bypass -File ...`）。`.ps1` 仍可直接用，前提是
> 显式带 `-ExecutionPolicy Bypass`：`powershell -ExecutionPolicy Bypass -File .\scripts\mysql-local.ps1 -Action start`。

- 连接：`127.0.0.1:3306`，账号 `root`，口令 `123456`，库 `feedback`（表 `feedback` 已建好）
- 日志：`.mysql/mysqld.err`
- 客户端：`.mysql/mysql-8.0.28-winx64/bin/mysql.exe -uroot -p123456 feedback`

想换成自备的 MySQL / Docker，起好实例后执行一次建库 + `schema.sql` 即可（`run-local.ps1` 默认连 3306/root/123456，不一致就传 `-DbUrl/-DbUser/-DbPassword`）：

```sql
CREATE DATABASE IF NOT EXISTS feedback DEFAULT CHARACTER SET utf8mb4;
```

```powershell
cmd /c "mysql -uroot -p feedback < schema.sql"
```

```powershell
# 或用 Docker
docker run -d --name feedback-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=123456 mysql:8
```

### 1. 启动

```powershell
# 默认：管理员 admin / admin123，库 root@127.0.0.1:3306/feedback（口令 123456），端口 8080
.\scripts\run-local.cmd

# 换管理员口令 / 库账号 / 端口 / CORS
.\scripts\run-local.cmd -AdminPassword 'MyDevPwd1' -DbUser feedback -DbPassword <pwd> -Port 9090 -CorsOrigins http://localhost:5173
```

脚本做的事：fatJar 缺失时先跑 `.\gradlew.bat --offline shadowJar`；补齐下面 6 个必填环境变量；
探测数据库端口（连不上只告警）；前台启动 Netty，`Ctrl+C` 停止。
`JWT_SECRET` 未指定时每次启动随机生成 64 字符（重启后旧 token 失效，本地调试预期行为）。

### 2. 冒烟

```powershell
# 登录拿 token
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/login `
    -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
$token = $login.data.token

# 管理员列表（Bearer 鉴权）
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/feedback/list?page=1&pageSize=10' `
    -Headers @{ Authorization = "Bearer $token" }
```

App 端匿名提交（无需 token）：

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/feedback -ContentType 'application/json' -Body (@{
    title = '启动页闪退'; content = '打开应用后白屏 2 秒然后退出'
    category = 'BUG'; appName = 'Launcher+'; appVersion = '1.2.0'
    deviceId = 'local-dev-device-001'; deviceModel = 'Pixel 7'; osVersion = 'Android 15'
} | ConvertTo-Json)
```

> 同一 `deviceId` 限流：1 次/分钟 + 5 次/天（PRD §2.5）。连续调试换个 `deviceId` 即可。
> 提交成功返回 `{"code":0,"data":{"feedbackId":2}}`，标记状态/删除用这个 `feedbackId`。

### 3. 不用脚本的手工启动

```powershell
$env:ADMIN_USERNAME      = 'admin'
$env:ADMIN_PASSWORD_HASH = '<BCrypt cost≥12 哈希>'
$env:JWT_SECRET          = '<≥32 字符随机串>'
$env:DB_URL              = 'jdbc:mysql://127.0.0.1:3306/feedback?useSSL=false&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$env:DB_USER             = 'root'
$env:DB_PASSWORD         = '<MySQL 密码>'
$env:PORT                = '8080'

java -jar build\libs\feedback-backend-0.1.0-all.jar     # 已有 fatJar
.\gradlew.bat --offline run                             # 或者让 Gradle 编译并运行
```

环境变量优先级高于 `src/main/resources/application.conf`（该文件只放 `feedback.port`、`feedback.corsOrigins` 这类非敏感缺省值）。

### 必填环境变量

| 变量 | 必填 | 本地示例 | 说明 |
| --- | --- | --- | --- |
| `ADMIN_USERNAME` | ✅ | `admin` | 管理员登录名 |
| `ADMIN_PASSWORD_HASH` | ✅ | `$2a$12$...` | BCrypt 哈希，cost ≥ 12；不是明文口令 |
| `JWT_SECRET` | ✅ | 64 位随机串 | HS256 签名密钥，< 32 字符启动即失败 |
| `DB_URL` | ✅ | `jdbc:mysql://127.0.0.1:3306/feedback?useSSL=false&characterEncoding=utf8&allowPublicKeyRetrieval=true` | MySQL 8 建议带 `allowPublicKeyRetrieval=true` |
| `DB_USER` | ✅ | `root` | |
| `DB_PASSWORD` | ✅ | `123456` | 便携实例的 root 口令 | |
| `PORT` | ❌ | `8080` | 缺省取 `application.conf` |
| `CORS_ORIGINS` | ❌ | `http://localhost:5173,http://localhost:3000` | 逗号分隔；App 直连不需要 |

必填项缺失 → 启动 fail fast 并打印缺失清单（`AppConfig.load`）。

### 生成 / 更换 ADMIN_PASSWORD_HASH

```powershell
.\scripts\new-password-hash.cmd                       # 隐藏输入口令
.\scripts\new-password-hash.cmd -Password 'MyDevPwd1' # 或直接给明文（会进 shell 历史）
```

脚本复用 fatJar 里已打包的 bcrypt 库，在本机编译一个临时辅助类算哈希，不需另装工具。
手工写环境变量时，哈希里的 `$` 必须用**单引号**：`$env:ADMIN_PASSWORD_HASH = '$2a$12$...'`。

### 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 启动即退出，打印 `Missing required configuration variables` | 必填环境变量缺失 |
| 启动即退出，打印 `JWT_SECRET: must be at least 32 characters` | `JWT_SECRET` 太短 |
| 登录返回 `40100` | 明文口令与 `ADMIN_PASSWORD_HASH` 不匹配（哈希被 PowerShell 吞了 `$` 也会这样） |
| 列表/提交卡约 30 秒后返回 `50000` | 数据库连不上：启动不校验 DB，请求时才借连接（`DECISIONS.md #DB故障降级实现`）。先 `.\scripts\mysql-local.ps1 -Action start` |
| 重启电脑后接口全 50000 | 便携 MySQL 不是 Windows 服务，开机不自启；先 `-Action start` |
| 忘了数据库口令想重来 | 关掉实例后删掉 `.mysql\data`，再 `-Action init`（表会重建，数据清空） |
| 提交返回 `42900` | 同 `deviceId` 限流（1 次/分钟、5 次/天） |
| 提交返回 `40001` | 必填字段缺失/超长，`message` 会指明字段（如 `title: 必填`） |
| 端口被占用 | 换 `-Port 9090` 或 `$env:PORT` |
| `-Action start` 60 秒内没起来 | 看 `.mysql/mysqld.err` 最后一条 `[ERROR]`。已踩过：`[InnoDB] Unable to create temporary file inside "…\Temp"; errno: 13` —— 用户 TEMP 不可写，`my.ini` 现已把 `tmpdir` 指到 `.mysql/tmp`（老配置手工补 `tmpdir=E:/work/LauncehrBackend/.mysql/tmp`，或删掉 `my.ini` 让脚本重写） |
| `BindException: Address already in use: bind` | 端口被占：`netstat -ano \| findstr :8080` 拿 PID → `tasklist /FI "PID eq <pid>"` 确认（多为上一次没退出的后端），杀掉它或换 `-Port 9090`。脚本现在启动前就会拦下并给出提示 |
| 浏览器前端联调报 CORS | 传 `-CorsOrigins http://localhost:5173`（默认空 = 只允许非浏览器客户端） |
| PowerShell 5.1 里接口返回的中文是乱码 | 响应头是 `application/json`（无 charset），PS 5.1 的 `Invoke-RestMethod` 按 ISO-8859-1 解码；数据本身是正确的 UTF-8，换 `pwsh` 7 或用 `curl.exe` 看 |
| JDK 24+ 启动打印 `WARNING: A restricted method in java.lang.System` | 来自 netty/jansi 的原生访问告警，不影响运行 |
| 脚本报"未对文件进行数字签名" | 本机策略要求脚本签名（`Get-ExecutionPolicy` 显示 `RemoteSigned`，实际按需签名）。用 `.cmd` 包装（`mysql-local.cmd` / `run-local.cmd`），或 `powershell -ExecutionPolicy Bypass -File .\scripts\run-local.ps1`，或当前窗口 `Set-ExecutionPolicy -Scope Process Bypass -Force` |

## 生产部署

systemd / Nginx / HTTPS 由部署阶段处理（PRD M3，不进代码 issue）。
生产必须使用真实的 `ADMIN_PASSWORD_HASH`、`JWT_SECRET`、`DB_*`，禁止把真实值提交进仓库（`*.local.conf` 已 gitignore）；
不要把本机脚本的默认口令 `admin123` 带到服务器。
