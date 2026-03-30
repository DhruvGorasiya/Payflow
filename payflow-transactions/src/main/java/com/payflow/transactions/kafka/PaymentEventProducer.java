package com.payflow.transactions.kafka;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);
    private static final String COMPLETED_TOPIC = "payment.completed";
    private static final String FAILED_TOPIC = "payment.failed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes a PaymentCompletedEvent asynchronously. */
    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        kafkaTemplate.send(COMPLETED_TOPIC, event.paymentId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentCompletedEvent for paymentId={}", event.paymentId(), ex);
                    } else {
                        log.debug("Published PaymentCompletedEvent for paymentId={}", event.paymentId());
                    }
                });
    }

    /** Publishes a PaymentFailedEvent asynchronously. */
    public void publishPaymentFailed(PaymentFailedEvent event) {
        kafkaTemplate.send(FAILED_TOPIC, event.paymentId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentFailedEvent for paymentId={}", event.paymentId(), ex);
                    } else {
                        log.debug("Published PaymentFailedEvent for paymentId={}", event.paymentId());
                    }
                });
    }
}
