package com.payflow.notifications.kafka;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.notifications.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final NotificationService notificationService;

    public PaymentEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "payment.completed", groupId = "${spring.application.name}")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent for paymentId={}", event.paymentId());
        notificationService.notifyPaymentCompleted(event);
    }

    @KafkaListener(topics = "payment.failed", groupId = "${spring.application.name}")
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent for paymentId={}", event.paymentId());
        notificationService.notifyPaymentFailed(event);
    }
}
