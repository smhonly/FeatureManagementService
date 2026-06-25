# Feature Management Service — Architecture Design

*100+ applications · 10,000+ flags · 100K QPS evaluations · P99 < 10ms*

> **Three core decisions:**
> 1. **Cache flag definitions, not evaluation results.** SDK evaluates locally → `isEnabled()` never makes a network call.
> 2. **Stable hash bucketing + salt** for percentage rollouts → same user always lands in the same bucket, buckets are independent across flags.
> 3. **Deterministic replay** replaces evaluation logs → store version history (~50 MB) instead of logs (864 GB/day).

---

## 0. Background

E-commerce wants to ship a new feature gradually — start with 5% of US users, then expand if it works. That's what a feature flag is: a backend toggle that changes live behavior without a deploy.

**The problem:** an application asks *"is `new_checkout` on for user `U123`?"* — answer it in **10 ms**, **100,000 times per second**.

---

## 1. Core idea: SDK evaluates locally

Every `isEnabled()` going over HTTP → 100K QPS all slowed by 5 ms; if the server goes down, the business grinds to a halt. A config system shouldn't have this much blast radius.

**The approach:** the SDK downloads the full snapshot into memory at startup, then evaluates entirely locally. Like offline maps on a phone.

```
[ Snapshot Service ]  ───────►  [ SDK (in app process) ]  ───────►  [ your code ]
   only GET /snapshot              snapshot in memory                client.isEnabled("flag", user)
                                    isEnabled() pure memory · μs
                                    no network call
```

*Figure 1 — Snapshot only delivers configuration, never participates in evaluation.*

> **Design soul:** high-frequency evaluations run locally; low-frequency config downloads go through the server. The control plane (admin actions on flags, low frequency) and the data plane (SDK flag evaluation, high frequency) are fully separated.

---

## 2. Cross-region: how a change reaches every SDK worldwide

```
Write path — only deployed in us-east primary
┌──────────────────┐    ┌──────────────┐    ┌──────────────┐
│ Management API   │ ─► │ PostgreSQL   │ ─► │ CDC → Kafka  │ ── async fan-out ──►
└──────────────────┘    └──────────────┘    └──────────────┘

                          ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
                          │   us-east       │  │   eu-west       │  │   ap-southeast  │
                          │   (primary)     │  │   (~2s)         │  │   (~3s)         │
                          └─────────────────┘  └─────────────────┘  └─────────────────┘
                                    │                    │                    │
                              Kafka Consumer      Kafka Consumer      Kafka Consumer
                                    ▼                    ▼                    ▼
                              Redis replica       Redis replica       Redis replica
                                    ▼                    ▼                    ▼
                              Snapshot API       Snapshot API        Snapshot API
                                    ▼                    ▼                    ▼
                              CDN edge cache     CDN edge cache      CDN edge cache
                                    ▼                    ▼                    ▼
                              SDK (local)        SDK (local)         SDK (local)

  Each region runs the full stack: Consumer → Redis → Snapshot API → CDN → SDK
```

*Figure 2 — Writes only happen in primary, Kafka fans out, each region runs the full stack.*

**Key points:** writes only happen in us-east (PG primary); each region has an independent Redis + Snapshot API + CDN; the Management API publishes self-contained Kafka events (flagKey + full definition + scoped app IDs) after each write, so the Consumer in each region can merge directly into Redis without querying PostgreSQL — Kafka carries the data, not just a notification; cross-region Kafka latency is 1–5 s, acceptable for flag use cases.

**Note:** when an operator in eu-west/ap-southeast changes a flag, the Management API receives the request in-region then **forwards the write** to the us-east primary (extra ~200 ms RTT, acceptable). Reads still go to the local region. Full multi-master for a config system costs far more than it returns.

---

## 3. Cache update: Push + Poll

How does the SDK know there's a new version? Two wake-up mechanisms, but once awake they do the exact same thing — go fetch the full snapshot from the Snapshot API.

```
                ┌──────────────────────────────────┐
                │   Snapshot API (via CDN)         │
                │   the only source of truth      │
                └──────────────────────────────────┘
                              ▲      │
            SDK request        │      │   Return snapshot
                              │      ▼
                ┌──────────────────────────────────┐
                │              SDK                 │
                │   whichever wakes it up, same   │
                │   action: pull full snapshot     │
                └──────────────────────────────────┘
              ▲                                       ▲
              │ push                                  │ poll
              │                                       │
   ┌────────────────────┐                ┌────────────────────┐
   │   Channel 1: Push  │                │  Channel 2: Poll   │
   │ Trigger: Redis     │                │ Trigger: built-in  │
   │   Pub/Sub          │                │   timer            │
   │ Only critical      │                │ All flags          │
   │   flags · <1s      │                │ 30–60 s            │
   └────────────────────┘                └────────────────────┘
```

*Figure 3 — Push = Redis knocks, Poll = SDK's alarm clock. Different wake-up, identical data path.*

Push and Poll are **two wake-up mechanisms**. The data path (SDK ↔ Snapshot API) is identical.

Pub/Sub can drop messages under partition or network failure — the SDK's polling timer guarantees eventual consistency regardless. Worst-case staleness: 60 s.

---

## 4. SDK internals

App developers see one object with two methods. Inside there are three layers plus a side-channel sync controller.

```
   ┌─────────────── SDK boundary ──────────────────────────────────┐
   │                                                                │
   │   your code: client.isEnabled("flag", user)                   │
   │                          │                                     │
   │                          ▼                                     │
   │   ┌─────────────────────────────┐    ┌──────────────────┐      │
   │   │ ① Public API                │    │ Sync controller  │      │
   │   └─────────────────────────────┘    │                  │      │
   │                          │            │  ┌────────────┐  │      │
   │                          ▼            │  │ Ch.1 Push  │  │      │
   │   ┌─────────────────────────────┐    │  │ Redis      │  │      │
   │   │ ② Condition matcher         │    │  │ Pub/Sub    │  │      │
   │   │ rules + hash bucketing      │    │  └────────────┘  │      │
   │   │ → true/false                │    │  ┌────────────┐  │      │
   │   └─────────────────────────────┘    │  │ Ch.2 Poll  │  │      │
   │                          │            │  │ built-in   │  │      │
   │                          ▼            │  │ timer      │  │      │
   │   ┌─────────────────────────────┐    │  └────────────┘  │      │
   │   │ ③ Local cache               │◄───┤   writes to     │      │
   │   │ full snapshot in memory     │    │   cache         │      │
   │   │ read-only · ~2 MB · no      │    └──────────────────┘      │
   │   │ eviction                   │                                 │
   │   └─────────────────────────────┘                                 │
   └────────────────────────────────────────────────────────────────┘

   ①②③ are cross-language shared · there is one Reference Impl (Rust/Go) that runs
   the full conformance test vectors; Java / JS / Swift SDKs check against the Reference
   in CI, guaranteeing 100% identical hash function and rule semantics

   The sync controller picks different network modes per platform (Java uses HTTP/Redis,
   Node uses HTTP/WebSocket, mobile uses silent push) — it's the sidecar that writes
   to the cache
```

*Figure 4 — three read layers + right-side sync controller writes the cache.*

**Mobile extra:** process death is the norm, so relying only on the in-memory cache would waste a full snapshot download (~1 MB) on every cold start. Mobile SDKs persist the snapshot + ETag to local disk; on next cold start they read the local ETag and send `If-None-Match`. If the snapshot is unchanged, the response is 304 with empty body — saving ~99% of repeat downloads.

---

## 5. How the condition matcher works

Ops configures: *"20% of enterprise tenants in us-east-1."* When the user calls `isEnabled()`, two steps run:

```
   Step 1: rule match                              Step 2: hash bucketing
   ────────────────────                            ──────────────────────
   All rules must pass (AND)                       Determine if within %
   ─────────────────────                           ──────────────────────
   region = "us-east-1"?    user.region = "us-east-1"  ✓
                                                       "salt:checkout:U123" → xxhash64 % 100
   tenant = "enterprise"?   user.tenant = "enterprise" ✓
                                                                    bucket = 15, pct = 20
                                                                    15 < 20 → true ✓
```

Three users, different results:

| User  | region     | tenant     | Rules | Bucket | Verdict    | Result |
|-------|------------|------------|-------|--------|------------|--------|
| U123  | us-east-1  | enterprise | ✓     | 15     | 15 < 20 ✓  | true   |
| U456  | us-west-2  | enterprise | ✗     | —      | —          | false  |
| U789  | us-east-1  | enterprise | ✓     | 85     | 85 ≥ 20 ✗  | false  |

*Figure 5 — Rules narrow the population, hashing controls the proportion. Both steps must pass for true.*

### type × rules × percentage priority

| type           | rules handling             | percentage handling                       | final result                                  |
|----------------|----------------------------|-------------------------------------------|-----------------------------------------------|
| `boolean`      | skipped                    | skipped                                   | depends on `state`: `active` → true, else false |
| `targeting`    | AND must all pass          | skipped                                   | all pass → true; any fail → false             |
| `pct_rollout`  | AND must all pass          | hash(user, salt, flag) % 100 < pct (percentage) | all pass and bucket is small → true; else false |

Evaluation semantics across flag types: rules are always evaluated first (AND); on failure return false directly. The percentage (`pct`) field only takes effect when type is `pct_rollout`.

---

## 6. API design

**Table of contents**
- 6.1 Data plane (SDK pulls snapshot)
- 6.2 Control plane (ops edits flags)
- 6.3 Core model
- 6.4 `op` vocabulary for rules
- 6.5 definition examples
- 6.6 Operations API (`/explain`)

### 6.1 Data plane (for SDKs)

```
GET /api/v1/snapshot
  Header: Authorization: Bearer <api_key>     // app identity resolved from here
                                                // (api_key binds to an application; HMAC verification in §7.3)
  Header: X-Env: production                    // required: target environment
  Header: If-None-Match: "v42.7"               // ETag cache
  → 200  { "version":"v42.7", "flags":[/* only this app's visible flags */], "etag":"v42.7" }
  → 304  (empty body)
```

**How `env` is passed:** use the `X-Env` HTTP header to declare the target environment (production SDKs default to `production`; staging SDKs switch via env var at deploy time). Not a query parameter — that would make the CDN treat one URL as many cache keys and waste hit rate.

**How `app` is passed:** don't put it in the request — **resolve it from the api_key**. Each api_key is permanently bound to an application in the `applications` table. On request, we authenticate the api_key and obtain `app_id`, then filter the visible flags by that `app_id`. SDK code doesn't need to know which app it is, and ops don't need to configure an app name at SDK deploy time.

One endpoint, returns the **app-filtered** latest snapshot (typically ~50 flags, not the full 10k). Goes through CDN with ETag caching (like browser caching: server returns a version number; next request the SDK sends that number, and if unchanged the server replies 304 with an empty body; if changed, it sends the data).

**Implementation is a thin read layer:**

```go
// Snapshot API — pseudocode, this is literally it
func HandleSnapshot(w, r):
    api_key  = r.Header["Authorization"][len("Bearer "):]
    app      = authenticate(api_key)                        // HMAC verify + get app_id
    env      = require(r.Header["X-Env"])
    key_data = fmt.Sprintf("snapshot:%s:%d:data", env, app.id)   // one per (env, app)
    key_ver  = fmt.Sprintf("snapshot:%s:%d:version", env, app.id)

    // ETag clients send with quotes (HTTP spec), Redis stores without — must Trim before compare
    if strings.Trim(r.Header["If-None-Match"], "\"") == redis.Get(key_ver):
        w.WriteHeader(304)
        return
    flags = redis.Get(key_data)
    w.Write(JSON{version: redis.Get(key_ver), flags: flags, etag: redis.Get(key_ver)})
```

**Critical: version and data must be written atomically.** If SET happens in two calls, a reader can see new version + old data (or vice versa), tearing the SDK cache. Use a Lua script to write both keys atomically on the Redis side:

```lua
-- Consumer merges a single flag into the per-app snapshot (atomic INCR + SET)
-- KEYS[1]=version key, KEYS[2]=data key, ARGV[1]=json
redis.eval([[
    local v = redis.call('INCR', KEYS[1])
    redis.call('SET', KEYS[2], ARGV[1])
    return v
]], {key_ver, key_data}, {json_data})
```

**Design B — Kafka carries the full payload.** After a flag mutation, the Management API publishes a self-contained event to Kafka: `{ flagKey, env, op, definition, scopedAppIds }`. The Consumer in each region receives this event and merges the flag directly into each scoped app's Redis snapshot — no PostgreSQL query needed. The Lua script above atomically bumps the version (INCR) and persists the new snapshot (SET). The 60s @Scheduled sweep still calls the full `rebuildForApp` path as a fallback for missed events. One flag change triggers N Redis writes (one per scoped app) — the write-amplification cost of per-app isolation, accepted because per-app snapshots are what make CDN caching, bandwidth-constrained SDK pulls, and cross-team flag-key namespace isolation possible.

**Concrete example (what `checkout-service` sees):**

```json
// GET /api/v1/snapshot   Authorization: Bearer sk_live_xxx   X-Env: production   →   200 OK
{
  "version": "v42.7",
  "etag": "v42.7",
  "flags": [
    { "key":"new_checkout",    "definition":{ "type":"pct_rollout","pct":20,"salt":"a3f9",... }},
    { "key":"vip_discount",    "definition":{ "type":"boolean" }},
    { "key":"admin_debug",     "definition":{ "type":"targeting","rules":[...] }}
    // only the 50 flags visible to this app — not the full 10k
  ]
}
```

A filtered JSON, ~**20 KB–80 KB gzipped** (depends on the app's visible flag count and rule complexity). Excludes management fields like `version`, `state`, `owner`, `updated_at` — the SDK only needs `key` + `definition`. State is managed in a DB column and is not part of the snapshot.

**Do we still need CDN?** Yes. The CDN cache key includes the app (resolved from api_key), so it caches each `(env, app)` combination. Each cache key's traffic falls from 35M–50M req/day (single key) to 1/N of that. **Hot apps still hit 95%+, cold apps hit less.** With 100 apps the aggregate hit rate is ~85% (acceptable). At 1,000+ apps, layer it: only hot apps get CDN cache, cold apps go straight to origin (origin has an in-process LRU as fallback) to avoid wasting edge memory on cold keys.

### 6.2 Control plane (for backend admin)

```
POST   /api/v1/flags                       create
PUT    /api/v1/flags/{key}                 update (version optimistic lock;
                                              request must include If-Match: "<version>"; the update
                                              checks version is still the old value, preventing two
                                              operators from overwriting each other)
GET    /api/v1/flags                       search (pagination: ?cursor=&limit=)
GET    /api/v1/flags/{key}                 detail + history
DELETE /api/v1/flags/{key}                 soft delete (state → archived; snapshot no longer
                                              distributed; flags_history is preserved for explain)
POST   /api/v1/flags/{key}/rollout         rollout: 0% → 10% → 50% → 100% (specifically pct changes)
POST   /api/v1/flags/{key}/targeting       change rules array (specifically targeting conditions)
```

`/rollout` isn't a complex state machine — it's just a regular flag update that specifically changes `pct`:

```sql
-- POST /api/v1/flags/new_checkout/rollout   body: {"pct": 50, "expected_version": 41}

BEGIN;
  UPDATE flags SET version = version + 1,
      definition = jsonb_set(definition, '{pct}', '50'::jsonb),  -- ::jsonb cast is mandatory,
                                                                   -- otherwise stored as string
      updated_at = now()
  WHERE key = 'new_checkout' AND env = 'production' AND version = 41;
  -- 0 rows affected → return 412 Precondition Failed

  INSERT INTO flags_history (flag_key, env, version, definition, changed_by)
  VALUES ('new_checkout', 'production', 42,
          (SELECT definition FROM flags WHERE key='new_checkout'), 'alice');
COMMIT;
```

It's the same thing `PUT /api/v1/flags/{key}` does — optimistic lock update + write history. Splitting `/rollout` is purely for operator ergonomics; they don't need to know the whole JSON schema.

### 6.3 Core model

```sql
-- DB flags row (state/version go in columns, not in JSONB definition)
key="new_checkout_flow", env="production", version=42, state="active",
critical=false, owner="checkout-team", release="v3.2.0",
definition = {                              -- JSONB
  "type":"pct_rollout", "pct":20, "salt":"a3f9c2e1",
  "rules":[
    {"attr":"region","op":"in","values":["us-east-1"]},
    {"attr":"tenant","op":"eq","value":"enterprise"}
  ]
}
```

Rules are AND semantics. After all rules pass, the percentage (pct) takes effect — i.e. "of those who pass the rules, the pct%". **`state` and `version` are managed in DB columns, not in the definition JSON** — avoids dual-write drift.

### 6.4 `op` vocabulary in rules

| op                          | meaning                                  | example                                                |
|-----------------------------|------------------------------------------|--------------------------------------------------------|
| `eq` / `ne`                 | equals / not equals                      | `{"op":"eq","value":"enterprise"}`                     |
| `in` / `not_in`             | is in / not in set                       | `{"op":"in","values":["us-east-1","us-west-2"]}`       |
| `gt` / `gte` / `lt` / `lte` | numeric comparison                       | `{"op":"gte","value":18}`                              |
| `contains`                  | string contains                          | `{"op":"contains","value":"premium"}`                  |
| `starts_with`               | string prefix                            | `{"op":"starts_with","value":"ios_"}`                  |
| `regex`                     | regular expression (RE2 syntax, avoid ReDoS) | `{"op":"regex","value":"^vip_.*"}`                |
| `exists`                    | attribute is non-empty                   | `{"op":"exists"}`                                      |

The SDK must support the full op set; ops in the management UI can only pick from these, no free input.

SDK startup validates: encountering an unknown op fails fast, no silent ignore — otherwise 5% of flags using a new op with an old SDK would be silently turned off.

### 6.5 definition examples: three typical flags

**① Site-wide kill switch** — whether enabled is determined by the DB column `state`; `active` = true, `archived` = false. The definition only declares the type.

```json
{ "type": "boolean" }
```

**② Percentage rollout** — "show to 10% of users"; the salt guarantees each flag's rollout users are independent.

```json
{ "type": "pct_rollout", "pct": 10, "salt": "a3f9c2e1" }
```

**③ Targeting rules** — "admins only".

```json
{
  "type": "targeting",
  "rules": [{ "attr": "role", "op": "eq", "value": "admin" }]
}
```

**④ Combined** — rule filtering + percentage. The most common shape.

```json
{
  "type": "pct_rollout",
  "pct": 20,
  "salt": "a3f9c2e1",
  "rules": [
    { "attr": "region",    "op": "in", "values": ["us-east-1"] },
    { "attr": "tenant",    "op": "eq", "value": "enterprise" },
    { "attr": "beta_user", "op": "eq", "value": true }
  ]
}
```

The condition matcher is just a few dozen lines of if/else: run rules first (AND; one failure returns false), then handle by type — see the priority table above.

### 6.6 Operations API (`/explain`)

A user complains: "I didn't see the new checkout yesterday at 14:00" — operations can pull up this API and explain it.

**API shape (this is the one line you actually call):**

```
GET /api/v1/explain?flag=new_checkout&user=U123&at=2026-06-24T14:00:00Z

→ 200
{
  "enabled": false,                              ← what U123 saw at the time
  "reason":  "user bucket=15, but at 14:00 flag was 10% rollout, 15 ≥ 10 → false",
  "flag_version": 41,                            ← which version was in use
  "user_context": { "region": "us-west-2", "tenant": "enterprise" }   ← U123's profile at the time
}
```

**How it's computed:** we don't store "was it true or false at the time" (100K QPS × 86400 s = 864 GB/day, can't afford it). We only store each flag change (a few hundred per day at most). When asked, take **the flag definition from that moment** + **the user's profile from that moment** and re-run evaluation — the result matches exactly because evaluation is purely local (hash bucketing + rule AND); same inputs always produce same outputs. The entire history table over 7 years is only 50–500 MB.

**The one case we can't handle:** if the flag's salt was later changed — the salt used for replay is no longer the one in effect then, so the bucket number changes. In that case return "cannot answer exactly, check `audit_log`".

```
   🔍 Ask                          Get inputs                  Re-evaluate           Result
  ┌──────────┐                  ┌──────────────┐            ┌──────────────┐     ┌──────────┐
  │ What did │                  │ flag def     │            │ Same algo    │     │          │
  │ U123 see │ ──────────────► │ (v41) +      │ ─────────► │ as at that   │ ──► │  false   │
  │ 14:00    │                  │ U123 profile │            │ moment       │     │          │
  │ yesterday?                  └──────────────┘            └──────────────┘     └──────────┘

   ❌ Store every eval result: 864 GB/day        ✅ Store flag changes: 50–500 MB

   API lives on Management API (control plane), not Snapshot API (data plane)
```

*Figure 6 — Don't store every result, store every flag change, replay on demand.*

---

## 7. Database design

```sql
CREATE TABLE flags (
    key         VARCHAR(128),
    env         VARCHAR(16) NOT NULL DEFAULT 'production',
    version     INTEGER NOT NULL,               -- optimistic lock
    state       VARCHAR(16) NOT NULL DEFAULT 'active',  -- active | archived (not in JSONB definition)
    definition  JSONB NOT NULL,                 -- only type/pct/salt/rules
    critical    BOOLEAN NOT NULL DEFAULT false,
    owner       VARCHAR(64),
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (key, env),
    CHECK (env IN ('dev','staging','production','preprod')),
    CHECK (state IN ('active','archived'))
);

-- flags_history: composite primary key + FK to flags, prevents duplicate rows and orphan history
CREATE TABLE flags_history (
    flag_key    VARCHAR(128) NOT NULL,
    env         VARCHAR(16) NOT NULL,
    version     INTEGER NOT NULL,
    definition  JSONB NOT NULL,
    changed_by  VARCHAR(64),
    changed_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (flag_key, env, version),       -- one (flag, env) has exactly one row per version
    FOREIGN KEY (flag_key, env) REFERENCES flags(key, env) ON DELETE CASCADE
);
CREATE INDEX idx_hist_time ON flags_history(flag_key, env, changed_at DESC);
-- flags_history is never deleted: audit requires ≥7 years; data older than 1 year is
-- partitioned by month + archived to S3
```

When changing a flag, one transaction: optimistic-lock update on `flags` + INSERT into `flags_history`. The FK is the safety net — if ops mistypes a key (e.g. one extra letter) it raises an FK violation immediately instead of leaving orphan history rows.

### How do we handle environments?

The same flag at 50% in staging but 0% in production — different values per env. The simple approach: **add `env` to the flags table primary key**, so one row = one flag's configuration in one environment. With 4 environments (dev/staging/preprod/production), each flag has at most 4 rows.

### Flag → App visibility

```sql
-- Each flag in each env explicitly lists the apps that can see it;
-- a (flag, env, app) not in this table is implicitly invisible.
CREATE TABLE flag_app_scopes (
    flag_key  VARCHAR(128) NOT NULL,
    env       VARCHAR(16) NOT NULL,
    app_id    INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    PRIMARY KEY (flag_key, env, app_id)
);
CREATE INDEX idx_scope_app ON flag_app_scopes(app_id, env);
```

**Purpose:** when ops creates or modifies a flag, they check which apps can see it (e.g. "checkout-service and payment-service can see it, nothing else"). The Snapshot API resolves `app_id` from the api_key and JOINs this table to filter visible flags. 10k flags × 100 apps × 4 envs = 4M rows; a many-to-many table handles this trivially.

**Side benefit: solves flag key collisions across teams.** Two teams can independently name a flag `new_ui`; as long as each flag is bound to different apps, their snapshots don't collide. The original hard constraint that `flags` PK must be `(key, env)` becomes more relaxed — two flags with the same key but different app bindings don't interfere.

### Applications + credentials

```sql
-- Service-level identity: SDK uses api_key to call Snapshot API
-- User-level identity: comes via SSO/OIDC (not this table); audit_log.actor comes from the user layer
CREATE TABLE applications (
    id            SERIAL PRIMARY KEY,
    team          VARCHAR(64) NOT NULL,        -- owning team (cross-team name isolation)
    name          VARCHAR(64) NOT NULL,        -- "checkout-service"
    env_scope     VARCHAR(16) NOT NULL DEFAULT 'production',  -- key is bound to env, prevents misuse
    api_key_hash  VARCHAR(255) NOT NULL,       -- bcrypt/argon2 hash, no plaintext
    api_key_prefix VARCHAR(16) NOT NULL UNIQUE,-- first 8 chars of key, helps ops identify (not the full key)
    owner         VARCHAR(64),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (team, name),                       -- prevents different teams from colliding on service name
    CHECK (env_scope IN ('dev','staging','production','preprod'))
);
```

**Why hash:** plaintext api_key → anyone who can SELECT this table walks away with every service credential → any one service compromised and everything falls. bcrypt hashing makes verification slow (~100 ms), but the Snapshot API path can use an in-memory cache to cover the cost.

### How the API Key is used (HMAC hash)

An API key is a 256-bit high-entropy random string. Hash it with HMAC-SHA256(server_secret, raw_key) and store as `api_key_hash`. A DB dump can't recover plaintext. The `api_key_hash` column gets a UNIQUE constraint, so we look up directly by hash. `server_secret` lives in Vault, injected at runtime.

**Registration (one-time):** ops call `POST /api/v1/admin/applications` from the internal admin console (SSO login required), with body `(team, name, env_scope)`. Not a script, not exposed externally.

```python
# register-application(team, name, env_scope)
raw_key = "sk_live_" + crypto_random(24)              # 32 bytes plaintext, shown only this once
hash    = HMAC-SHA256(SERVER_SECRET, raw_key)         # microseconds
INSERT INTO applications (team, name, env_scope, api_key_hash, ...)
  VALUES (?, ?, ?, hash, ...);
return raw_key     # ops stores it in vault; DB never has plaintext again
```

**Per-request verification:**

```python
# Snapshot API entry
submitted = r.Header["Authorization"][len("Bearer "):]   # full key
hash      = HMAC-SHA256(SERVER_SECRET, submitted)
row       = SELECT id, env_scope, revoked_at
            FROM applications WHERE api_key_hash = hash  -- UNIQUE index, O(1)
if row is null:                       return 401
if row.revoked_at != null:            return 401         -- revoked
if row.env_scope != requested_env:    return 403         -- key bound to env, out of scope
# authenticated
```

**Rotation / revocation:**

```sql
-- Operator-initiated rotate
UPDATE applications SET revoked_at = now() WHERE id = ?;        -- old key dies immediately
INSERT INTO applications (..., api_key_hash)                    -- new key in DB
  VALUES (..., HMAC-SHA256(SERVER_SECRET, new_raw_key));
-- SDK sees 401, fetches new key from vault, zero downtime
```

After revocation the old hash stays in the table; we filter by `revoked_at`. Rotation is just `UPDATE revoked_at` + INSERT a new row.

### Audit log

```sql
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(64) NOT NULL,                 -- from user auth (SSO/OIDC), not api_key
    action      VARCHAR(32) NOT NULL,                 -- see CHECK below
    target      VARCHAR(128) NOT NULL,                -- flag_key or (app team/name)
    detail      JSONB,                                -- change details (cap 8 KB)
    ip_address  INET,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (action IN ('created','updated','rolled_out','targeted','archived','queried','login'))
);
CREATE INDEX idx_audit_actor_time ON audit_log(actor, created_at DESC);
CREATE INDEX idx_audit_target     ON audit_log(target, created_at DESC);
```

**The two identity layers must stay separated:** `api_key` is "which service is calling" (machine auth), `actor` is "which person is acting" (user auth). A service key cannot impersonate "alice" — the actor is injected by SSO/OIDC, the request comes with a JWT carrying the `sub` claim. The audit trail cannot be polluted by a compromised service.

**How long to keep them:** split into two classes — flag-change records kept 7 years (financial audits go back that far); "who queried a flag" read operations moved to S3 after 30 days (volume too high for the DB).

**How to query:** when an incident happens and you need to know who did what, filter by `(actor, time)` or `(target, time)` — two indexes cover every investigation path.

**Emergency rollback:** when a critical flag is mistakenly pushed to 100%, first `UPDATE flags SET state='archived'` (snapshot stops distributing this flag at second-level speed, **archive is not delete** — history is preserved), then call `/rollout` to roll back to the previous version.

### Sizing of the four main tables (assuming 10k flags × 4 envs)

| Table           | Rows                | Size              | Purpose                                                              |
|-----------------|---------------------|-------------------|----------------------------------------------------------------------|
| flags           | 40k                 | ~15 MB            | Currently active configuration (all envs)                             |
| flags_history   | 200k–2M             | 50–500 MB         | Version history. 10 versions is a conservative estimate for new flags; 50 versions is realistic for flags over 1 year old; data over 2 years needs partitioning + S3 archive |
| applications    | 100–500             | negligible        | Service credentials (hashed)                                         |
| audit_log       | ~10M/year           | ~5 GB/year        | Full audit; query-class logs archived monthly                        |
| **Hot total**   |                     | **~60 MB–1 GB**   |                                                                      |

---

## 8. Observability

| Ops question                          | Metric                                          | Alert rule                                          | Response                                          |
|---------------------------------------|-------------------------------------------------|------------------------------------------------------|---------------------------------------------------|
| "Is the SDK on the latest config?"    | `snapshot_version`, `snapshot_age_seconds`      | any instance `snapshot_age > 120s`                  | page oncall (some SDK is stuck on an old version) |
| "Is evaluation getting slow?"         | `eval_duration_us` histogram                    | p99 > 1 ms (design target is μs; 1 ms means rules exploded) | open ticket, find which flag's rules grew complex |
| "Which flags are used most?"          | `eval_total{flag, result}` counter              | 100+ flags with zero evaluations for 7 days         | suggest archive candidates                        |
| "Is cross-region sync healthy?"       | `redis_replication_lag_seconds`                 | > 30 s for 5 min                                    | page oncall (CDC/Kafka issue)                     |

SDK accumulates these in-process; Prometheus scrapes on a schedule. No network request on every `isEnabled()`.

---

## 9. Key decisions

| Decision                          | Why                                                                                                                       | Trade-off                                                                                                                                                                          |
|-----------------------------------|---------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Cache definitions, not results    | `isEnabled()` makes no network call; even if the admin backend is down, SDK keeps evaluating from local cache             | 2 MB memory per instance                                                                                                                                                          |
| Push + Poll dual channel          | Critical changes propagate in seconds; normal changes stay low-cost                                                      | Doesn't guarantee all SDKs see exactly the same config at the same instant (worst case 60 s lag)                                                                                  |
| Hash bucketing + salt             | Pure local; buckets are independent across flags                                                                         | Switching algorithms requires redeploying                                                                                                                                          |
| Deterministic replay              | 50–500 MB one-time vs 864 GB/day of logs                                                                                 | Doesn't capture user-context drift (covered by 1% sampling log)                                                                                                                    |
| Per-app Snapshot + CDN             | Each app sees only its visible flags; side benefit: cross-team flag key collisions are solved; CDN edge still caches on (env, app) dimension | CDN cache key count grows from 4 to 4×N; at 100 apps aggregate hit rate ~85%; at 1,000+ apps, layer it (only hot apps get CDN, cold apps go to origin) |

**How to evolve at 1,000+ apps:** when CDN cache hit rate drops below 70%, layer by app hotness — hot apps (top 100) keep full CDN cache; cold apps go straight to origin (with an in-process LRU fallback), avoiding N cold cache keys wasting edge memory.

---

## 10. Implementation order

1. **Flag model + versioned storage** — PostgreSQL three-table schema (`flags` / `flags_history` / `flag_app_scopes`) + migration
2. **Management API** — control plane CRUD (create / update / delete / search)
3. **Snapshot API** — GET + ETag, under 100 lines
4. **Condition matcher** — rule matching + hash bucketing, under 200 lines
5. **SDK prototype** — built-in polling timer + condition matcher
6. **Explain API** — historical version lookup + replay
7. **Push channel** — Redis Pub/Sub change signal, SDK pulls snapshot when notified

---

**Core insight — *local evaluation + deterministic replay* — a few hundred lines of code is enough to ship an end-to-end minimum viable version.**