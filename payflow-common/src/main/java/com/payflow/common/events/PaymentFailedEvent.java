package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published to Kafka topic {@code payment.failed} when the transaction service
 * cannot complete a payment.
 */
public record PaymentFailedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID paymentId,
        String reason
) {
    public static final String EVENT_TYPE = "PAYMENT_FAILED";
}
