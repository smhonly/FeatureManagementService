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
docs/                 design.md / design.html 架构设计文档
```

三个 Maven 模块，无 parent POM。

---

## 快速开始

**前置条件：** JDK 21、Maven 3.8+、Redis 7+

### 1. 构建全部模块

```bash
mvn install -f feature-flag-sdk/pom.xml -DskipTests   # SDK 必须先装到本地
mvn test -f feature-flag-sdk/pom.xml                   # 34 tests
mvn test -f management-api/pom.xml                     #  8 tests
mvn test -f snapshot-api/pom.xml                       #  6 tests
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

### 4. 运行 SDK Demo

```bash
mvn package -f feature-flag-sdk/pom.xml -DskipTests

java -DbaseUrl=http://localhost:8090 \
     -DapiKey=sk_live_demo_key \
     -Denv=production \
     -jar feature-flag-sdk/target/feature-flag-sdk-0.1.0.jar
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

```bash
mvn test -f feature-flag-sdk/pom.xml    # 34 tests
mvn test -f management-api/pom.xml      #  8 tests (SpringBootTest + H2)
mvn test -f snapshot-api/pom.xml        #  6 tests (SpringBootTest + H2 + Mock Redis)
```

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
