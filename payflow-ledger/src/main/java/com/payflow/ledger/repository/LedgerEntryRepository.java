package com.payflow.ledger.repository;

import com.payflow.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    boolean existsByTransactionId(UUID transactionId);
}
