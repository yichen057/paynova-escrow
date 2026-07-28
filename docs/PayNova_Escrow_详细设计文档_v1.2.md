# PayNova Escrow 1.0 — 详细设计文档

> **PayNova Escrow — Secure Payment and Ledger Platform**
> A portfolio-grade sandbox escrow payment platform. It implements production-inspired ledger, idempotency, concurrency control, transactional outbox, security, and audit patterns. It does not process or custody real funds.
>
> 版本：v1.2（正式冻结版：v1.1 六项阻塞修正 + claim_token fencing）｜ 日期：2026-07-25 ｜ 状态：**唯一实施依据（Source of Truth）**
> 《PayNova_1.0_开源调研与实现方案》第 3–6 节已被本文档取代，仅第 1–2 节作为调研记录保留。
> 技术栈：Java 17 · Spring Boot 3 · Spring Security (JWT) · Spring Data JPA · PostgreSQL · Flyway · springdoc-openapi · Docker Compose · JUnit 5 + Testcontainers · GitHub Actions

---

## 1. 范围与非目标

### 1.1 V1 做什么（全部真实运行，无写死数据）

注册/登录（JWT + RBAC）；模拟 USD 充值；创建担保订单；Fund / Release / Refund 全流程；余额、订单、账本记录查询；并发下防止余额重复消费；重复请求不重复扣款；Outbox 异步通知模拟商户（含重试）；管理员查看审计记录；Swagger 全流程演示；Docker 一键启动，可部署云端供招聘者操作。

### 1.2 V1 明确不做（README 的 Limitations 节）

真实银行卡/ACH/PayPal 资金；存储卡号/CVV；真实充值提现与资金保管；KYC/AML/制裁筛查/争议处理；对公众开放的商业支付服务；PCI-DSS 合规或 Money Transmitter 资质声明。**原因是监管与接入资质，不是代码能力**——README 如实说明。

Phase 2（冻结范围外，另立计划）：Stripe Test Mode 充值渠道（引回 connector/attempt 概念）、规则风控 + risk_events、部分退款、订单过期/取消、Splunk 摄入截图。

### 1.3 全局约束

金额一律 `BIGINT`、单位 cents；V1 单币种 USD，但 `currency` 列与按币种校验从第一天做对；`buyer_id != seller_id`；账本只追加；已终态订单不可迁移；状态修改与账本写入同一数据库事务。

**范围纪律：九张表、十二个 API、一个 PostgreSQL、一个 Spring Boot 应用。** 先把资金正确性、并发和失败恢复做扎实，再考虑前端、Stripe 或风控。

---

## 2. 职责表（本项目的灵魂页，同步进 README）

| # | 问题 | 解决机制 |
|---|---|---|
| 1 | 重复 HTTP 请求 | Idempotency Record + 唯一约束（§8） |
| 2 | 同一订单并发状态迁移 | 条件 UPDATE（CAS，affected_rows 必须 =1）（§6） |
| 3 | 余额双花 | PostgreSQL 悲观行锁，按账户 ID 升序加锁（§7） |
| 4 | 资金守恒 | 双分录账本 + 系统账户，按币种全局 SUM=0（§5） |
| 5 | 状态与账本一致 | 单数据库事务原子提交（§7） |
| 6 | 支付成功但通知丢失 | Transactional Outbox + Webhook Worker（§10） |
| 7 | 安全追踪 | Audit Event + 结构化 JSON 日志（§11） |

面试标准表述：**幂等记录处理请求级重复，条件更新处理领域状态竞争，悲观锁保护余额，数据库事务保证订单、账本和事件原子提交。** 补充区分：对只要求 ACK 的回调场景，状态 CAS 可充当重复检测；但账本与事件等副作用必须与首次成功的状态迁移原子提交（Phase 2 接 Stripe webhook 时适用）。

---

## 3. 模块架构

单仓库、单 Spring Boot 应用，按 package 分模块（模块间只经 service 接口调用，不跨模块碰 Repository）：

```
com.paynova
 ├── auth         # 注册/登录/JWT/RBAC(USER, ADMIN)。买家/卖家是订单上的关系，不是全局角色
 ├── account      # 账户与余额（含系统账户），充值
 ├── escrow       # 担保订单 + 状态机（唯一的资金编排入口）
 ├── ledger       # ledger_transactions + ledger_entries，只暴露 post(transaction) 一个写入口
 ├── idempotency  # 幂等记录，@IdempotentOperation 拦截器
 ├── outbox       # outbox_events 写入 + @Scheduled Webhook Worker
 ├── audit        # audit_events + JSON 日志 appender
 └── mockmerchant # 模拟商户接收端（验签 + event_id 去重）
```

PayNova 目标架构（drawio）→ V1 映射表沿用上一份方案文档 §4.2，写进 README。

---

## 4. 数据模型（9 张表，Flyway V1__init.sql）

```sql
CREATE TABLE users (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email         VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,            -- BCrypt
  role          VARCHAR(10)  NOT NULL DEFAULT 'USER'
                CHECK (role IN ('USER','ADMIN')),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- BIGINT: 锁排序依据
  owner_user_id BIGINT REFERENCES users(id),      -- 系统账户为 NULL
  type          VARCHAR(10) NOT NULL CHECK (type IN ('USER','SYSTEM')),
  name          VARCHAR(64) NOT NULL UNIQUE,      -- user:{id}:wallet / system:cash_in ...
  currency      CHAR(3)     NOT NULL DEFAULT 'USD',
  balance       BIGINT      NOT NULL DEFAULT 0,   -- cents，余额快照
  allow_negative BOOLEAN    NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0)
);
-- 系统账户（Flyway seed）：
--   system:cash_in   allow_negative=TRUE   （外部资金占位，唯一允许负余额的账户）
--   system:escrow    allow_negative=FALSE  （不变量：托管账户为负 = 放了没收到的钱，DB CHECK 兜底）
--   system:cash_out  allow_negative=FALSE
-- 用户注册时在同一事务内创建 user:{id}:wallet（allow_negative=FALSE）
-- Release/Refund 仍须锁定 system:escrow 并检查余额

CREATE TABLE escrow_orders (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  buyer_id    BIGINT NOT NULL REFERENCES users(id),
  seller_id   BIGINT NOT NULL REFERENCES users(id),
  amount      BIGINT NOT NULL CHECK (amount > 0),
  currency    CHAR(3) NOT NULL DEFAULT 'USD',
  description VARCHAR(500),
  status      VARCHAR(10) NOT NULL DEFAULT 'CREATED'
              CHECK (status IN ('CREATED','FUNDED','RELEASED','REFUNDED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT buyer_not_seller CHECK (buyer_id <> seller_id)
);

CREATE TABLE ledger_transactions (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  type           VARCHAR(20) NOT NULL
                 CHECK (type IN ('TOP_UP','ESCROW_FUND','ESCROW_RELEASE','ESCROW_REFUND')),
  reference_type VARCHAR(20) NOT NULL,            -- 'ESCROW_ORDER' | 'TOP_UP'
  reference_id   VARCHAR(40) NOT NULL,            -- 业务单据双向可追溯 (Fineract entity 思想)
  reversal_of    UUID REFERENCES ledger_transactions(id),  -- 冲正引用（退款用）
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_ledger_business UNIQUE (type, reference_type, reference_id)
  -- 账本层最后一道重复记账防线：同一业务单据同一类型只能记一次账，
  -- 不单独依赖 API 幂等和订单 CAS
);

CREATE TABLE ledger_entries (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id UUID   NOT NULL REFERENCES ledger_transactions(id),
  account_id     BIGINT NOT NULL REFERENCES accounts(id),
  direction      VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount         BIGINT NOT NULL CHECK (amount > 0),   -- 恒为正，方向由 direction 表达
  currency       CHAR(3) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id, id);
CREATE INDEX idx_entries_txn     ON ledger_entries(transaction_id);
-- 不可变性：JPA 实体无 setter、Repository 不暴露 save 更新路径；
-- 加固（可选加分项）：BEFORE UPDATE OR DELETE 触发器直接 RAISE EXCEPTION

CREATE TABLE idempotency_records (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT NOT NULL REFERENCES users(id),
  idempotency_key UUID   NOT NULL,
  request_hash    CHAR(64) NOT NULL,              -- SHA-256(method + path + canonical body)
  status          VARCHAR(12) NOT NULL DEFAULT 'IN_PROGRESS'
                  CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_status SMALLINT,
  response_body   JSONB,
  resource_id     VARCHAR(40),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_idem UNIQUE (user_id, idempotency_key)
);

CREATE TABLE audit_events (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  event_type            VARCHAR(40) NOT NULL,
  correlation_id        UUID NOT NULL,
  actor_id              BIGINT,
  actor_role            VARCHAR(10),
  escrow_id             UUID,
  ledger_transaction_id UUID,
  source_ip             VARCHAR(45),
  old_status            VARCHAR(10),
  new_status            VARCHAR(10),
  amount                BIGINT,
  currency              CHAR(3),
  result                VARCHAR(10) NOT NULL,     -- SUCCESS / REJECTED / ERROR
  details               JSONB
);
CREATE INDEX idx_audit_time ON audit_events(occurred_at);

CREATE TABLE outbox_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),   -- 即 event_id，消费端去重键
  aggregate_type  VARCHAR(20) NOT NULL,           -- 'ESCROW_ORDER'
  aggregate_id    VARCHAR(40) NOT NULL,
  event_type      VARCHAR(30) NOT NULL,           -- escrow.funded / escrow.released / escrow.refunded
  payload         JSONB NOT NULL,
  status          VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','PROCESSING','DELIVERED','FAILED')),
  attempt_count   INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_at      TIMESTAMPTZ,
  locked_until    TIMESTAMPTZ,                    -- 认领租约，过期可被接管
  claim_token     UUID,                           -- fencing token：只有当前租约持有者能写回结果
  delivered_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_poll ON outbox_events(status, next_attempt_at);

CREATE TABLE webhook_receipts (                    -- 第 9 张表：消费端（mockmerchant）去重
  event_id      UUID PRIMARY KEY,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload_hash  CHAR(64) NOT NULL
);
-- mockmerchant 同一事务内：INSERT webhook_receipts ON CONFLICT (event_id) DO NOTHING
--   affected_rows=1 → 执行业务逻辑；affected_rows=0 → 重复投递，直接 200
-- 持久化去重，重启不丢——这是破坏性实验 #5 能证明"恰好一次效果"的前提
```

---

## 5. 账本模型与系统账户

**记账约定（简化钱包约定，README 声明非完整 GAAP 科目体系）**：`CREDIT` = 资金流入该账户，`DEBIT` = 资金流出该账户；账户余额 ≡ Σ(CREDIT) − Σ(DEBIT)。`accounts.balance` 是快照，账本是事实来源，可随时重算对账。

**两个不变量（都有测试断言）**：
- 单笔：每个 `ledger_transaction` 内 Σ(DEBIT 金额) = Σ(CREDIT 金额)，且至少 2 条 entry（服务层校验，违反抛异常回滚）。
- 全局：**按币种分组**后 Σ(CREDIT) − Σ(DEBIT) = 0（不得跨币种混加）。

**资金流全景（V1 仅此 4 种，全部经 `LedgerService.post()` 唯一入口）**：

| 业务 | DEBIT（流出） | CREDIT（流入） |
|---|---|---|
| 模拟充值 | system:cash_in | user:{buyer}:wallet |
| Fund | user:{buyer}:wallet | system:escrow |
| Release | system:escrow | user:{seller}:wallet |
| Refund（reversal_of→原 FUND txn） | system:escrow | user:{buyer}:wallet |

README 注明：*`system:cash_in` is a placeholder for external funding sources in this demo environment; it does not mean the platform creates money. Sandbox funds — no real monetary value.*

---

## 6. 状态迁移矩阵

| 当前状态 ↓ / 动作 → | fund | release | refund |
|---|---|---|---|
| CREATED | → FUNDED | ✗ 409 | ✗ 409 |
| FUNDED | ✗ 409 | → RELEASED | → REFUNDED |
| RELEASED（终态） | ✗ 409 | ✗ 409 | ✗ 409 |
| REFUNDED（终态） | ✗ 409 | ✗ 409 | ✗ 409 |

实现：`UPDATE escrow_orders SET status=:new, updated_at=now() WHERE id=:id AND status=:expectedOld`，**affected_rows 必须 == 1**；== 0 时抛 `IllegalStateTransitionException` → 409 `ILLEGAL_STATE_TRANSITION`，该分支**零副作用**。权限：fund/release 仅 buyer；refund 仅 seller 或 admin；越权 → 403（先查权限再 CAS）。订单过期与 CREATED 取消进 Phase 2。

---

## 7. 锁顺序与事务边界

**加锁规则（硬规则，代码注释引用本节）**：
1. 需要锁定多个账户时，一律按 `accounts.id` **升序**执行 `SELECT ... FOR UPDATE`（`AccountLockService.lockAll(ids)` 内部排序，业务代码不允许自行加锁）——防死锁；系统账户同样参与排序。
2. 锁只加在 `accounts` 行上；`escrow_orders` 不加行锁，其并发由 CAS 承担（职责表 #2/#3 分工）。
3. `SET LOCAL lock_timeout = '5s'`（事务级，**不用连接级**——连接池复用会泄漏配置），超时映射为 503 `LOCK_TIMEOUT`。

**以 fund 为例的完整事务边界（其余操作同构）**：

```
@Transactional
1. INSERT idempotency_record ... ON CONFLICT DO NOTHING（原生 SQL，见 §8）
   affected_rows=0 → 走幂等决策表分支，不再产生任何副作用
2. 权限校验（当前用户 == buyer）
3. lockAll([buyer_wallet_id, system_escrow_id]) -- 按 id 升序 FOR UPDATE
4. 余额检查：buyer_wallet.balance >= amount，否则抛 → 422（回滚，幂等记录一并回滚）
5. CAS: CREATED → FUNDED，affected_rows == 1，否则抛 → 409（回滚）
6. LedgerService.post(ESCROW_FUND, entries[债:buyer_wallet, 贷:system_escrow])
   + 更新两个账户 balance 快照
7. INSERT audit_event, INSERT outbox_event      -- 与状态、账本同事务
8. UPDATE idempotency_record: COMPLETED + response_status + response_body
COMMIT   -- 8 步一起生效或一起消失
```

隔离级别：READ COMMITTED（默认）即可——正确性由行锁 + CAS + 唯一约束保证，不依赖更高隔离级别（面试考点：能解释为什么不需要 SERIALIZABLE）。

---

## 8. 幂等决策表

采用**方案 A：幂等记录与业务操作同一事务**。V1 不实现 lease/recovery；`IN_PROGRESS/COMPLETED` 字段表达生命周期，但**不声称并发请求能即时读到 IN_PROGRESS**（PostgreSQL 下第二个相同 Key 的 INSERT 会阻塞等待首个事务落定）。

**写入协议（必须原生 SQL，JdbcTemplate 或 @Query(nativeQuery)，禁止 `JpaRepository.save()` + 捕获异常）**——Hibernate 事务内的唯一约束异常会把事务标记 rollback-only，捕获后也无法继续查询缓存响应：

```sql
INSERT INTO idempotency_records (user_id, idempotency_key, request_hash, status)
VALUES (?, ?, ?, 'IN_PROGRESS')
ON CONFLICT (user_id, idempotency_key) DO NOTHING;
```

- `affected_rows = 1`：本请求获得执行权，继续业务逻辑。
- `affected_rows = 0`：读取已有记录 → 比较 `request_hash` → 按决策表返回缓存或 409。
- 首个事务仍在执行时，第二个 INSERT 在唯一索引处等待其落定；首个回滚则第二个插入成功接管。
- **不变量推论**：方案 A 下记录与业务同事务、COMPLETED 更新发生在提交前，因此**已提交的记录必然是 COMPLETED**——affected_rows=0 后读到 IN_PROGRESS 即不变量被破坏，直接断言/告警。

| 场景 | 行为 |
|---|---|
| 相同 Key + 相同 request_hash + 已 COMPLETED | 返回首次的 response_status + response_body（透传，不重新执行） |
| 相同 Key + 不同 request_hash | 409 `IDEMPOTENCY_KEY_REUSED` |
| 相同 Key + 首次请求执行中 | INSERT 阻塞等待首个事务；`lock_timeout` 超时 → 409 `REQUEST_IN_PROGRESS` |
| 首次请求事务回滚 | 幂等记录随之消失，客户端可重试（"相同请求重试尽量得到相同结果"） |
| 缺少 Idempotency-Key 头（资金写接口） | 400 `IDEMPOTENCY_KEY_REQUIRED` |

范围：`POST /wallets/top-ups`、`POST /escrows`、`fund/release/refund` 五个写接口强制要求 `Idempotency-Key: <UUID>`。`request_hash = SHA-256(method + path + canonical_json(body))`，canonical_json 规则：UTF-8、字段按字典序排序、数字规范化表示（无多余小数位/科学计数法）。记录保留 24h 后由清理任务删除——**API 契约（写进 README）：24h 后相同 Key 视为新请求**，对 top-up 意味着可能再次充值，客户端重试窗口以此为限。

---

## 9. API 契约（12 个）

通用错误体：`{ "error_code": "...", "message": "...", "correlation_id": "..." }`。认证：除 register/login/webhook 外全部 `Authorization: Bearer <JWT>`。

| # | API | 认证/角色 | 成功 | 关键错误 |
|---|---|---|---|---|
| 1 | POST /api/auth/register | 公开 | 201（同事务创建 USD 钱包） | 409 EMAIL_EXISTS；422 弱密码 |
| 2 | POST /api/auth/login | 公开 | 200 {token} | 401 BAD_CREDENTIALS |
| 3 | POST /api/wallets/top-ups ⚿ | USER | 201 | 400/409/422（幂等表） |
| 4 | GET /api/accounts/me | USER | 200 余额+账户 | — |
| 5 | GET /api/accounts/me/transactions | USER | 200 分页账本流水 | — |
| 6 | POST /api/escrows ⚿ | USER(=buyer) | 201 | 422 SELLER_IS_BUYER / SELLER_NOT_FOUND / INVALID_AMOUNT |
| 7 | POST /api/escrows/{id}/fund ⚿ | buyer | 200 | 403；404；409 ILLEGAL_STATE_TRANSITION；422 INSUFFICIENT_FUNDS；503 LOCK_TIMEOUT |
| 8 | POST /api/escrows/{id}/release ⚿ | buyer | 200 | 403；404；409 |
| 9 | POST /api/escrows/{id}/refund ⚿ | seller/ADMIN | 200 | 403；404；409 |
| 10 | GET /api/escrows/{id} | 参与方/ADMIN | 200 | 403；404 |
| 11 | GET /api/admin/audit-events | ADMIN | 200 分页 | 403 |
| 12 | POST /api/webhooks/mock-merchant | HMAC 验签 | 200 | 401 INVALID_SIGNATURE |

⚿ = 强制 `Idempotency-Key`。状态码语义备忘：401 未认证 / 403 已认证无权限 / 409 资源状态或幂等冲突 / 422 请求合法但业务无法处理。

---

## 10. Outbox 重试策略（认领协议）

**核心原则：HTTP 调用绝不发生在数据库事务内**——否则一次 30s 网络超时就占死连接和行锁，单实例也是 bug。三段式：

```
状态机：PENDING → PROCESSING → DELIVERED
                            ↘ PENDING（回队等待重试，next_attempt_at 按阶梯后移）
                            ↘ FAILED（第 7 次失败，人工介入位）

短事务①认领：UPDATE outbox_events
             SET status='PROCESSING', claim_token=:newUuid,
                 claimed_at=now(), locked_until=now()+'60s'
             WHERE id IN (SELECT id FROM outbox_events
                          WHERE status='PENDING' AND next_attempt_at <= now()
                          ORDER BY next_attempt_at LIMIT 10
                          FOR UPDATE SKIP LOCKED)
             → COMMIT（锁立即释放；每次认领生成新 claim_token）
事务外发送：HTTP POST 商户 URL（timeout 10s）
短事务②条件写回（fencing）：
             UPDATE outbox_events
             SET status='DELIVERED', delivered_at=now()
             WHERE id=:id AND status='PROCESSING' AND claim_token=:myToken;
             -- affected_rows=0 → 租约已被接管，本 Worker 必须放弃写入，静默退出
             失败分支同理带 claim_token 条件：
               attempt_count+1；≤6 → 回 PENDING + 阶梯 next_attempt_at
                                     （1m → 5m → 10m → 30m → 1h → 6h）
                                =7 → FAILED
```

- **租约接管（Reaper）**：`@Scheduled` 扫描 `status='PROCESSING' AND locked_until < now()`（认领后、写结果前崩溃或卡死的孤儿）→ 重置回 PENDING 并**清除 claim_token / claimed_at / locked_until**。被接管的慢 Worker 归来后条件写回 affected_rows=0，自动放弃。
- **语义边界（面试必讲的精确表述）**：claim_token 保护的是**状态写回**，不是投递本身——被接管 Worker 的慢 HTTP 请求仍可能在新持有者成功之后到达商户端，投递语义始终是 at-least-once；"恰好一次效果"由消费端 `webhook_receipts` 持久化去重承担，两者缺一不可。
- 签名：`X-PayNova-Signature: HMAC-SHA256(secret, payload)`；`X-PayNova-Event-Id: {id}`。消费端验签 + `webhook_receipts` 表持久化去重 → **at-least-once 投递 + 消费端幂等 = 恰好一次效果**（面试表述）。
- 语义：进程在"事务提交后、发送前"崩溃 → 事件仍在表中，重启后照常投递（破坏性实验 #5）；"认领后、发送前"崩溃 → 租约过期被 Reaper 接管。
- V1 仍只跑单 Worker 实例；认领租约 + fencing token 使**多实例认领安全**（Kill Bill DB 队列同款模式 + 条件写回）。

---

## 11. 审计与日志红线

`audit_events` 记录：注册、登录成败、充值、订单创建、fund/release/refund 成败、非法迁移尝试、越权尝试、webhook 投递终态。JSON 日志（Logback + logstash-encoder）字段：`timestamp, event_type, correlation_id, actor_id, actor_role, escrow_id, ledger_transaction_id, source_ip, old_status, new_status, amount, currency, result`。

**审计的事务边界（否则失败审计随业务回滚一起消失）**：
- 成功审计：与业务事务一起提交（§7 第 7 步）。
- 失败/拒绝审计（余额不足、非法迁移、越权）：业务事务已回滚后，在异常处理边界（`@ControllerAdvice` / service 边界）用 **`REQUIRES_NEW` 独立事务**写入。
- JSON 安全日志：无条件输出，即使数据库审计写入失败。

**红线（代码评审检查项）**：绝不记录 JWT、密码（含错误密码原文）、`Authorization` 头、幂等响应体中的敏感字段。`correlation_id` 由 Filter 生成、贯穿请求→审计→日志→webhook payload。定位：**SIEM-ready 而非 SIEM-dependent**——应用不依赖 Splunk 运行；README 提供字段规范 + 2 条示例 Splunk 查询（暴力 fund 尝试、非法状态迁移聚集），摄入截图为可选加分项。

---

## 12. 六阶段增量实现计划（Step 0–5）

开发协议（三条约束）：① 每步开工前 Claude 给 2 个设计选项+权衡，**Jun 拍板**，代码不替人做决定；② 每步完成后 Jun 做破坏性实验；③ Jun 能独立解释并修改后才提交 commit。Commit message 如实标注 AI 辅助（`Co-authored-by`），不伪造历史。

| Step | 内容 | 测试 | 破坏性实验 |
|---|---|---|---|
| 0 骨架 | 工程/Flyway 9 表/JWT/Docker Compose/CI（Claude 直接交付，2 commits） | 上下文启动 + 登录冒烟 | — |
| 1 状态机 | escrow CRUD + 纯 CAS 迁移规则（**不开放资金端点**——fund/release 等到 Step 4 账本+锁就位后才接线进主分支） | 迁移矩阵全用例：合法迁移成功、4 状态×3 动作非法组合全 409 | 删掉 `AND status=` 旧值条件 → 制造双 release |
| 2 账本 | LedgerService.post + 系统账户（allow_negative）+ 充值 + 注册建钱包 | 单笔平衡断言；按币种全局 SUM=0；entry≥2；uq_ledger_business 重复记账拒绝 | 删平衡校验 → 提交单边分录，看全局对账测试抓住它 |
| 3 幂等 | ON CONFLICT 写入协议 + 决策表 5 场景 | 同 Key×10 次仅 1 次资金变动；异 hash 409；回滚后可重试 | 删唯一约束 → 并发提交相同 Key |
| 4 锁 | lockAll 升序 + fund/release/refund 资金端点上线 | **$100 并发 2×$80 恰成功 1 笔**（Testcontainers PG）；对 `lockAll()` 以相反输入顺序多线程调用，验证内部按 ID 排序、无死锁 | 删行锁 → 复现双花；乱序加锁 → 复现死锁（**手动实验，不进 CI**——死锁测试天然抖动） |
| 5 Outbox | 认领协议 Worker（claim_token 条件写回）+ Reaper + mockmerchant（webhook_receipts 去重） | webhook 首次失败仍最终送达且不重复入账；租约过期接管；**被接管的慢 Worker 条件写回 affected_rows=0**；FAILED 终态 | 事务提交后、发送前 kill -9，重启验证事件不丢；认领后、发送前 kill -9，验证 Reaper 接管 |

测试分层：单元测试不碰数据库；集成/并发测试全部 Testcontainers PostgreSQL（锁与隔离行为必须真库验证，H2 不用）。

时间线：第一个周末 = Step 0–4 跑通 + 破坏性实验；晚 2 = Step 5；晚 3 = 审计/JWT 细化；晚 4 = README（职责表、双架构图、测试输出截图）+ CI 徽章 + demo GIF；晚 5 = 部署（Render/AWS）+ 录屏；**8/8 冻结**，8 月开投。

---

*本文档为五轮交叉评审后的正式冻结方案。v1.1：6 项阻塞修正（webhook_receipts 第九表、幂等 ON CONFLICT 原生写入协议、Outbox 认领租约协议、失败审计 REQUIRES_NEW、allow_negative 账户约束、Step 4 死锁测试改为 lockAll 直测）。v1.2：Outbox claim_token fencing 条件写回。本文档为唯一实施依据；范围变更需先改本文档。*
