# P3-2 OAuth2 授权码 + PKCE 迁移方案（Web 端）

> 状态：**已实施并验证**（2026-08-15，评审通过：路线 A / 直接切换 / 开销可接受）｜ 关联：IMPROVEMENT-PLAN.md P3-2、P2-3（BFF）

> **实施结果**：阶段 1–4 一次完成（无存量依赖，取消灰度双轨）；浏览器 E2E 与 OAuth2AuthCodeIT（3 用例）全绿。
> 关键实现差异见文末「实施补记」；password grant 按时间表进入弃用期（T+3 月禁用）。

## 1. 背景与动机

- **OAuth 2.1 方向**：password grant 在 OAuth 2.1 草案中已移除；Spring Authorization Server 官方对 password grant 无内置支持（本项目为自研 Provider），长期维护成本上升。
- **现状暴露面**（P2-3 治理后）：前端产物已无 client_secret，但 BFF `/login/token` 仍接受 `username/password` 直传——密码经手我们自己的代理层，浏览器插件/XSS 仍可打该端点（限流已兜底 P0-5）。
- **迁移收益**：密码不再经过任何自定义端点（只在 IAM 官方登录表单提交一次）；token 不进浏览器 JS；彻底对齐官方 SAS 演进路线。

## 2. 现状盘点（2026-08-15 实测）

| 项 | 状态 | 证据 |
|----|------|------|
| `/oauth2/authorize` + PKCE 参数 | ✅ 就绪 | `code_challenge=S256` 请求 302 到登录页（未登录先认证） |
| client 表授权码能力 | ✅ | `authorization_grant_types` 含 `authorization_code`；`require_proof_key` 字段可强制 PKCE |
| 登录页/consent 页/错误页 | ✅ | `templates/login.html`、`AuthorizationConsentController` |
| CORS | ✅ | IAM `CorsFilter`（需按阶段 1 白名单化） |
| BFF 代理 | ✅ 雏形 | `/login/token`（P2-3，password 专用，需扩展授权码回调） |
| 前端登录流程 | ⚠️ 单一 | `passwordForm.vue` 直连 BFF password 代理 |

## 3. 目标架构（推荐路线 A：BFF + 授权码 + PKCE）

```
浏览器 ──(1) GET /auth/authorize ──> 网关(9000) ──> IAM BFF
                                        │
浏览器 <──(2) 302 SAS /oauth2/authorize + state/verifier(Redis) ──┘
   │
浏览器 ──(3) IAM 登录表单(用户输密码，官方链路) ──> SAS
   │
浏览器 <──(4) 302 redirect_uri = 网关 /auth/callback?code=...&state=...
   │
浏览器 ──(5) GET /auth/callback ──> IAM BFF：校验 state、code+verifier 换 token
   │                                token 存服务端(Redis, sessionId 绑定)
浏览器 <──(6) Set-Cookie: SESSION(HttpOnly) + 302 前端首页
   │
浏览器 ──(7) 业务请求带 SESSION ──> 网关：session→token 注入上游
```

**为何不选 SPA 公共客户端（路线 B）**：token 进浏览器 JS（XSS 可窃）；刷新 token 落地前端；与 P2-3 的 BFF 投资方向相悖。
**为何不选维持现状（路线 C）**：密码经手自定义端点的结构性风险仍在；OAuth 2.1 合规缺口。

## 4. 实施步骤

| 阶段 | 内容 | 验收 | 工作量 |
|------|------|------|--------|
| 1 准备 | ① CORS 白名单（前端域名/env）② 授权页品牌化（logo/文案）③ client 表新增 web 端记录：`authorization_code+refresh_token`、`require_proof_key=1`、redirect_uri=`{gateway}/auth/callback` | authorize 全流程手工可跑通 | 1 人日 |
| 2 BFF 扩展 | IAM 新增 `/auth/authorize`（生成 state+code_verifier 存 Redis TTL 5min，302 到 SAS）与 `/auth/callback`（state 校验、code 换 token、用户态存 Redis、下发 HttpOnly SESSION cookie）；登出端点 `/auth/logout`（吊销 token+清 session） | curl 全流程：authorize→callback→SESSION 换 users/me | 3 人日 |
| 3 网关适配 | 网关 GlobalFilter：SESSION cookie → Authorization 注入上游；无有效 session 401→前端跳授权码流程 | 浏览器无 JS token 也能用全部功能 | 2 人日 |
| 4 前端切换 | 登录页改为"跳转登录"按钮（GET /auth/authorize）；移除账密表单与 `/login/token` 调用；401 拦截器改跳授权；feature flag（env）双轨保留 | flag 两态均可登录使用 | 2 人日 |
| 5 灰度与收尾 | 灰度 2 周（按用户/百分比）→ 默认授权码 → **password grant 退出时间表**：T+3 月默认禁用（client 表移除 password）、T+6 月删除 Provider 代码 | 时间表写入本文件并跟踪 | 1 人日 |

**受信第一方例外**：运维 CLI/内部脚本可继续用 password grant 至 T+6 月，期间迁移到 client_credentials。

## 5. 风险与缓解

| 风险 | 缓解 |
|------|------|
| BFF 引入服务端 session（Redis 强依赖升级） | 复用现有 Redis；session TTL 与 refresh_token 对齐；Redis HA 按 P1-5 方案先行 |
| 回调跨域/cookie 策略（SameSite） | 统一经网关域名代理（同源），IAM 只接受网关回调 |
| 双轨期登录状态混乱 | flag 按 localStorage 一次性切换；两轨 token 格式一致（均 SAS 签发） |
| weapp/sms 登录通道 | 不在本次范围（移动端后续独立评估 OAuth 2.1 for Native Apps） |

## 6. 验收标准（DoD）

- [ ] 浏览器开发者工具 Sources/Application 面板 grep 不到 access_token/refresh_token
- [ ] 前端产物与代码中无 client_secret、无用户密码处理逻辑
- [ ] 授权码全流程（登录/consent/回调/刷新/登出）E2E 测试入库（扩展 OAuth2PasswordGrantIT → OAuth2AuthCodeIT）
- [ ] password grant 按时间表标记弃用（client 表与 Provider @Deprecated）

## 7. 评审要点（给评审人）

1. 路线 A（BFF）是否认可？有无更倾向 B/C 的场景？
2. password grant 退出时间表（T+3/T+6 月）节奏是否合适？
3. 阶段 3 网关 SESSION→token 注入的额外延迟是否可接受（预计 <5ms/req）？


---

## 8. 实施补记（2026-08-15）

| 方案设计 | 实际落地 | 原因 |
|---------|---------|------|
| 网关 GlobalFilter 注入 | **WebFilter（order=-200）** | 网关自带 SecurityWebFilterChain（order=-100）先于 GlobalFilter 做 opaque introspection，注入必须更早 |
| redirect_uri = 网关域名 | 开发期 = `http://localhost:9010/auth/callback`（IAM 直连） | Cookie 按 host 匹配（不含 port），localhost 域全端口共享；生产改配置为网关域名 |
| 刷新：网关直调 /oauth2/token | 网关调 IAM `/auth/refresh`（凭 session） | client_secret 只允许 IAM 持有；refresh 不轮换（reuse=1）并发刷新无锁安全 |
| 灰度 2 周双轨 | 直接切换 | 无存量程序使用 password grant（用户确认） |

**踩坑实录**：
1. `MaculaRegisteredClientRepository.findByClientId` 有 Redis `@Cacheable`（JDK 序列化）——**改 sys_oauth2_client 表必须同步清缓存 key** `macula:cache:cloud:iam:oauth2:client::{clientId}`，重启服务无效（缓存 TTL 24h）
2. Reactor 中 `chain.filter()` 返回 `Mono<Void>`（永远 empty）——对它套 `switchIfEmpty` 会用原始 exchange 二次转发丢失注入；用 `defaultIfEmpty` 哨兵分流
3. Redisson reactive 完成信号在 Redisson netty 线程；后续链路（Security introspection 的阻塞 Redis 调用）会触发 sync-on-netty 防死锁检测——`publishOn(boundedElastic())` 切换
4. 网关 Redisson 默认 Kryo5 codec，读 IAM StringRedisTemplate 写的原始字符串需 `getBucket(key, StringCodec.INSTANCE)`
5. TestRestTemplate（Apache HttpClient 工厂）自动跟随 302 且 `HttpURLConnection.setFollowRedirects(false)` 无效——集成测试用 JDK HttpClient（默认 NEVER 跟随）

**password grant 弃用时间表**：T+3 月（2026-11）从 client 表移除 password/sms（新客户端一律授权码+PKCE）；T+6 月（2027-02）删除 Provider 代码与 password IT。运维脚本迁移至 client_credentials。
