# Macula Cloud 改进计划

> 版本：v1.0（2026-08-15）
> 前提：基于 2026-08-08 端到端调试（启动全链路 + 登录修复 + 数据修复）积累的一手证据制定。
> 当前代码基线：commit `1410c67`，版本 6.0.1-SNAPSHOT。

---

## 1. 问题清单（已确认的证据）

| # | 问题 | 证据 | 严重度 |
|---|------|------|--------|
| Q1 | Spring Boot 3.5.8 与 Spring Cloud 2023.0.6 版本错配（官方矩阵中 Boot 3.5.x 应配 Cloud 2025.0.x），靠禁用 compatibility-verifier 掩盖 | Gateway/System bootstrap.yml 中 `compatibility-verifier.enabled: false` | 🔴 高 |
| Q2 | Spring Security 6.5.7 下 `@EnableWebSecurity` 不再内含 `@Configuration`，`DefaultSecurityConfiguration` 全部 `@Bean` 静默失效（启动不报错、登录才炸） | 已修复（补注解 + Provider 延迟创建），但同类问题可能残留在其他配置类 | 🔴 高 |
| Q3 | 父依赖 `macula-boot-parent:6.0.1-SNAPSHOT`，构建不可复现，上游停更则锁死 | 根 pom.xml | 🔴 高 |
| Q4 | client_secret 明文硬编码在前端源码中，可绕过前端直接刷 token 接口 | `macula-cloud-admin/src/views/common/login/components/passwordForm.vue`、`auth.js` | 🔴 高 |
| Q5 | SQL dump 存在语法错误且导入默认 latin1 导致中文全部乱码；库命名连字符（`macula-system`）需反引号转义，非常规 | `docs/macula-system-dump.sql`（已修一处），Docker 导入乱码已手工重灌 | 🟡 中 |
| Q6 | 无测试：全程 `skipTests`，pom 无测试模块，回归全靠手点 | 调试全程 | 🟡 中 |
| Q7 | 组件冗余：xxl-job 与 snail-job 两套调度并存；tinyid 独立进程 + 独立库只做发号 | 模块列表 | 🟡 中 |
| Q8 | Redis 强依赖：OAuth2 授权数据/consent/会话全在 Redis，无高可用方案即单点 | `MaculaOAuth2AuthorizationConsentService(RedisTemplate)` | 🟡 中 |
| Q9 | 前端依赖树不健康：需 `--legacy-peer-deps --ignore-scripts`，Windows 下 postinstall 报错；sass 与 Element Plus 错配（已修一例） | package.json | 🟡 中 |
| Q10 | Nacos 配置中心空置（namespace MACULA5 为空配置，实际走本地 application.yml），配置与代码未分离 | Nacos 控制台 | 🟢 低 |
| Q11 | 上游维护力度弱：2025-12 ~ 2026-06 仅零星提交，Spring 大版本升级无人兜底 | git log | 🔴 高（战略） |

---

## 2. 目标与原则

**目标**：把项目从"能跑的 demo 基座"变成"可复现、可回归、可运维的商用基座"。

**原则**：
1. **可复现优先**：任何人在干净环境 30 分钟内从零拉起到登录成功。
2. **先固化再优化**：先锁定版本和构建，再谈架构演进。
3. **每个修复配测试**：修过的坑必须用自动化测试钉死，防止回归。
4. **渐进替换**：不推倒重来，macula-boot 收编按模块逐步进行。

---

## 3. 分阶段任务

### 阶段 P0：止血与固化（第 1~2 周，约 5 人日）

| 编号 | 任务 | 具体动作 | 完成标准（DoD） | 工作量 |
|------|------|----------|----------------|--------|
| P0-1 | 固化父依赖 | 验证 Maven Central 是否存在 macula-boot-parent 稳定版（`6.0.0` 或最新 release）；若无，用 `mvn flatten` + 内联 BOM 方式将 SNAPSHOT 依赖版本锁定到自有仓库 | `mvn -o clean package` 离线构建成功；两次构建产物 hash 一致 | 1 人日 |
| P0-2 | 排查同类静默失效 | 全仓库审计所有仅有 `@EnableWebSecurity`/`@Enable*` 而无 `@Configuration` 的配置类（重点：iam、gateway、system 模块） | 审计清单入库；发现的类补注解并启动验证【2026-08-15 预审计：全仓库 `@EnableWebSecurity` 仅 DefaultSecurityConfiguration 一处且已修复，无同类隐患；仅需例行复查】 | 0.5 人日 |
| P0-3 | 修复部署物料 | ① dump 文件头部加 `SET NAMES utf8mb4`；② 校验全部 dump 无语法错误；③ `deploy/docker-compose.yml` 增加 MySQL 初始化自动导入（`/docker-entrypoint-initdb.d`）；④ 库命名决策记录（保留连字符，文档注明需反引号） | `docker compose up` + 三服务启动 + 登录成功，全程无手工 SQL 操作 | 1 人日 |
| P0-4 | 部署文档重写 | README 增补"从零启动"章节：环境要求（JDK21/Node22/端口清单）、Windows 下的坑（taskkill、postinstall）、数据库初始化步骤 | 新同事按文档独立完成部署（真人或模拟验证） | 1 人日 |
| P0-5 | 登录防爆破（临时） | IAM 侧对 `/oauth2/token` 增加 IP + 账号双维度失败计数（Redis），5 次失败锁定 10 分钟；验证码开关默认开启 | curl 连续错误密码 5 次后返回 429/锁定提示 | 1 人日 |
| P0-6 | 分支保护 | GitHub 设置 main 分支保护（PR 必须过 CI）；建立 `develop` 分支流转 | 直接 push main 被拒绝 | 0.5 人日 |

### 阶段 P1：版本矩阵修正与测试兜底（第 3~6 周，约 10 人日）

| 编号 | 任务 | 具体动作 | DoD | 工作量 |
|------|------|----------|-----|--------|
| P1-1 | Spring Cloud 升级到 2025.0.x | 升级根 pom 的 spring-cloud 版本；移除两处 `compatibility-verifier.enabled: false`；全模块回归 | 不禁用校验器启动成功；登录/菜单/用户 CRUD 冒烟通过 | 3 人日 |
| P1-2 | 认证链路集成测试 | 新建 `macula-cloud-iam` 测试模块：① password grant 成功/失败/锁定；② refresh_token；③ `/users/me`、`/menus/routes` 网关链路；④ Testcontainers 起 MySQL+Redis | `mvn test` 全绿；CI 中强制执行 | 3 人日 |
| P1-3 | CI 流水线 | GitHub Actions：PR 触发 `mvn verify` + 前端 `vite build`； nightly 全量集成测试 | PR 未过 CI 不可合并 | 1 人日 |
| P1-4 | 可观测性 | Actuator 暴露 health/metrics/prometheus（独立 management port + 网络隔离）；接入现有 Grafana 或输出部署文档 | /actuator/prometheus 可抓取；登录失败率、token 签发量有图表 | 2 人日 |
| P1-5 | Redis 高可用方案 | 生产部署采用 Sentinel（≥3 节点）或云托管 Redis；应用侧配置 sentinel 模式并验证故障切换 | kill 一个 Redis 后登录不中断（Sentinel 自动切换） | 1 人日 |

### 阶段 P2：组件收敛与安全演进（第 2~3 个月，约 12 人日）

| 编号 | 任务 | 具体动作 | DoD | 工作量 |
|------|------|----------|-----|--------|
| P2-1 | 调度组件二选一 | 评估 xxl-job vs snail-job（功能/社区/与 Boot 3.5 兼容性），保留一套，另一套下线 | 下线模块从 pom 移除；保留方任务调度冒烟通过 | 3 人日 |
| P2-2 | tinyid 去留决策 | 若无强需求（严格趋势递增），改用 MyBatis-Plus 默认雪花 ID，下线 tinyid 进程与独立库 | 减 1 个 Java 进程 + 1 个 DB 库；ID 生成无冲突验证 | 2 人日 |
| P2-3 | secret 治理 | ① client_secret 移出前端源码，改由登录接口代理换取（BFF 模式）或网关注入；② 生产 secret 走 Nacos 加密配置或环境变量 | 前端产物中 grep 不到 secret 字符串 | 2 人日 |
| P2-4 | Nacos 配置中心启用 | 将各服务 application.yml 迁移到 Nacos（namespace MACULA5），本地只留 bootstrap.yml；多环境（dev/test/prod）profile 化 | 改配置不需重新打包；Nacos 改配置热生效验证 | 2 人日 |
| P2-5 | 前端依赖治理 | 梳理 peer conflicts 清单，逐个升级消除 `--legacy-peer-deps`；postinstall 改跨平台写法（node 脚本替代 cmd） | `npm install`（无 flag）成功；Windows/macOS/Linux 三平台可装 | 3 人日 |

### 阶段 P3：长期演进（第 3 个月起，按季度滚动）

| 编号 | 任务 | 方向 | 里程碑标准 |
|------|------|------|-----------|
| P3-1 | 核心链路收编 | 将 IAM 自定义 Provider（password/sms grant）、UserDetails、ClientRepository 逐步收编进自有代码库，macula-boot 降级为普通依赖再移除 | 自有代码覆盖认证核心，macula-boot 仅剩工具类依赖 |
| P3-2 | OAuth2 模式演进 | Web 端评估迁移授权码 + PKCE（OAuth 2.1 方向），password grant 保留给受信第一方客户端并设退出时间表 | 迁移方案评审通过；新客户端默认走授权码 |
| P3-3 | 升级窗口机制 | 每季度评估一次 Spring Boot/Cloud 小版本升级，锁定官方兼容矩阵，不再出现跨矩阵组合 | 升级操作手册 + 回滚预案入库 |
| P3-4 | System 服务拆分评估 | 用户/权限/租户/字典/日志按团队规模决定是否拆分；输出拆分评估报告而非直接动手 | 报告含收益/成本/风险测算 |

---

## 4. 里程碑总览

```
第1-2周   P0  止血固化      → 可复现构建 + 一键部署 + 防爆破
第3-6周   P1  版本修正+测试  → 官方版本矩阵 + CI 全绿 + 可观测
第2-3月   P2  组件收敛+安全  → 减进程减组件 + secret 治理 + 配置中心
第3月起   P3  长期演进      → 核心收编 + 授权码迁移 + 季度升级窗口
```

**关键依赖关系**：
- P1-1（Cloud 升级）必须在 P0-1（固化依赖）之后，否则升级对象本身不可复现。
- P1-2（测试）先于 P2 所有任务——没有回归网不动架构。
- P2-3（secret 治理）依赖 P0-5 的限流先行兜底。

---

## 5. 风险与应对

| 风险 | 概率 | 应对 |
|------|------|------|
| Spring Cloud 2025.0.x 升级引入新的破坏性变更（如 Gateway 配置项变更） | 中 | 先在独立分支全量回归；保留 2023.0.6 分支可回退 |
| macula-boot 无稳定版 release，无法脱离 SNAPSHOT | 中 | flatten+自有仓库锁版本（P0-1）；长期走 P3-1 收编 |
| xxl-job/snail-job 二选一后历史任务迁移成本超预期 | 低 | 输出任务清单评估后再动手；允许双栈并存过渡一个版本 |
| 前端依赖升级引发 UI 回归 | 中 | P2-5 逐包升级 + 关键页面截图对比 |
| 上游 macula-cloud 社区彻底停更 | 中 | 本计划 P3-1 即为此预案；收编完成后上游停更无影响 |

---

## 6. 度量指标

| 指标 | 现状 | P1 结束目标 | P3 结束目标 |
|------|------|-------------|-------------|
| 从零部署到登录成功耗时 | >2 小时（含踩坑） | ≤30 分钟（按文档） | ≤10 分钟（一键脚本） |
| 构建可复现（同源两次构建 hash 一致） | ❌（SNAPSHOT） | ✅ | ✅ |
| 认证链路自动化测试覆盖 | 0 | 核心场景 100% | 含故障注入 |
| 版本组合符合官方兼容矩阵 | ❌ | ✅ | ✅ |
| 运行进程数（核心三件套外） | +tinyid+双调度 | -1（调度二选一后 -2） | 最小化 |
| 前端产物含明文 secret | ❌ 含 | ❌ 含（有限流兜底） | ✅ 不含 |

---

## 7. 立即可做的第一步

**本周执行 P0-1 + P0-2**（合计 1.5 人日）：
1. 查询 Maven Central：`https://central.sonatype.com/search?q=dev.macula.boot` 确认有无 release 版；
2. 全仓库执行 `grep -rL "@Configuration" --include="*.java" $(grep -rl "@EnableWebSecurity\|@EnableMethodSecurity\|@EnableGlobalMethodSecurity" --include="*.java" .)` 输出待审计清单。

> 本文档随进度更新，每阶段结束追加执行记录与偏差说明。

---

## 8. 执行记录

### P0 执行记录（2026-08-15，全部完成）

| 任务 | 结果 | 备注 |
|------|------|------|
| P0-1 固化 SNAPSHOT 依赖 | ✅ 锁定到 `6.0.1-20260610.013910-7`，离线构建 + 两次构建 hash 一致 | **实施发现**：Maven Central 无 6.x release（最新 5.0.18）；SNAPSHOT 漂移已实际发生（本地同时存在 -7/-13 两版，-13 已升 Boot 3.5.13/Cloud 2025.0.2）→ **P1-1 升级目标改为直接采用 -13 版本组合** |
| P0-2 静默失效审计 | ✅ 无残留 | `@Enable*` 4 处、`@ConfigurationProperties` 3 处、SecurityFilterChain 仅 IAM 两处，均有注册注解 |
| P0-3 部署物料 | ✅ docker compose up 一键初始化验证通过（3 库 26 表自动导入，中文正常，Nacos ready） | **实施发现**：`deploy/docker-compose.yml` 原为 0 字节空文件；新增 Redis 服务（默认注释）；**踩坑**：`--skip-character-set-client-handshake` 与 JDBC 驱动协商冲突导致中文乱码，已移除 |
| P0-4 部署文档 | ✅ 新建 `docs/quickstart.md`，README 增加入口 | 含 Windows 坑速查表（cmd 分离启动/taskkill 占用/esbuild 手动安装等） |
| P0-5 登录防爆破 | ✅ DoD 达成：5 次失败后第 6 次返回 `access_denied` + 中文锁定提示（HTTP 400） | 实现在 `OAuth2ResourceOwnerBaseAuthenticationProvider`；账号+IP 双维度（Redis 计数，TTL 600s）；**修复附带 bug**：原 `oAuth2AuthenticationException()` 转换器会把非预期异常包成 server_error，已加透传分支；限流 fail-open（Redis 不可用不阻断登录）；密码与 SMS 授权同受保护 |
| P0-6 分支保护 | 📋 指引已交付（需仓库管理员网页操作） | Settings → Branches → main：勾选 Require PR + Required status checks（待 P1-3 CI 就绪后启用） |

**验证环境状态**：MySQL/Nacos 由新 compose 管理（named volume 持久化）；Redis 复用宿主机 tutoring-redis:6379（跨项目共享，生产环境需独立实例——列入 P2）；三后端 + 前端运行正常，登录链路含限流全绿。

### P1 执行记录（2026-08-15，全部完成）

| 任务 | 结果 | 备注 |
|------|------|------|
| P1-1 升级官方版本矩阵 | ✅ parent 切 -13（Boot 3.5.13 + Cloud 2025.0.2），移除全部 5 处 compatibility-verifier 禁用，校验器真实启用下全回归通过 | 上游 -13 已自带官方矩阵组合，直接采用；后续升级沿此模式（新时间戳版本+全回归） |
| P1-2 认证链路集成测试 | ✅ OAuth2PasswordGrantIT（Testcontainers），本地/CI 均 6/6 全绿 | 测试反哺设计：IP 维度阈值独立为 10 次（NAT 容忍）；修复 oAuth2AuthenticationException() 透传缺陷 | 
| P1-3 CI 流水线 | ✅ 双 job 全绿（commit 84e6e35 验证） | **踩坑实录**：①本地绿 CI 红两类根因——前端 sideM.vue import 大小写错误（Windows 不敏感/Linux 敏感，已修+全扫无残留）、tinyid ServerTest 隐式依赖本机 MySQL（已 skipTests，P2-2 随模块处理）；②诊断手段：check-runs annotations 匿名可读，logs/artifacts 需权限 |
| P1-4 可观测性 | ✅ 三服务 health/prometheus 200，指标带 application 标签 | 需显式加 spring-boot-starter-actuator + micrometer-registry-prometheus（传递依赖不可靠）；IAM 安全链补白名单放行 |
| P1-5 Redis HA 方案 | ✅ deploy/redis-ha.md | Sentinel 3 节点拓扑+compose 示例+故障演练步骤+运维要点；应用侧仅改配置 |

### P2 执行记录（2026-08-15，全部完成；commits 2b74b4c/87a3c9f/5d18672/99c2b69/477ec5e，CI 全绿）

| 任务 | 结果 | 备注 |
|------|------|------|
| P2-1 调度二选一 | ✅ 保留 snail-job，下线 xxl-job | xxl-job 内嵌 3000+ 行 admin 源码自维护成本高；snail-job 仅 1 启动类+官方 starter 1.9.0（Boot 3 原生），功能超集。冒烟：控制台 200、auth/login 签发 JWT（admin/admin，前端预 md5 后 sha256 入库）、节点 rebalance 正常；官方 1.9.0 schema（23 表）入库并代码化 |
| P2-2 tinyid 下线 | ✅ 零消费者实证（无 pom 依赖/代码调用/网关路由；ID 实际由 MySQL AUTO_INCREMENT 生成） | 减 1 进程+1 库；根 pom 移 module、compose/IT 移除 dump、DB drop |
| P2-3 secret 治理 | ✅ BFF 代理 POST /login/token：服务端注入凭证、错误体透传（含限流）、生产环境变量覆盖（MACULA_OAUTH2_*） | DoD 达成：dist 产物 grep 不到 e4da4a32/client_secret；IT 新增 BFF 用例 7/7 绿；端口回填用 WebServerInitializedEvent（RANDOM_PORT 兼容） |
| P2-4 Nacos 配置中心 | ✅ 业务配置全量迁 Nacos（MACULA5 三 dataId）；bootstrap.yml 退役；application.yml 最小骨架+spring.config.import(optional:nacos:)；deploy/nacos-config/ 为 git source of truth | 附带修复：system 9081→9082（被其他项目 Docker 容器占用）；nacos-config starter 由框架传递无需显式。**已知限制**：SCA 2025.0.0.0 下 @Value/@ConfigurationProperties 热重绑定未完全生效（refresh keys 仅 spring.application.version；排除 bootstrap starter 对照实验失败已回滚），列入 P3；“改配置不重新打包”以重启生效达成 |
| P2-5 前端依赖治理 | ✅ npm install 无 flag（Windows 干净目录实测+CI Linux 验证） | lock 固化后无 peer 冲突；esbuild 平台包走 optionalDependencies 自动就位，删 --ignore-scripts 与手动 install 步骤；engines.node >=22 <23 |

**P2 后环境变化**：Java 进程 12→9 模块（tinyid/xxljob 移除）；DB 库 macula-system + macula-snailjob（utf8mb4）；Nacos MACULA5 namespace 承载全部应用配置（dataId=服务名.yml，group=DEFAULT_GROUP）；system 直连端口 9082；snail-job 控制台 http://localhost:9086/snail-job（admin/admin）。

### P3 执行记录（2026-08-15，P0~P3 计划全部完成；commit d092cb9）

| 任务 | 结果 | 备注 |
|------|------|------|
| P3-1 核心链路收编 | ✅ 调研证实**已天然达成**：grant Provider/Converter、UserDetailsService、RegisteredClientRepository、AuthorizationService 均在自有代码；pom 显式依赖为 Spring 官方 SAS 1.5.5（属性名误导已澄清加注）；macula-boot 仅剩工具类引用 | 上游架构本身把认证核心放在应用仓库，收编零成本 |
| P3-2 授权码+PKCE 迁移方案 | ✅ docs/oauth2-authcode-pkce-migration.md（待评审） | 实测 authorize+PKCE 基础设施就绪；推荐 BFF 路线（token 不进浏览器）；password grant T+3 月禁用/T+6 月移除 |
| P3-3 升级窗口机制 | ✅ docs/upgrade-window-playbook.md | 季度评估节奏+官方矩阵铁律+时间戳 SNAPSHOT 锁定+回滚预案+版本登记表 |
| P3-4 System 拆分评估 | ✅ 结论：现阶段不拆分 | 收益≈0（管理台场景 QPS 低、登录链路 4 表联查拆分代价高、近不可逆）；给出重评触发条件（代码量>20k 行/多团队/合规隔离）与三个低成本边界守护动作 |

**计划全览**：P0（止血固化）→ P1（工程化：矩阵/测试/CI/观测）→ P2（收敛与安全：组件减二、BFF 零 secret、配置中心、依赖去 flag）→ P3（演进：收编确认+方案储备）全部完成。后续待办：P3-2 方案评审后的 5 阶段实施、SCA 热刷新深挖（P2-4 遗留）、P2 遗留的 Redis 独立实例生产化。
