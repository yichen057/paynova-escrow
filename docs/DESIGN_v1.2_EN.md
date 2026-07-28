# PayNova Escrow 1.0 — Detailed Design (v1.2)

> **PayNova Escrow — Secure Payment and Ledger Platform**
> A portfolio-grade sandbox escrow payment platform. It implements production-inspired ledger, idempotency, concurrency control, transactional outbox, security, and audit patterns. It does not process or custody real funds.
>
> Version: v1.2 (formally frozen: v1.1 six blocking fixes + claim_token fencing) | Date: 2026-07-25 | Status: **sole implementation basis (Source of Truth)**
> Sections 3–6 of the earlier document "PayNova 1.0 — Open-Source Research and Implementation Plan" are superseded by this document; only its Sections 1–2 are retained as research notes.
> Tech stack: Java 17 · Spring Boot 3 · Spring Security (JWT) · Spring Data JPA · PostgreSQL · Flyway · springdoc-openapi · Docker Compose · JUnit 5 + Testcontainers · GitHub Actions
> Chinese original: PayNova_Escrow_详细设计文档_v1.2.md (same directory)

---

## 1. Scope and Non-Goals

### 1.1 What V1 does (everything runs for real, no hard-coded data)

Registration/login (JWT + RBAC); simulated USD top-up; creating escrow orders; the full Fund / Release / Refund flow; querying balances, orders, and ledger records; preventing double-spending of balances under concurrency; ensuring duplicate requests never cause duplicate charges; Outbox-based asynchronous notification of a mock merchant (with retries); admin access to audit records; end-to-end Swagger walkthrough; one-command Docker startup, deployable to the cloud for recruiters to try hands-on.

### 1.2 What V1 explicitly does not do (the README's Limitations section)

Real card/ACH/PayPal funds; storing card numbers/CVV; real deposits, withdrawals, or fund custody; KYC/AML/sanctions screening/dispute handling; a commercial payment service open to the public; any claim of PCI-DSS compliance or a Money Transmitter license. **The reason is regulation and licensing, not coding ability** — the README states this candidly.

Phase 2 (outside the frozen scope, planned separately): a Stripe Test Mode top-up channel (reintroducing the connector/attempt concepts), rule-based risk controls + risk_events, partial refunds, order expiry/cancellation, Splunk ingestion screenshots.

### 1.3 Global constraints

All monetary amounts are `BIGINT` in cents; V1 is single-currency USD, but the `currency` column and per-currency validation are in place from day one; `buyer_id != seller_id`; the ledger is append-only; orders in a terminal state cannot transition; status changes and ledger writes commit in the same database transaction.

**Scope discipline: nine tables, twelve APIs, one PostgreSQL, one Spring Boot application.** Get fund correctness, concurrency, and failure recovery solid first; only then consider a frontend, Stripe, or risk controls.

---

## 2. Responsibility Table (the soul of this project; mirrored into the README)

| # | Problem | Mechanism |
|---|---|---|
| 1 | Duplicate HTTP requests | Idempotency Record + unique constraint (§8) |
| 2 | Concurrent state transitions on the same order | Conditional UPDATE (CAS, affected_rows must be 1) (§6) |
| 3 | Balance double-spend | PostgreSQL pessimistic row locks, acquired in ascending account-ID order (§7) |
| 4 | Conservation of funds | Double-entry ledger + system accounts, global SUM = 0 per currency (§5) |
| 5 | Status/ledger consistency | Atomic commit in a single database transaction (§7) |
| 6 | Payment succeeds but notification is lost | Transactional Outbox + Webhook Worker (§10) |
| 7 | Security traceability | Audit Event + structured JSON logging (§11) |

Standard explanation: **idempotency records handle request-level duplication, conditional updates handle domain-state races, pessimistic locks protect balances, and a database transaction guarantees atomic commit of order, ledger, and events.** A further distinction: for callback scenarios that only require an ACK, the status CAS can double as duplicate detection; but side effects such as ledger entries and events must commit atomically with the first successful state transition (relevant in Phase 2 when wiring up Stripe webhooks).

---

## 3. Module Architecture

Single repository, single Spring Boot application, modularized by package (modules interact only through service interfaces; no module touches another module's Repository):

```
com.paynova
 ├── auth         # Registration/login/JWT/RBAC (USER, ADMIN). Buyer/seller are relationships on an order, not global roles
 ├── account      # Accounts and balances (including system accounts), top-up
 ├── escrow       # Escrow orders + state machine (the sole orchestration entry point for funds)
 ├── ledger       # ledger_transactions + ledger_entries, exposing exactly one write entry point: post(transaction)
 ├── idempotency  # Idempotency records, @IdempotentOperation interceptor
 ├── outbox       # outbox_events writes + @Scheduled Webhook Worker
 ├── audit        # audit_events + JSON log appender
 └── mockmerchant # Mock merchant receiver (signature verification + event_id deduplication)
```

The PayNova target architecture (drawio) → V1 mapping table carries over §4.2 of the previous plan document and goes into the README.

---

## 4. Data Model (9 tables, Flyway V1__init.sql)

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
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- BIGINT: basis for lock ordering
  owner_user_id BIGINT REFERENCES users(id),      -- NULL for system accounts
  type          VARCHAR(10) NOT NULL CHECK (type IN ('USER','SYSTEM')),
  name          VARCHAR(64) NOT NULL UNIQUE,      -- user:{id}:wallet / system:cash_in ...
  currency      CHAR(3)     NOT NULL DEFAULT 'USD',
  balance       BIGINT      NOT NULL DEFAULT 0,   -- cents, balance snapshot
  allow_negative BOOLEAN    NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0)
);
-- System accounts (Flyway seed):
--   system:cash_in   allow_negative=TRUE   (placeholder for external funds; the only account allowed a negative balance)
--   system:escrow    allow_negative=FALSE  (invariant: a negative escrow account = money paid out that was never received; the DB CHECK is the backstop)
--   system:cash_out  allow_negative=FALSE
-- On user registration, user:{id}:wallet (allow_negative=FALSE) is created in the same transaction
-- Release/Refund must still lock system:escrow and check its balance

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
  reference_id   VARCHAR(40) NOT NULL,            -- bidirectional traceability to the business document (Fineract entity concept)
  reversal_of    UUID REFERENCES ledger_transactions(id),  -- reversal reference (used by refunds)
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_ledger_business UNIQUE (type, reference_type, reference_id)
  -- The ledger layer's last line of defense against duplicate posting: the same business document
  -- can be posted at most once per type — it does not rely solely on API idempotency and the order CAS
);

CREATE TABLE ledger_entries (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id UUID   NOT NULL REFERENCES ledger_transactions(id),
  account_id     BIGINT NOT NULL REFERENCES accounts(id),
  direction      VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount         BIGINT NOT NULL CHECK (amount > 0),   -- always positive; sign is expressed by direction
  currency       CHAR(3) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id, id);
CREATE INDEX idx_entries_txn     ON ledger_entries(transaction_id);
-- Immutability: JPA entities have no setters; the Repository exposes no update path via save;
-- hardening (optional bonus): a BEFORE UPDATE OR DELETE trigger that directly RAISEs EXCEPTION

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
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),   -- i.e. event_id, the consumer-side deduplication key
  aggregate_type  VARCHAR(20) NOT NULL,           -- 'ESCROW_ORDER'
  aggregate_id    VARCHAR(40) NOT NULL,
  event_type      VARCHAR(30) NOT NULL,           -- escrow.funded / escrow.released / escrow.refunded
  payload         JSONB NOT NULL,
  status          VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','PROCESSING','DELIVERED','FAILED')),
  attempt_count   INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_at      TIMESTAMPTZ,
  locked_until    TIMESTAMPTZ,                    -- claim lease; once expired, the claim can be taken over
  claim_token     UUID,                           -- fencing token: only the current lease holder may write back the result
  delivered_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_poll ON outbox_events(status, next_attempt_at);

CREATE TABLE webhook_receipts (                    -- 9th table: consumer-side (mockmerchant) deduplication
  event_id      UUID PRIMARY KEY,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload_hash  CHAR(64) NOT NULL
);
-- mockmerchant, within a single transaction: INSERT webhook_receipts ON CONFLICT (event_id) DO NOTHING
--   affected_rows=1 → execute business logic; affected_rows=0 → duplicate delivery, return 200 immediately
-- Durable deduplication that survives restarts — the prerequisite for destructive experiment #5
-- to demonstrate the "exactly-once effect"
```

---

## 5. Ledger Model and System Accounts

**Posting convention (a simplified wallet convention; the README states it is not a full GAAP chart of accounts)**: `CREDIT` = funds flowing into the account, `DEBIT` = funds flowing out of the account; account balance ≡ Σ(CREDIT) − Σ(DEBIT). `accounts.balance` is a snapshot; the ledger is the source of truth and can be recomputed for reconciliation at any time.

**Two invariants (both asserted by tests)**:
- Per transaction: within each `ledger_transaction`, Σ(DEBIT amounts) = Σ(CREDIT amounts), with at least 2 entries (validated at the service layer; a violation throws and rolls back).
- Global: **grouped by currency**, Σ(CREDIT) − Σ(DEBIT) = 0 (never summed across currencies).

**Complete map of fund flows (V1 has exactly these 4, all through the single entry point `LedgerService.post()`)**:

| Operation | DEBIT (outflow) | CREDIT (inflow) |
|---|---|---|
| Simulated top-up | system:cash_in | user:{buyer}:wallet |
| Fund | user:{buyer}:wallet | system:escrow |
| Release | system:escrow | user:{seller}:wallet |
| Refund (reversal_of → original FUND txn) | system:escrow | user:{buyer}:wallet |

The README notes: *`system:cash_in` is a placeholder for external funding sources in this demo environment; it does not mean the platform creates money. Sandbox funds — no real monetary value.*

---

## 6. State Transition Matrix

| Current state ↓ / Action → | fund | release | refund |
|---|---|---|---|
| CREATED | → FUNDED | ✗ 409 | ✗ 409 |
| FUNDED | ✗ 409 | → RELEASED | → REFUNDED |
| RELEASED (terminal) | ✗ 409 | ✗ 409 | ✗ 409 |
| REFUNDED (terminal) | ✗ 409 | ✗ 409 | ✗ 409 |

Implementation: `UPDATE escrow_orders SET status=:new, updated_at=now() WHERE id=:id AND status=:expectedOld`, and **affected_rows must be == 1**; when == 0, throw `IllegalStateTransitionException` → 409 `ILLEGAL_STATE_TRANSITION`, and that branch has **zero side effects**. Authorization: fund/release only by the buyer; refund only by the seller or an admin; unauthorized attempts → 403 (authorization is checked before the CAS). Order expiry and cancellation of CREATED orders are deferred to Phase 2.

---

## 7. Lock Ordering and Transaction Boundaries

**Locking rules (hard rules; code comments reference this section)**:
1. Whenever multiple accounts must be locked, always execute `SELECT ... FOR UPDATE` in **ascending** `accounts.id` order (`AccountLockService.lockAll(ids)` sorts internally; business code is not allowed to acquire locks on its own) — this prevents deadlocks; system accounts participate in the same ordering.
2. Locks are taken only on `accounts` rows; `escrow_orders` takes no row lock — its concurrency is handled by the CAS (division of labor per responsibility table #2/#3).
3. `SET LOCAL lock_timeout = '5s'` (transaction-scoped; **never connection-scoped** — connection-pool reuse would leak the setting), with timeouts mapped to 503 `LOCK_TIMEOUT`.

**Complete transaction boundary using fund as the example (the other operations are structurally identical)**:

```
@Transactional
1. INSERT idempotency_record ... ON CONFLICT DO NOTHING (native SQL, see §8)
   affected_rows=0 → take the idempotency decision-table branch; produce no further side effects
2. Authorization check (current user == buyer)
3. lockAll([buyer_wallet_id, system_escrow_id]) -- FOR UPDATE in ascending id order
4. Balance check: buyer_wallet.balance >= amount, otherwise throw → 422 (rollback; the idempotency record rolls back with it)
5. CAS: CREATED → FUNDED, affected_rows == 1, otherwise throw → 409 (rollback)
6. LedgerService.post(ESCROW_FUND, entries[debit: buyer_wallet, credit: system_escrow])
   + update both accounts' balance snapshots
7. INSERT audit_event, INSERT outbox_event      -- same transaction as status and ledger
8. UPDATE idempotency_record: COMPLETED + response_status + response_body
COMMIT   -- all 8 steps take effect together or vanish together
```

Isolation level: READ COMMITTED (the default) is sufficient — correctness is guaranteed by row locks + CAS + unique constraints, not by a higher isolation level (interview point: being able to explain why SERIALIZABLE is not needed).

---

## 8. Idempotency Decision Table

We adopt **Option A: the idempotency record shares the business operation's transaction**. V1 does not implement lease/recovery; the `IN_PROGRESS/COMPLETED` field expresses the lifecycle, but we **do not claim that a concurrent request can observe IN_PROGRESS in real time** (under PostgreSQL, a second INSERT with the same key blocks until the first transaction settles).

**Write protocol (must be native SQL — JdbcTemplate or @Query(nativeQuery); `JpaRepository.save()` + catching the exception is forbidden)** — a unique-constraint violation inside a Hibernate transaction marks the transaction rollback-only, so even after catching it you cannot go on to query the cached response:

```sql
INSERT INTO idempotency_records (user_id, idempotency_key, request_hash, status)
VALUES (?, ?, ?, 'IN_PROGRESS')
ON CONFLICT (user_id, idempotency_key) DO NOTHING;
```

- `affected_rows = 1`: this request wins the right to execute; proceed with business logic.
- `affected_rows = 0`: read the existing record → compare `request_hash` → return the cached response or 409 per the decision table.
- While the first transaction is still executing, the second INSERT waits at the unique index for it to settle; if the first rolls back, the second insert succeeds and takes over.
- **Invariant corollary**: under Option A the record shares the business transaction and the COMPLETED update happens before commit, therefore **any committed record is necessarily COMPLETED** — reading IN_PROGRESS after affected_rows=0 means the invariant is broken; assert/alert immediately.

| Scenario | Behavior |
|---|---|
| Same key + same request_hash + already COMPLETED | Return the original response_status + response_body (passthrough; do not re-execute) |
| Same key + different request_hash | 409 `IDEMPOTENCY_KEY_REUSED` |
| Same key + first request still executing | INSERT blocks waiting for the first transaction; on `lock_timeout` → 409 `REQUEST_IN_PROGRESS` |
| First request's transaction rolled back | The idempotency record vanishes with it; the client may retry ("retrying the same request should yield the same result whenever possible") |
| Missing Idempotency-Key header (fund-moving write endpoint) | 400 `IDEMPOTENCY_KEY_REQUIRED` |

Scope: the five write endpoints `POST /wallets/top-ups`, `POST /escrows`, and `fund/release/refund` all mandate `Idempotency-Key: <UUID>`. `request_hash = SHA-256(method + path + canonical_json(body))`; canonical_json rules: UTF-8, fields sorted lexicographically, numbers in canonical form (no superfluous decimal places or scientific notation). Records are retained for 24h and then deleted by a cleanup job — **API contract (stated in the README): after 24h the same key is treated as a new request**; for top-up this means funds could be added again, so the client's retry window is bounded accordingly.

---

## 9. API Contract (12 endpoints)

Common error body: `{ "error_code": "...", "message": "...", "correlation_id": "..." }`. Authentication: everything except register/login/webhook requires `Authorization: Bearer <JWT>`.

| # | API | Auth/Role | Success | Key errors |
|---|---|---|---|---|
| 1 | POST /api/auth/register | Public | 201 (USD wallet created in the same transaction) | 409 EMAIL_EXISTS; 422 weak password |
| 2 | POST /api/auth/login | Public | 200 {token} | 401 BAD_CREDENTIALS |
| 3 | POST /api/wallets/top-ups ⚿ | USER | 201 | 400/409/422 (idempotency table) |
| 4 | GET /api/accounts/me | USER | 200 balance + accounts | — |
| 5 | GET /api/accounts/me/transactions | USER | 200 paginated ledger history | — |
| 6 | POST /api/escrows ⚿ | USER (=buyer) | 201 | 422 SELLER_IS_BUYER / SELLER_NOT_FOUND / INVALID_AMOUNT |
| 7 | POST /api/escrows/{id}/fund ⚿ | buyer | 200 | 403; 404; 409 ILLEGAL_STATE_TRANSITION; 422 INSUFFICIENT_FUNDS; 503 LOCK_TIMEOUT |
| 8 | POST /api/escrows/{id}/release ⚿ | buyer | 200 | 403; 404; 409 |
| 9 | POST /api/escrows/{id}/refund ⚿ | seller/ADMIN | 200 | 403; 404; 409 |
| 10 | GET /api/escrows/{id} | participants/ADMIN | 200 | 403; 404 |
| 11 | GET /api/admin/audit-events | ADMIN | 200 paginated | 403 |
| 12 | POST /api/webhooks/mock-merchant | HMAC signature | 200 | 401 INVALID_SIGNATURE |

⚿ = `Idempotency-Key` mandatory. Status-code semantics memo: 401 unauthenticated / 403 authenticated but not authorized / 409 resource-state or idempotency conflict / 422 well-formed request that the business cannot process.

---

## 10. Outbox Retry Strategy (Claim Protocol)

**Core principle: HTTP calls never happen inside a database transaction** — otherwise a single 30s network timeout pins a connection and row locks; that is a bug even on a single instance. Three-phase design:

```
State machine: PENDING → PROCESSING → DELIVERED
                                    ↘ PENDING (re-queued for retry, next_attempt_at pushed back per the backoff ladder)
                                    ↘ FAILED (7th failure, manual-intervention slot)

Short transaction ① claim: UPDATE outbox_events
             SET status='PROCESSING', claim_token=:newUuid,
                 claimed_at=now(), locked_until=now()+'60s'
             WHERE id IN (SELECT id FROM outbox_events
                          WHERE status='PENDING' AND next_attempt_at <= now()
                          ORDER BY next_attempt_at LIMIT 10
                          FOR UPDATE SKIP LOCKED)
             → COMMIT (locks released immediately; each claim generates a fresh claim_token)
Outside any transaction, send: HTTP POST to the merchant URL (timeout 10s)
Short transaction ② conditional write-back (fencing):
             UPDATE outbox_events
             SET status='DELIVERED', delivered_at=now()
             WHERE id=:id AND status='PROCESSING' AND claim_token=:myToken;
             -- affected_rows=0 → the lease has been taken over; this Worker must abandon the write and exit silently
             The failure branch carries the same claim_token condition:
               attempt_count+1; ≤6 → back to PENDING + backoff-ladder next_attempt_at
                                     (1m → 5m → 10m → 30m → 1h → 6h)
                                =7 → FAILED
```

- **Lease takeover (Reaper)**: an `@Scheduled` scan for `status='PROCESSING' AND locked_until < now()` (orphans that crashed or hung after claiming but before writing the result) → reset to PENDING and **clear claim_token / claimed_at / locked_until**. When the superseded slow Worker returns, its conditional write-back hits affected_rows=0 and it gives up automatically.
- **Semantic boundary (the precise statement worth spelling out — Standard explanation)**: the claim_token fences the **status write-back**, not the delivery itself — a superseded Worker's slow HTTP request may still reach the merchant after the new holder has already succeeded; delivery semantics remain at-least-once, and the "exactly-once effect" is carried by the consumer's durable `webhook_receipts` deduplication. Neither piece is sufficient alone.
- Signing: `X-PayNova-Signature: HMAC-SHA256(secret, payload)`; `X-PayNova-Event-Id: {id}`. The consumer verifies the signature and deduplicates durably via the `webhook_receipts` table → **at-least-once delivery + consumer-side idempotency = exactly-once effect** (Standard explanation).
- Semantics: if the process crashes "after commit, before send" → the event is still in the table and is delivered normally after restart (destructive experiment #5); if it crashes "after claim, before send" → the lease expires and the Reaper takes over.
- V1 still runs a single Worker instance; the claim lease + fencing token make **multi-instance claiming safe** (the same pattern as Kill Bill's DB queue, plus the conditional write-back).

---

## 11. Audit and Logging Red Lines

`audit_events` records: registration, login success/failure, top-ups, order creation, fund/release/refund success/failure, illegal transition attempts, unauthorized-access attempts, and webhook delivery terminal states. JSON log fields (Logback + logstash-encoder): `timestamp, event_type, correlation_id, actor_id, actor_role, escrow_id, ledger_transaction_id, source_ip, old_status, new_status, amount, currency, result`.

**Transaction boundaries for auditing (otherwise failure audits vanish with the business rollback)**:
- Success audits: commit together with the business transaction (§7 step 7).
- Failure/rejection audits (insufficient funds, illegal transition, unauthorized access): after the business transaction has rolled back, write them at the exception-handling boundary (`@ControllerAdvice` / service boundary) in an independent **`REQUIRES_NEW` transaction**.
- JSON security logs: emitted unconditionally, even if the database audit write fails.

**Red lines (code-review checklist items)**: never log JWTs, passwords (including the raw text of wrong passwords), the `Authorization` header, or sensitive fields inside idempotency response bodies. The `correlation_id` is generated by a Filter and flows through request → audit → logs → webhook payload. Positioning: **SIEM-ready, not SIEM-dependent** — the application does not depend on Splunk to run; the README provides the field specification plus 2 example Splunk queries (brute-force fund attempts, clusters of illegal state transitions), with an ingestion screenshot as an optional bonus.

---

## 12. Six-Phase Incremental Implementation Plan (Steps 0–5)

Development protocol (three constraints): (1) before each step begins, Claude presents 2 design options with trade-offs and the **decision is made by the project owner** — the code never makes decisions on a human's behalf; (2) after each step, the project owner performs the destructive experiments; (3) a commit is made only once the project owner can independently explain and modify the code. Commit messages honestly disclose AI assistance (`Co-authored-by`); history is never fabricated.

| Step | Content | Tests | Destructive experiment |
|---|---|---|---|
| 0 Skeleton | Project scaffolding / Flyway 9 tables / JWT / Docker Compose / CI (delivered directly by Claude, 2 commits) | Context startup + login smoke test | — |
| 1 State machine | escrow CRUD + pure CAS transition rules (**fund-moving endpoints not exposed** — fund/release are wired into the main branch only in Step 4, after the ledger and locks are in place) | Full transition-matrix coverage: legal transitions succeed; all 4 states × 3 actions illegal combinations return 409 | Remove the `AND status=` old-value condition → manufacture a double release |
| 2 Ledger | LedgerService.post + system accounts (allow_negative) + top-up + wallet creation at registration | Per-transaction balance assertion; global SUM=0 per currency; entries ≥ 2; uq_ledger_business rejects duplicate posting | Remove the balance validation → commit a one-sided entry, and watch the global reconciliation test catch it |
| 3 Idempotency | ON CONFLICT write protocol + all 5 decision-table scenarios | Same key × 10 requests moves funds exactly once; different hash → 409; retry possible after rollback | Drop the unique constraint → submit the same key concurrently |
| 4 Locks | lockAll ascending order + fund/release/refund endpoints go live | **$100 balance, 2 concurrent $80 requests, exactly 1 succeeds** (Testcontainers PG); call `lockAll()` from multiple threads with reversed input orders and verify internal ID sorting, no deadlock | Remove row locks → reproduce the double-spend; lock out of order → reproduce the deadlock (**manual experiment, kept out of CI** — deadlock tests are inherently flaky) |
| 5 Outbox | Claim-protocol Worker (claim_token conditional write-back) + Reaper + mockmerchant (webhook_receipts deduplication) | Webhook that fails first is still eventually delivered without double-processing; lease-expiry takeover; **the superseded slow Worker's conditional write-back returns affected_rows=0**; FAILED terminal state | kill -9 after commit, before send; restart and verify no event is lost. kill -9 after claim, before send; verify Reaper takeover |

Test layering: unit tests never touch the database; all integration/concurrency tests run on Testcontainers PostgreSQL (locking and isolation behavior must be verified against a real database — H2 is not used).

Timeline: first weekend = Steps 0–4 working end-to-end + destructive experiments; evening 2 = Step 5; evening 3 = audit/JWT refinement; evening 4 = README (responsibility table, both architecture diagrams, test-output screenshots) + CI badge + demo GIF; evening 5 = deployment (Render/AWS) + screen recording; **freeze on 8/8**, applications begin in August.

---

*This document is the formally frozen design after five rounds of design review. v1.1: 6 blocking fixes (webhook_receipts as the ninth table, native ON CONFLICT idempotency write protocol, Outbox claim-lease protocol, REQUIRES_NEW failure audits, allow_negative account constraint, Step 4 deadlock testing changed to direct lockAll tests). v1.2: Outbox claim_token fencing conditional write-back. This document is the sole implementation basis; any scope change must amend this document first.*
