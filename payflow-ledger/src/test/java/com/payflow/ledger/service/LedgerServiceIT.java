package com.payflow.ledger.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"payment.completed"})
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@DirtiesContext
class LedgerServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void recordDoubleEntry_writesExactlyTwoRowsForNewPayment() {
        UUID paymentId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                paymentId, "sender_1", "receiver_1", new BigDecimal("300.00"), "USD");

        ledgerService.recordDoubleEntry(event);

        long count = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getTransactionId().equals(paymentId))
                .count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void recordDoubleEntry_idempotent_doesNotDuplicateOnReplay() {
        UUID paymentId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                paymentId, "sender_2", "receiver_2", new BigDecimal("150.00"), "EUR");

        ledgerService.recordDoubleEntry(event);
        ledgerService.recordDoubleEntry(event);

        long count = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getTransactionId().equals(paymentId))
                .count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void recordDoubleEntry_debitFromSender_creditToReceiver() {
        UUID paymentId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                paymentId, "alice", "bob", new BigDecimal("500.00"), "GBP");

        ledgerService.recordDoubleEntry(event);

        var entries = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getTransactionId().equals(paymentId))
                .toList();

        assertThat(entries).hasSize(2);
        assertThat(entries).anyMatch(e -> "DEBIT".equals(e.getEntryType()) && "alice".equals(e.getAccountId()));
        assertThat(entries).anyMatch(e -> "CREDIT".equals(e.getEntryType()) && "bob".equals(e.getAccountId()));
        entries.forEach(e -> assertThat(e.getAmount()).isEqualByComparingTo("500.00"));
    }
}
