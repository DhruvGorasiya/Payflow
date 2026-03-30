package com.payflow.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to Kafka topic {@code payment.completed} when the transaction service
 * successfully processes a payment.
 */
public record PaymentCompletedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID paymentId,
        String senderId,
        String receiverId,
        BigDecimal amount,
        String currency
) {
    public static final String EVENT_TYPE = "PAYMENT_COMPLETED";
}
