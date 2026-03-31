package com.payflow.common.dto;

import com.payflow.common.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Inbound request body for POST /api/v1/payments. */
public record PaymentRequest(
        @NotBlank @Size(max = 64) String senderId,
        @NotBlank @Size(max = 64) String receiverId,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") BigDecimal amount,
        @NotBlank @ValidCurrency String currency
) {}
