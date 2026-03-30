package com.payflow.common.dto;

import java.time.Instant;
import java.util.UUID;

/** Response body returned on successful payment initiation (HTTP 202 or 200 on cache hit). */
public record PaymentResponse(
        UUID paymentId,
        String status,
        Instant createdAt
) {}
