package com.payflow.ledger.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.ledger.domain.LedgerEntry;
import com.payflow.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerEntryRepository);
    }

    @Test
    void recordDoubleEntry_newTransaction_writesDebitAndCreditRows() {
        PaymentCompletedEvent event = completedEvent(new BigDecimal("250.00"));

        when(ledgerEntryRepository.existsByTransactionId(event.paymentId())).thenReturn(false);
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ledgerService.recordDoubleEntry(event);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());

        List<LedgerEntry> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);

        LedgerEntry debit = saved.stream()
                .filter(e -> "DEBIT".equals(e.getEntryType()))
                .findFirst()
                .orElseThrow();
        LedgerEntry credit = saved.stream()
                .filter(e -> "CREDIT".equals(e.getEntryType()))
                .findFirst()
                .orElseThrow();

        assertThat(debit.getAccountId()).isEqualTo(event.senderId());
        assertThat(debit.getAmount()).isEqualByComparingTo("250.00");
        assertThat(debit.getTransactionId()).isEqualTo(event.paymentId());

        assertThat(credit.getAccountId()).isEqualTo(event.receiverId());
        assertThat(credit.getAmount()).isEqualByComparingTo("250.00");
        assertThat(credit.getTransactionId()).isEqualTo(event.paymentId());
    }

    @Test
    void recordDoubleEntry_alreadyProcessed_skipsWriteIdempotently() {
        PaymentCompletedEvent event = completedEvent(new BigDecimal("100.00"));

        when(ledgerEntryRepository.existsByTransactionId(event.paymentId())).thenReturn(true);

        ledgerService.recordDoubleEntry(event);

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void recordDoubleEntry_entriesHaveMatchingCurrencyAndUniqueIds() {
        PaymentCompletedEvent event = completedEvent(new BigDecimal("75.50"));

        when(ledgerEntryRepository.existsByTransactionId(event.paymentId())).thenReturn(false);
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ledgerService.recordDoubleEntry(event);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());

        List<LedgerEntry> saved = captor.getAllValues();
        assertThat(saved.get(0).getCurrency()).isEqualTo("USD");
        assertThat(saved.get(1).getCurrency()).isEqualTo("USD");
        assertThat(saved.get(0).getId()).isNotEqualTo(saved.get(1).getId());
    }

    // --- helper ---

    private PaymentCompletedEvent completedEvent(BigDecimal amount) {
        return new PaymentCompletedEvent(
                UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                UUID.randomUUID(), "sender_1", "receiver_1", amount, "USD");
    }
}
