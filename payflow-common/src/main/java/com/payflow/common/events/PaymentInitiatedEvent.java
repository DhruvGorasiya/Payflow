package com.payflow.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to Kafka topic {@code payment.initiated} when a new payment is accepted by the API.
 */
public record PaymentInitiatedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID paymentId,
        String idempotencyKey,
        String senderId,
        String receiverId,
        BigDecimal amount,
        String currency
) {
    public static final String EVENT_TYPE = "PAYMENT_INITIATED";
}
