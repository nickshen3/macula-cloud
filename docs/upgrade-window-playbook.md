# P3-3 升级窗口机制：Spring 版本升级操作手册 + 回滚预案

> 状态：生效中 ｜ 建立：2026-08-15 ｜ 节奏：**每季度评估一次**（1/4/7/10 月第一周）

## 1. 原则

1. **只升官方矩阵内组合**：Spring Boot ↔ Spring Cloud ↔ Spring Cloud Alibaba 必须取 [spring-cloud 官方兼容矩阵](https://spring.io/projects/spring-cloud#overview) 同一列；禁止跨矩阵拼装（P0-1 前的教训：Boot 3.5.8 + Cloud 2023.0.6 错配静默漂移）。
2. **小版本跳跃 ≤ 2**：例如 3.5.13 → 3.5.15 允许；3.5 → 4.0 需单独立项。
3. **锁定时间戳 SNAPSHOT 策略**：macula-boot-parent 无 release 时，parent 版本必须锁时间戳（如 `6.0.1-20260813.031552-13`），升级 = 显式换时间戳，禁止开区间漂移。
4. **先锁定后升级**：任何升级前，当前组合必须已通过 `mvn -o` 离线构建验证（可复现基线）。

## 2. 升级窗口操作手册

### 2.1 评估（半天）

```bash
# ① 查当前组合
grep -A2 "<parent>" pom.xml | grep version
mvn dependency:tree -pl macula-cloud-iam | grep -E "spring-boot|spring-cloud" | head -5

# ② 查目标版本兼容性（官方矩阵 + SAS/Security 版本）
# Boot 3.5.x ↔ Security 6.5.x ↔ SAS 1.5.x ↔ Cloud 2025.0.x ↔ SCA 2025.0.0.0

# ③ 查 macula-boot-parent 是否有更新的时间戳 SNAPSHOT
# （Maven Central: dev.macula.boot → macula-boot-parent → maven-metadata.xml）
```

产出：《本季度升级评估》追加至本文件附录：升/不升、目标版本、风险点。

### 2.2 升级（半天～1 天）

```bash
# ① 建分支
git checkout -b upgrade/2026Q4-boot-3.5.x

# ② 改 parent 时间戳版本（pom.xml 唯一改动点）
# ③ 全量构建（在线拉新 parent）
mvn clean verify -Dmaven.repo.local=D:/Dev/Java/mvnRepo

# ④ 关键回归清单（按序）：
#   - IAM IT 7 用例全绿（password grant/限流/BFF）
#   - 三服务启动 + Nacos 配置加载（MACULA5 dataId）
#   - BFF 登录 → 网关 users/me → menus/routes
#   - snail-job 控制台登录
#   - 前端 vite build + 登录冒烟
#   - CI 双 job 绿

# ⑤ compatibility-verifier 保持启用（P1-1 已移除禁用项，任何错配在此暴露）
```

### 2.3 收尾

- [ ] `IMPROVEMENT-PLAN.md` 附录登记新组合
- [ ] tag：`v6.0.1-boot{X}-cloud{Y}`（升级点可回溯）
- [ ] `docs/quickstart.md` 若涉及版本坑则更新

## 3. 回滚预案

| 触发条件 | 动作 |
|---------|------|
| 构建失败/IT 红 | 分支不合并，直接丢弃；主分支零影响 |
| 上线后启动失败 | ① 回滚 jar：保留上一版 jar 目录 `deploy/releases/`（或镜像 tag）② `git revert` 升级 commit ③ 重建部署 |
| 上线后运行异常（内存/性能/功能） | 同上；数据无 schema 变更时无需回滚 DB |
| parent SNAPSHOT 仓库失效 | 本地仓库 `D:/Dev/Java/mvnRepo` 已含全量构件，`mvn -o` 离线可构建（P0-1 验证）；紧急时以此重建 |

**回滚验证**：回滚后跑 2.2 的④回归清单（子集：登录/网关/IT）。

## 4. 附录：版本组合登记

| 日期 | Boot | Cloud | SCA | parent（时间戳） | 决定 | 备注 |
|------|------|-------|-----|------------------|------|------|
| 2026-08-08 | 3.5.8 | 2023.0.6 | — | -7 | 发现 | 错配组合（历史遗留） |
| 2026-08-15 | 3.5.13 | 2025.0.2 | 2025.0.0.0 | 6.0.1-20260813.031552-13 | ✅ 采用 | P1-1，官方矩阵，校验器启用 |

## 5. 季度评估记录模板

```markdown
### 2026-Q4 评估（执行日：____）
- 目标版本：Boot ___ / Cloud ___ / SCA ___ / parent ___
- 官方矩阵核对：✅/❌
- 决定：升级 / 跳过（原因）
- 回归结果：（链接 CI run）
```
