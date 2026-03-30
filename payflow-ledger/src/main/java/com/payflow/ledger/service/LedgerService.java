package com.payflow.ledger.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.ledger.domain.LedgerEntry;
import com.payflow.ledger.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Records a double-entry (DEBIT from sender, CREDIT to receiver) for a completed payment.
     * Idempotent: skips write if entries already exist for the transaction ID.
     */
    @Transactional
    public void recordDoubleEntry(PaymentCompletedEvent event) {
        if (ledgerEntryRepository.existsByTransactionId(event.paymentId())) {
            log.info("Ledger entries already exist for paymentId={}, skipping", event.paymentId());
            return;
        }

        Instant now = Instant.now();

        LedgerEntry debit = new LedgerEntry(
                UUID.randomUUID(), event.paymentId(), "DEBIT",
                event.senderId(), event.amount(), event.currency(), now);

        LedgerEntry credit = new LedgerEntry(
                UUID.randomUUID(), event.paymentId(), "CREDIT",
                event.receiverId(), event.amount(), event.currency(), now);

        ledgerEntryRepository.save(debit);
        ledgerEntryRepository.save(credit);

        log.info("Recorded DEBIT/CREDIT ledger entries for paymentId={}, amount={} {}",
                event.paymentId(), event.amount(), event.currency());
    }
}
