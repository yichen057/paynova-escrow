# PayNova Escrow — AI Session Red Lines (required reading for every AI-assisted session)

**Sole implementation basis (Source of Truth): the PayNova Escrow detailed design document v1.2 (under `docs/`).**
The earlier "PayNova 1.0 open-source research and implementation plan" document (under `docs/`) is superseded; its sections 3–6
(merchant acquiring, payment_attempts, MERCHANT role, PaymentChannel, payment QR codes, risk control,
DECLINED/ERROR/UNKNOWN) **must not be reintroduced**.

## Scope Discipline (do not cross)

- Nine tables, twelve APIs, one PostgreSQL, one Spring Boot application
- Do not introduce: Kafka, Redis, Elasticsearch, microservice splits, real payment channels, a React frontend
- Phase 2 features (Stripe Test Mode, risk control, partial refunds, order expiry) are out of scope for this repository

## Technical Red Lines

- All amounts are `BIGINT` cents; never use floating point or DECIMAL
- `ledger_entries` is append-only: no UPDATE/DELETE code paths (a database trigger enforces this as a backstop)
- Within each ledger_transaction, Σ(DEBIT)=Σ(CREDIT); globally, SUM=0 per currency
- Idempotent writes must use native `INSERT ... ON CONFLICT DO NOTHING` (never JpaRepository.save + catching the unique-constraint exception)
- All multi-account locking goes through `AccountLockService.lockAll()` (which locks in ascending account id order); business code must not issue its own FOR UPDATE
- HTTP calls must never happen inside a database transaction (Outbox Worker three-phase pattern: claim in a short transaction → send outside the transaction → conditional write-back)
- Outbox write-backs must carry the `claim_token` fencing condition
- Failure/rejection audits use a separate REQUIRES_NEW bean (to avoid self-invocation); success audits share the business transaction
- Logs/audits must never record: JWTs, passwords, Authorization headers, card numbers/CVV (this project holds no card data at all)
- The schema is managed exclusively by Flyway (`ddl-auto: validate`)

## Development Protocol

Six-phase plan (design doc §12): before starting each step, present two design options with trade-offs; the project owner makes the final call.
Commits must honestly note AI assistance; do not fabricate history. CI runs deterministic tests only.

**Completion criteria for every Step (all required):**
Code complete → all tests green (local mvn verify) → destructive experiments done → the design can be explained independently
→ update interviewQ&A.md (new-term explanations / this step's design trade-offs / real problem–root cause–fix–verification / 3–5 follow-up questions)
→ commit.
interviewQ&A.md is an acceptance criterion for each step, not an afterthought written when the project ends;
strictly distinguish "implemented and verified" from "planned" — the same applies to the README: never claim capabilities that are not yet implemented.

## Test Layering

- Unit tests (`mvn test`): do not touch the database
- Integration/concurrency tests (`mvn verify`, *IT.java): Testcontainers PostgreSQL; H2 is forbidden
