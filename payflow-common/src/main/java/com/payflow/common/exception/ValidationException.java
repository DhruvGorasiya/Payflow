package com.payflow.common.exception;

/** Thrown when incoming request data fails validation. Maps to HTTP 400. */
public class ValidationException extends PayflowException {

    public ValidationException(String message) {
        super(message);
    }
}
