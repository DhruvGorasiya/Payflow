-- Test-only migration: recreates the payments table that payflow-api owns in production.
-- Used by PaymentTransactionServiceIT to give Hibernate a schema to validate against.
CREATE TABLE IF NOT EXISTS payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    sender_id       VARCHAR(255) NOT NULL,
    receiver_id     VARCHAR(255) NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payments_idempotency_key ON payments(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payments_sender_id ON payments(sender_id);
