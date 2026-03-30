package com.payflow.ledger.kafka;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.ledger.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final LedgerService ledgerService;

    public PaymentEventConsumer(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @KafkaListener(topics = "payment.completed", groupId = "${spring.application.name}")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent for paymentId={}", event.paymentId());
        ledgerService.recordDoubleEntry(event);
    }
}
