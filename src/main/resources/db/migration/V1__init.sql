-- PayNova Escrow 1.0 — schema baseline
-- Source of Truth: the PayNova Escrow detailed design document v1.2 (under docs/), §4 (9 tables)
-- Hard rules: all amounts are BIGINT cents; ledger_entries is append-only; no real card numbers/CVV stored

CREATE TABLE users (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email         VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,            -- BCrypt
  role          VARCHAR(10)  NOT NULL DEFAULT 'USER'
                CHECK (role IN ('USER','ADMIN')),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- BIGINT: basis for lock ordering (§7)
  owner_user_id  BIGINT REFERENCES users(id),      -- NULL for system accounts
  type           VARCHAR(10) NOT NULL CHECK (type IN ('USER','SYSTEM')),
  name           VARCHAR(64) NOT NULL UNIQUE,      -- user:{id}:wallet / system:cash_in ...
  currency       VARCHAR(3)     NOT NULL DEFAULT 'USD',
  balance        BIGINT      NOT NULL DEFAULT 0,   -- cents; balance snapshot — the ledger is the source of truth
  allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0)
);

CREATE TABLE escrow_orders (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  buyer_id    BIGINT NOT NULL REFERENCES users(id),
  seller_id   BIGINT NOT NULL REFERENCES users(id),
  amount      BIGINT NOT NULL CHECK (amount > 0),
  currency    VARCHAR(3) NOT NULL DEFAULT 'USD',
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
  reference_type VARCHAR(20) NOT NULL,
  reference_id   VARCHAR(40) NOT NULL,
  reversal_of    UUID REFERENCES ledger_transactions(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Last line of defense against duplicate postings at the ledger level (§4)
  CONSTRAINT uq_ledger_business UNIQUE (type, reference_type, reference_id)
);

CREATE TABLE ledger_entries (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id UUID   NOT NULL REFERENCES ledger_transactions(id),
  account_id     BIGINT NOT NULL REFERENCES accounts(id),
  direction      VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount         BIGINT NOT NULL CHECK (amount > 0),  -- always positive; sign is expressed by direction
  currency       VARCHAR(3) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id, id);
CREATE INDEX idx_entries_txn     ON ledger_entries(transaction_id);

-- Immutability hardening: the ledger is append-only; UPDATE/DELETE are rejected at the database level (§4 optional hardening, enabled by default)
CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ledger_entries_immutable
  BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TABLE idempotency_records (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT NOT NULL REFERENCES users(id),
  idempotency_key UUID   NOT NULL,
  request_hash    CHAR(64) NOT NULL,
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
  currency              VARCHAR(3),
  result                VARCHAR(10) NOT NULL,
  details               JSONB
);
CREATE INDEX idx_audit_time ON audit_events(occurred_at);

CREATE TABLE outbox_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- event_id; consumer-side deduplication key
  aggregate_type  VARCHAR(20) NOT NULL,
  aggregate_id    VARCHAR(40) NOT NULL,
  event_type      VARCHAR(30) NOT NULL,
  payload         JSONB NOT NULL,
  status          VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','PROCESSING','DELIVERED','FAILED')),
  attempt_count   INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_at      TIMESTAMPTZ,
  locked_until    TIMESTAMPTZ,
  claim_token     UUID,                            -- fencing token: only the current lease holder may write back
  delivered_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_poll ON outbox_events(status, next_attempt_at);

CREATE TABLE webhook_receipts (
  event_id      UUID PRIMARY KEY,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload_hash  CHAR(64) NOT NULL
);

-- System account seed (§4/§5): only system:cash_in may go negative
INSERT INTO accounts (owner_user_id, type, name, currency, balance, allow_negative) VALUES
  (NULL, 'SYSTEM', 'system:cash_in',  'USD', 0, TRUE),
  (NULL, 'SYSTEM', 'system:escrow',   'USD', 0, FALSE),
  (NULL, 'SYSTEM', 'system:cash_out', 'USD', 0, FALSE);
