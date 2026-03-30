package com.payflow.common.exception;

/** Base unchecked exception for all PayFlow domain errors. */
public class PayflowException extends RuntimeException {

    public PayflowException(String message) {
        super(message);
    }

    public PayflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
