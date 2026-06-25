# Feature Management Service

100+ 应用 · 10,000+ 开关 · 100K QPS · P99 < 10ms

三个核心决策：
1. **缓存开关定义**（非评估结果），SDK 本地执行 → `isEnabled()` 无网络请求
2. **稳定哈希分桶 + salt** 做百分比灰度 → 同用户永远同桶
3. **确定性重放**替代评估日志 → 存版本历史(~50MB) vs 日志(864GB/天)

详见 `docs/design.md`（英文）和 `docs/design.html`（中文 + SVG 图）。

---

## 项目结构

```
feature-flag-sdk/     Java 21 SDK（纯 stdlib + Jackson + Jedis optional）
management-api/       Spring Boot 3.3 控制面（CRUD、auth、/explain）
snapshot-api/         Spring Boot 3.3 数据面（GET /snapshot + ETag + Pub/Sub）
e2e-tests/            端到端测试（起两个服务 + 内嵌 Redis + 共享 H2）
docs/                 design.md / design.html 架构设计文档
```

四个 Maven 模块，统一父 POM。

---

## 快速开始

**前置条件：** JDK 21、Maven 3.8+

运行服务需要 Redis 7+（见步骤 2）。**跑测试不需要** —— e2e 测试自带内嵌 Redis。

### 1. 构建全部模块

```bash
# 编译安装 SDK（其他模块依赖它）
mvn install -f feature-flag-sdk/pom.xml -DskipTests

# 运行全部测试（含 e2e）
mvn test
```

### 2. 启动 Redis

```bash
docker run -d --name ff-redis -p 6379:6379 redis:7
```

### 3. 启动 management-api（控制面，先启动 — Flyway 建表）

```bash
mvn spring-boot:run -f management-api/pom.xml
```

### 4. 启动 snapshot-api（数据面）

```bash
mvn spring-boot:run -f snapshot-api/pom.xml
```

### 5. 运行 SDK Demo

```bash
# 构建 fat jar
mvn package -f feature-flag-sdk/pom.xml -DskipTests

# 运行（默认连 localhost:8090）
java -DbaseUrl=http://localhost:8090 \
     -DapiKey=sk_live_xxx \
     -Denv=production \
     -jar feature-flag-sdk/target/feature-flag-sdk-0.1.0.jar
```

Demo 输出示例：
```
=== Feature Flag SDK Demo ===
Snapshot API: http://localhost:8090
Environment:  production

Initial refresh... OK (4 flags)
Polling timer started (interval=30s)

=== Evaluation loop ===
  [user_0] flag=new_checkout         enabled=true  reason=bucket=15 < 20 → true
  [user_1] flag=vip_discount         enabled=false reason=boolean=false
  ...

=== Metrics ===
  eval_total                  = 12
  eval_avg_duration_us        = 3
  ...
Demo finished.
```

---

## 切换到 PostgreSQL

```bash
docker run -d --name ff-pg -e POSTGRES_DB=featureflags -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15

export DATASOURCE_URL=jdbc:postgresql://localhost:5432/featureflags
export DATASOURCE_USER=postgres
export DATASOURCE_PASSWORD=postgres

mvn spring-boot:run -f management-api/pom.xml
mvn spring-boot:run -f snapshot-api/pom.xml
```

---

## API 速览

### 数据面（snapshot-api :8090）

```
GET /api/v1/snapshot
  Header: Authorization: Bearer <api_key>
  Header: X-Env: production
  Header: If-None-Match: "v42.7"      ← 可选，304 缓存
  → 200  { "version":"v42.7", "flags":[...], "etag":"v42.7" }
  → 304  (unchanged)
```

### 控制面（management-api :8080）

```
POST   /api/v1/flags                       创建开关
PUT    /api/v1/flags/{key}                 更新（If-Match 乐观锁）
GET    /api/v1/flags                       搜索（?env=&prefix=&limit=）
GET    /api/v1/flags/{key}                 详情 + 历史
DELETE /api/v1/flags/{key}                 软删除（state → archived）
POST   /api/v1/flags/{key}/rollout         灰度放量（只改 pct）
POST   /api/v1/flags/{key}/targeting       定向规则（只改 rules）
POST   /api/v1/admin/applications          注册应用（返回 api_key 仅一次）
GET    /api/v1/explain?flag=&user=&at=      确定性重放
```

---

## 运行测试

### 单元 / 集成测试

```bash
mvn test -f feature-flag-sdk/pom.xml    # 41 tests
mvn test -f management-api/pom.xml      #  8 tests (SpringBootTest + H2)
mvn test -f snapshot-api/pom.xml        #  9 tests (SpringBootTest + H2 + Mock Redis)
```

### E2E 测试

端到端测试在一个 JVM 里启动 management-api 和 snapshot-api（随机端口）、内嵌 Redis（自动找空闲端口）、共享 H2 内存数据库，SDK 走 loopback HTTP 调用 snapshot-api。

**不需要 Docker、不需要安装 Redis。** 内嵌 Redis 由 `com.github.codemonstur:embedded-redis` 自动管理。

```bash
# 首次 / 代码有改动时 —— 构建所有依赖模块
mvn test -pl e2e-tests -am -Dtest='com.example.e2e.**' -Dsurefire.failIfNoSpecifiedTests=false

# 后续 —— 仅运行 e2e 测试（依赖已装到本地 Maven 仓库）
mvn test -pl e2e-tests -Dtest='com.example.e2e.**' -Dsurefire.failIfNoSpecifiedTests=false
```

e2e 覆盖场景：

| 测试类 | 覆盖 |
|--------|------|
| `HappyPathTest` | boolean flag 创建→快照重建→SDK 拉取→isEnabled()；scope 隔离；targeting 规则；百分比灰度分布；本地评测性能 |
| `FlagLifecycleTest` | 更新传播（定义变更后 SDK 看到新定义）；软删除（archive 后从快照消失）；版本号冲突（412）；乐观锁版本递增 |
| `ExplainReplayTest` | /explain 确定性重放（更新后查询当前版本）；历史时间点 404；不存在的 flag 404 |
| `AuthFlowTest` | 合法 API key；不合法 API key → 401；缺失 Auth header → 401；有快照时 → 200 |
| `EtagCachingTest` | 首次请求 → 200 + ETag；相同 ETag → 304；快照更新后旧 ETag → 200 + 新版本号 |

---

## 环境变量

| 变量 | 模块 | 默认值 | 说明 |
|------|------|--------|------|
| `DATASOURCE_URL` | management-api, snapshot-api | `jdbc:postgresql://localhost:5432/featureflags` | PG 连接 |
| `DATASOURCE_USER` | management-api, snapshot-api | `postgres` | PG 用户 |
| `DATASOURCE_PASSWORD` | management-api, snapshot-api | `postgres` | PG 密码 |
| `REDIS_HOST` | 全部 | `localhost` | Redis 地址 |
| `REDIS_PORT` | 全部 | `6379` | Redis 端口 |
| `FF_SERVER_SECRET` | management-api, snapshot-api | `change-me-...` | HMAC 密钥 |
| `FF_JWT_SECRET` | management-api | `change-me-...` | JWT 签名密钥 |

Auth filter 默认关闭，开启方式：
```yaml
ff:
  auth:
    hmac-enabled: true
    jwt-enabled: true
```
