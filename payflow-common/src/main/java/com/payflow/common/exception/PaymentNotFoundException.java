package com.payflow.common.exception;

/** Thrown when a payment cannot be found by the given ID. Maps to HTTP 404. */
public class PaymentNotFoundException extends PayflowException {

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
