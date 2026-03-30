package com.payflow.common.dto;

import java.time.Instant;

/** Standard error envelope returned by GlobalExceptionHandler for all error responses. */
public record ErrorResponse(
        String error,
        String message,
        Instant timestamp,
        String path
) {}
