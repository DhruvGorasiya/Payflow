package com.payflow.transactions.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.transactions.domain.Payment;
import com.payflow.transactions.kafka.PaymentEventProducer;
import com.payflow.transactions.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentTransactionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransactionService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;

    public PaymentTransactionService(PaymentRepository paymentRepository,
                                     PaymentEventProducer paymentEventProducer) {
        this.paymentRepository = paymentRepository;
        this.paymentEventProducer = paymentEventProducer;
    }

    /**
     * Processes a payment through the state machine: INITIATED → PROCESSING → COMPLETED | FAILED.
     * For v1, all payments are completed unless the amount exceeds the ceiling.
     */
    @Transactional
    public void processPayment(PaymentInitiatedEvent event) {
        log.info("Processing payment paymentId={}", event.paymentId());

        Payment payment = paymentRepository.findById(event.paymentId())
                .orElseGet(() -> {
                    log.warn("Payment {} not found in DB for processing; may already be handled", event.paymentId());
                    return null;
                });

        if (payment == null) {
            return;
        }

        if (!"INITIATED".equals(payment.getStatus())) {
            log.info("Payment {} already in status {}, skipping re-processing", event.paymentId(), payment.getStatus());
            return;
        }

        payment.setStatus("PROCESSING");
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        try {
            simulateProcessing(event);

            payment.setStatus("COMPLETED");
            payment.setUpdatedAt(Instant.now());
            paymentRepository.save(payment);

            PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                    UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                    event.paymentId(), event.senderId(), event.receiverId(),
                    event.amount(), event.currency());
            paymentEventProducer.publishPaymentCompleted(completedEvent);

            log.info("Payment {} completed successfully", event.paymentId());

        } catch (Exception e) {
            log.error("Payment {} failed during processing: {}", event.paymentId(), e.getMessage());

            payment.setStatus("FAILED");
            payment.setUpdatedAt(Instant.now());
            paymentRepository.save(payment);

            PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                    UUID.randomUUID(), PaymentFailedEvent.EVENT_TYPE, Instant.now(),
                    event.paymentId(), e.getMessage());
            paymentEventProducer.publishPaymentFailed(failedEvent);
        }
    }

    /**
     * Simulates payment processing. In v1 this is a no-op; real integration with a
     * payment gateway would go here.
     */
    private void simulateProcessing(PaymentInitiatedEvent event) {
        // No external gateway in v1; payments always succeed unless amount is invalid
        if (event.amount() == null || event.amount().signum() <= 0) {
            throw new IllegalArgumentException("Invalid payment amount");
        }
    }
}
