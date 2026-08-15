# Redis 高可用方案（P1-5）

> 背景：macula-cloud 的 OAuth2 授权数据（token 缓存、consent）、登录限流计数（P0-5）、
> 各服务缓存均强依赖 Redis。Redis 不可用时：登录链路限流自动降级 fail-open（P0-5 已实现），
> 但 token 校验/续期受损。生产环境必须消除单点。

## 1. 方案选型

| 方案 | 适用 | 结论 |
|------|------|------|
| **Sentinel（推荐）** | 中小规模、自建 | ✅ 本方案：3 节点 Sentinel + 1主2从，自动故障切换，客户端零改造 |
| Cluster | 大规模、分片需求 | ❌ 当前数据量（token/计数）无分片必要，复杂度高 |
| 云托管（ElastiCache/阿里云 Tair） | 云上生产 | ✅ 可直接替代，配置连接串即可 |

## 2. Sentinel 拓扑（最小生产配置）

```
节点规划（3 台独立宿主机或 3 AZ）：
┌─────────────────────────────────────────────┐
│ redis-1 (master, 6379, 写)                   │
│ redis-2 (replica, 6379, 读副本/晋升候选)      │
│ redis-3 (replica, 6379, 读副本/晋升候选)      │
│ sentinel-1/2/3（与 redis 同机混部或独立，26379）│
└─────────────────────────────────────────────┘
故障判定：多数 sentinel（quorum=2）确认 master 失联 → 自动选举新 master → 客户端感知切换。
```

### 部署（docker-compose 示例，生产建议 systemd/独立部署）

```yaml
# deploy/redis-sentinel/docker-compose.yml
services:
  redis-master:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes", "--requirepass", "${REDIS_PASS}"]
    volumes: [redis-m:/data]
  redis-replica-1:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes", "--requirepass", "${REDIS_PASS}",
              "--masterauth", "${REDIS_PASS}", "--replicaof", "redis-master", "6379"]
    depends_on: [redis-master]
    volumes: [redis-r1:/data]
  redis-replica-2:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes", "--requirepass", "${REDIS_PASS}",
              "--masterauth", "${REDIS_PASS}", "--replicaof", "redis-master", "6379"]
    depends_on: [redis-master]
    volumes: [redis-r2:/data]
  sentinel-1:
    image: redis:7-alpine
    command: ["redis-sentinel", "/etc/sentinel.conf"]
    volumes: ["./sentinel.conf:/etc/sentinel.conf:ro"]
    depends_on: [redis-master]
volumes: { redis-m: {}, redis-r1: {}, redis-r2: {} }
```

```conf
# sentinel.conf（三个 sentinel 实例各一份）
port 26379
sentinel monitor macula redis-master 6379 2
sentinel down-after-milliseconds macula 5000
sentinel failover-timeout macula 60000
sentinel auth-pass macula <REDIS_PASS>
```

## 3. 应用侧接入

macula 服务（Spring Boot + spring-data-redis）只需改连接配置（无需代码改动）：

```yaml
spring:
  data:
    redis:
      password: <REDIS_PASS>
      sentinel:
        master: macula
        nodes: sentinel-1:26379,sentinel-2:26379,sentinel-3:26379
      # 连接池（默认 lettuce 自动适配 sentinel 拓扑变化）
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
```

三个服务（IAM/System/Gateway）统一替换原 `host:port` 配置。
Gateway 的 RedisConfiguration（P0 修复过 @Primary）同样兼容。

## 4. 故障切换演练（上线前必做）

| 步骤 | 操作 | 预期 | 验证命令 |
|------|------|------|----------|
| 1 | 正常基线 | 登录成功 | `curl POST /oauth2/token` 拿 token |
| 2 | **kill master**（redis-1 容器） | 5~10s 内 sentinel 完成选举 | `redis-cli -p 26379 sentinel master macula` 看 flag 变化 |
| 3 | 切换期间持续登录 | 最多 1~2 次失败后恢复（lettuce 重连） | 循环 curl 观察 |
| 4 | 恢复旧 master | 自动以 replica 身份重新加入 | `redis-cli info replication` |
| 5 | 复核数据 | 限流计数/已签发 token 仍有效 | 旧 token 调 /users/me |

**验收标准**：步骤 3 中登录成功率 ≥ 95%，无需人工干预，无数据丢失（AOF everysec）。

## 5. 运维要点

- **持久化**：`appendonly yes` + `appendfsync everysec`（限流计数可容忍秒级丢失）
- **密码**：生产必须 requirepass + masterauth；sentinel 配置 auth-pass
- **监控**：Prometheus + redis_exporter，关键指标 `redis_up`、`redis_master_status`、
  `redis_connected_slaves`、复制延迟 `redis_master_repl_offset` 差值
- **告警**：master 切换事件（sentinel 通知脚本）、connected_slaves < 2、offset 滞后 > 10MB
- **禁止**：`FLUSHALL` 直连 master 操作（误删 token 库）；先 `SENTINEL get-master-addr-by-name` 确认
- **容量**：单 token 记录 < 1KB，10 万活跃用户 < 200MB，默认 maxmemory 建议限制 + `allkeys-lru`

## 6. 当前状态与差距

- [x] P0-5 限流已实现 fail-open（Redis 全挂时登录不受阻，但失去爆破防护）
- [x] 应用侧仅改配置即可接入 Sentinel（无代码耦合）
- [ ] 生产环境部署 3 节点 Sentinel（本方案）
- [ ] 故障切换演练通过（第 4 节）
- [ ] redis_exporter 接入 P1-4 的 Prometheus 体系
