package com.payflow.api.kafka;

import com.payflow.common.events.PaymentInitiatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);
    private static final String TOPIC = "payment.initiated";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes a PaymentInitiatedEvent asynchronously. */
    public void publishPaymentInitiated(PaymentInitiatedEvent event) {
        kafkaTemplate.send(TOPIC, event.paymentId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentInitiatedEvent for paymentId={}", event.paymentId(), ex);
                    } else {
                        log.debug("Published PaymentInitiatedEvent for paymentId={}", event.paymentId());
                    }
                });
    }
}
