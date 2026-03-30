package com.payflow.transactions.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.transactions.domain.Payment;
import com.payflow.transactions.kafka.PaymentEventProducer;
import com.payflow.transactions.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentTransactionServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventProducer paymentEventProducer;

    private PaymentTransactionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentTransactionService(paymentRepository, paymentEventProducer);
    }

    @Test
    void processPayment_validInitiatedPayment_completesAndPublishesCompletedEvent() {
        PaymentInitiatedEvent event = initiatedEvent(new BigDecimal("150.00"));
        Payment payment = mockPayment(event.paymentId(), "INITIATED");

        when(paymentRepository.findById(event.paymentId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processPayment(event);

        ArgumentCaptor<PaymentCompletedEvent> captor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(paymentEventProducer).publishPaymentCompleted(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(event.paymentId());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("150.00");
        verifyNoMoreInteractions(paymentEventProducer);
    }

    @Test
    void processPayment_paymentAlreadyCompleted_skipsProcessingIdempotently() {
        PaymentInitiatedEvent event = initiatedEvent(new BigDecimal("100.00"));
        Payment payment = mockPayment(event.paymentId(), "COMPLETED");

        when(paymentRepository.findById(event.paymentId())).thenReturn(Optional.of(payment));

        service.processPayment(event);

        verifyNoInteractions(paymentEventProducer);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void processPayment_paymentNotFoundInDb_skipsGracefully() {
        PaymentInitiatedEvent event = initiatedEvent(new BigDecimal("100.00"));

        when(paymentRepository.findById(event.paymentId())).thenReturn(Optional.empty());

        service.processPayment(event);

        verifyNoInteractions(paymentEventProducer);
    }

    @Test
    void processPayment_nullAmount_publishesFailedEvent() {
        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                UUID.randomUUID(), PaymentInitiatedEvent.EVENT_TYPE, Instant.now(),
                UUID.randomUUID(), "key", "user_1", "user_2", null, "USD");
        Payment payment = mockPayment(event.paymentId(), "INITIATED");

        when(paymentRepository.findById(event.paymentId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processPayment(event);

        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(paymentEventProducer).publishPaymentFailed(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(event.paymentId());
        assertThat(captor.getValue().reason()).isNotBlank();
        verifyNoMoreInteractions(paymentEventProducer);
    }

    @Test
    void processPayment_savesProcessingStatusBeforeCompletion() {
        PaymentInitiatedEvent event = initiatedEvent(new BigDecimal("200.00"));
        Payment payment = mockPayment(event.paymentId(), "INITIATED");

        when(paymentRepository.findById(event.paymentId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processPayment(event);

        // save must be called at least twice: once for PROCESSING, once for COMPLETED
        verify(paymentRepository, atLeast(2)).save(any());
    }

    // --- helpers ---

    private PaymentInitiatedEvent initiatedEvent(BigDecimal amount) {
        return new PaymentInitiatedEvent(
                UUID.randomUUID(), PaymentInitiatedEvent.EVENT_TYPE, Instant.now(),
                UUID.randomUUID(), "idempotency-key", "user_1", "user_2", amount, "USD");
    }

    private Payment mockPayment(UUID id, String status) {
        Payment payment = mock(Payment.class);
        when(payment.getId()).thenReturn(id);
        when(payment.getStatus()).thenReturn(status);
        when(payment.getSenderId()).thenReturn("user_1");
        when(payment.getReceiverId()).thenReturn("user_2");
        when(payment.getAmount()).thenReturn(new BigDecimal("150.00"));
        when(payment.getCurrency()).thenReturn("USD");
        return payment;
    }
}
