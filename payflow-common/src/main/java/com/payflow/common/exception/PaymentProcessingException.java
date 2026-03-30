package com.payflow.common.exception;

/** Thrown when a payment cannot be processed due to a business rule violation. Maps to HTTP 422. */
public class PaymentProcessingException extends PayflowException {

    public PaymentProcessingException(String message) {
        super(message);
    }
}
