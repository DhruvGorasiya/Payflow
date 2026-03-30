package com.payflow.common.dto;

import java.math.BigDecimal;

/** Inbound request body for POST /api/v1/payments. */
public record PaymentRequest(
        String senderId,
        String receiverId,
        BigDecimal amount,
        String currency
) {}
