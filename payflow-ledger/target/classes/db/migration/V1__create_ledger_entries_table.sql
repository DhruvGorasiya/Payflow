CREATE TABLE IF NOT EXISTS ledger_entries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    entry_type     VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    account_id     VARCHAR(255) NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ledger_txn_type ON ledger_entries(transaction_id, entry_type);
CREATE INDEX IF NOT EXISTS idx_ledger_transaction_id ON ledger_entries(transaction_id);
