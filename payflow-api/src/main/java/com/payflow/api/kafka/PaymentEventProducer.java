package com.payflow.api.kafka;

import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.common.util.Headers;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PaymentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);
    private static final String TOPIC = "payment.initiated";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes a PaymentInitiatedEvent asynchronously, propagating the correlation ID as a Kafka header. */
    public void publishPaymentInitiated(PaymentInitiatedEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(TOPIC, event.paymentId().toString(), event);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            record.headers().add(new RecordHeader(Headers.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8)));
        }
        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PaymentInitiatedEvent for paymentId={}", event.paymentId(), ex);
                    } else {
                        log.debug("Published PaymentInitiatedEvent for paymentId={}", event.paymentId());
                    }
                });
    }
}
