package com.payflow.common.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response body for GET /api/v1/payments/{paymentId}. */
public record PaymentStatusResponse(
        UUID paymentId,
        String status,
        String senderId,
        String receiverId,
        BigDecimal amount,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {}
