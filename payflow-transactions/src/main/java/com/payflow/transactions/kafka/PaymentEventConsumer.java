package com.payflow.transactions.kafka;

import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.transactions.service.PaymentTransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentTransactionService paymentTransactionService;

    public PaymentEventConsumer(PaymentTransactionService paymentTransactionService) {
        this.paymentTransactionService = paymentTransactionService;
    }

    @KafkaListener(topics = "payment.initiated", groupId = "${spring.application.name}")
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        log.info("Received PaymentInitiatedEvent for paymentId={}", event.paymentId());
        paymentTransactionService.processPayment(event);
    }
}
