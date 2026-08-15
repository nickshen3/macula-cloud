# Macula Cloud 从零启动指南

> 适用版本：6.0.1（commit 基线含 2026-08 全链路修复）
> 目标：干净环境 30 分钟内完成部署并登录成功。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 21 | `java -version` 确认 |
| Maven | 3.9+ | 本地仓库默认路径需可写 |
| Node.js | 22.x | 前端构建 |
| Docker | 24+ | 含 compose 插件 |

**端口占用检查**（全部需空闲）：`3306` MySQL、`8848/9848` Nacos、`6379` Redis、`9000` Gateway、`9010` IAM、`9081` System、`5900` 前端。

---

## 2. 基础设施（一键）

```bash
cd deploy
docker compose up -d
```

首次启动 MySQL 自动导入全部 SQL（utf8mb4，库：`macula-system`/`xxl_job`；tinyid 已随 P2-2 下线）。

验证：

```bash
docker exec macula-mysql mysql -u root -e "SELECT nickname FROM \`macula-system\`.sys_user WHERE username='admin'"
# 期望输出：系统管理员4（若乱码说明导入了错误字符集版本）
curl http://localhost:8848/nacos/v1/console/health/readiness   # 期望 OK
```

Redis：compose 中默认注释。宿主机已有 Redis（6379 无密码）可直接复用；没有则取消 `deploy/docker-compose.yml` 中 redis 段注释。

> 库命名说明：`macula-system` 等连字符库名在 SQL 语句中**必须反引号包裹**，手工执行 SQL 时注意。

---

## 3. 后端构建与启动

```bash
# 构建全部模块（依赖锁定版 macula-boot-parent:6.0.1-20260610.013910-7，需首次联网解析）
mvn clean package -DskipTests=true -Plocal -T 4
```

启动顺序：IAM → System → Gateway（无严格依赖，同时启动亦可）。

**Windows（Git Bash）推荐用 cmd 分离启动**，进程才不会随 shell 退出：

```bash
cmd.exe /c "start /b java -jar macula-cloud-iam/target/macula-cloud-iam-6.0.1-SNAPSHOT.jar > %TEMP%/macula-iam.log 2>&1"
cmd.exe /c "start /b java -jar macula-cloud-system/target/macula-cloud-system-6.0.1-SNAPSHOT.jar > %TEMP%/macula-system.log 2>&1"
cmd.exe /c "start /b java -jar macula-cloud-gateway/target/macula-cloud-gateway-6.0.1-SNAPSHOT.jar > %TEMP%/macula-gateway.log 2>&1"
```

验证（依次等待 Started 日志，约 20s）：

```bash
curl -s -X POST "http://localhost:9010/oauth2/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "e4da4a32-592b-46f0-ae1d-784310e88423:secret" \
  -d "username=admin&password=admin&grant_type=password&scope=message.read message.write userinfo" | head -c 100
# 期望返回含 "access_token"
```

---

## 4. 前端启动

```bash
cd macula-cloud-admin
npm install --legacy-peer-deps --ignore-scripts
node node_modules/esbuild/install.js    # Windows 下 postinstall 不自动执行，需手动
node node_modules/vite/bin/vite.js      # 不用 npm run dev（cmd 找不到 vite）
```

访问 **http://localhost:5900**，账号 `admin / admin`。

---

## 5. Windows 已知坑速查

| 症状 | 原因 | 处理 |
|------|------|------|
| bash 后台 java 随终端退出而死 | 进程组信号 | 用上文 `cmd.exe /c start /b` 方式 |
| `mvn package` 报 rename 失败 | 旧 java 进程占用 jar | `taskkill //F //IM java.exe` 后重试 |
| npm postinstall 报 'node' is not recognized | cmd.exe PATH 问题 | 手动执行 esbuild install（见上文） |
| 前端样式报 `Undefined function: color.channel` | sass < 1.79 | package.json 已锁 1.102.0，勿降级 |
| 登录返回 server_error | 见 IMPROVEMENT-PLAN.md 历史问题清单 | 确认使用修复后代码基线 |

---

## 6. 服务停止

```bash
taskkill //F //IM java.exe          # 后端（Windows）
cd deploy && docker compose stop   # 基础设施（down 加 -v 会清库，慎用）
```
