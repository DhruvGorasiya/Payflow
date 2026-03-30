package com.payflow.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "entry_type", nullable = false, length = 10)
    private String entryType;

    @Column(name = "account_id", nullable = false, length = 255)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {}

    public LedgerEntry(UUID id, UUID transactionId, String entryType, String accountId,
                       BigDecimal amount, String currency, Instant createdAt) {
        this.id = id;
        this.transactionId = transactionId;
        this.entryType = entryType;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public String getEntryType() { return entryType; }
    public String getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
