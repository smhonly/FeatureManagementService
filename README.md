# Feature Management Service

100+ apps · 10,000+ flags · 100K QPS · P99 < 10ms

Three core decisions:
1. **Cache flag definitions** (not evaluation results); the SDK evaluates locally → `isEnabled()` never hits the network
2. **Stable hash bucketing + salt** for percentage rollouts → the same user always lands in the same bucket
3. **Deterministic replay** replaces evaluation logs → store version history (~50 MB) instead of logs (864 GB/day)

See `docs/design.md` (English) and `docs/design.html` (Chinese + SVG diagrams).

---

## Project structure

```
feature-flag-sdk/     Java 21 SDK (pure stdlib + Jackson + Jedis optional)
management-api/       Spring Boot 3.3 control plane (CRUD, auth, /explain)
snapshot-api/         Spring Boot 3.3 data plane (GET /snapshot + ETag + Pub/Sub)
e2e-tests/            End-to-end tests (boots both services + embedded Redis + shared H2)
docs/                 design.md / design.html architecture documents
```

Four Maven modules under one parent POM.

---

## Quick start

**Prerequisites:** JDK 21, Maven 3.8+

Running the services needs Redis 7+ (see step 2). **Running the tests does not** — the e2e tests bring their own embedded Redis.

### 1. Build all modules

```bash
# Compile and install the SDK (other modules depend on it)
mvn install -f feature-flag-sdk/pom.xml -DskipTests

# Run all tests (including e2e)
mvn test
```

### 2. Start Redis

```bash
docker run -d --name ff-redis -p 6379:6379 redis:7
```

### 3. Start management-api (control plane — start this first; Flyway creates the tables)

```bash
mvn spring-boot:run -f management-api/pom.xml
```

### 4. Start snapshot-api (data plane)

```bash
mvn spring-boot:run -f snapshot-api/pom.xml
```

### 5. Run the SDK demo

```bash
# Build the fat jar
mvn package -f feature-flag-sdk/pom.xml -DskipTests

# Run (defaults to localhost:8090)
java -DbaseUrl=http://localhost:8090 \
     -DapiKey=sk_live_xxx \
     -Denv=production \
     -jar feature-flag-sdk/target/feature-flag-sdk-0.1.0.jar
```

Sample demo output:
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

## Switching to PostgreSQL

```bash
docker run -d --name ff-pg -e POSTGRES_DB=featureflags -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15

export DATASOURCE_URL=jdbc:postgresql://localhost:5432/featureflags
export DATASOURCE_USER=postgres
export DATASOURCE_PASSWORD=postgres

mvn spring-boot:run -f management-api/pom.xml
mvn spring-boot:run -f snapshot-api/pom.xml
```

---

## API quick reference

### Data plane (snapshot-api :8090)

```
GET /api/v1/snapshot
  Header: Authorization: Bearer <api_key>
  Header: X-Env: production
  Header: If-None-Match: "v42.7"      ← optional; 304 not modified
  → 200  { "version":"v42.7", "flags":[...], "etag":"v42.7" }
  → 304  (unchanged)
```

### Control plane (management-api :8080)

```
POST   /api/v1/flags                       Create a flag
PUT    /api/v1/flags/{key}                 Update (If-Match optimistic locking)
GET    /api/v1/flags                       Search (?env=&prefix=&limit=)
GET    /api/v1/flags/{key}                 Detail + history
DELETE /api/v1/flags/{key}                 Soft delete (state → archived)
POST   /api/v1/flags/{key}/rollout         Rollout (only changes pct)
POST   /api/v1/flags/{key}/targeting       Targeting rules (only changes rules)
POST   /api/v1/admin/applications          Register an app (returns api_key once)
GET    /api/v1/explain?flag=&user=&at=     Deterministic replay
```

---

## Running tests

### Unit / integration tests

```bash
mvn test -f feature-flag-sdk/pom.xml    # 41 tests
mvn test -f management-api/pom.xml      #  8 tests (SpringBootTest + H2)
mvn test -f snapshot-api/pom.xml        #  9 tests (SpringBootTest + H2 + mock Redis)
```

### E2E tests

The e2e tests start management-api and snapshot-api in a single JVM (random ports), an embedded Redis (auto-picks a free port), and a shared in-memory H2. The SDK hits snapshot-api over loopback HTTP.

**No Docker required, no Redis install required.** The embedded Redis is managed automatically by `com.github.codemonstur:embedded-redis`.

```bash
# First run, or after source changes — build all dependent modules
mvn test -pl e2e-tests -am -Dtest='com.example.e2e.**' -Dsurefire.failIfNoSpecifiedTests=false

# Subsequent runs — e2e tests only (dependencies already in the local Maven repo)
mvn test -pl e2e-tests -Dtest='com.example.e2e.**' -Dsurefire.failIfNoSpecifiedTests=false
```

E2E coverage:

| Test class | What it covers |
|------------|----------------|
| `HappyPathTest` | Boolean flag create → snapshot rebuild → SDK fetch → isEnabled(); scope isolation; targeting rules; percentage rollout distribution; local evaluation performance |
| `FlagLifecycleTest` | Update propagation (SDK sees the new definition after a change); soft delete (flag disappears from the snapshot after archive); version conflict (412); optimistic lock version increments |
| `ExplainReplayTest` | /explain deterministic replay (query the current version after an update); historical time point → 404; non-existent flag → 404 |
| `AuthFlowTest` | Valid API key; invalid API key → 401; missing Auth header → 401; with a snapshot present → 200 |
| `EtagCachingTest` | First request → 200 + ETag; same ETag → 304; after a snapshot update, old ETag → 200 with a new version |

---

## Environment variables

| Variable | Module | Default | Description |
|----------|--------|---------|-------------|
| `DATASOURCE_URL` | management-api, snapshot-api | `jdbc:postgresql://localhost:5432/featureflags` | Postgres connection string |
| `DATASOURCE_USER` | management-api, snapshot-api | `postgres` | Postgres user |
| `DATASOURCE_PASSWORD` | management-api, snapshot-api | `postgres` | Postgres password |
| `REDIS_HOST` | all | `localhost` | Redis host |
| `REDIS_PORT` | all | `6379` | Redis port |
| `FF_SERVER_SECRET` | management-api, snapshot-api | `change-me-...` | HMAC secret |
| `FF_JWT_SECRET` | management-api | `change-me-...` | JWT signing secret |

Auth filters are disabled by default. Enable them with:
```yaml
ff:
  auth:
    hmac-enabled: true
    jwt-enabled: true
```
