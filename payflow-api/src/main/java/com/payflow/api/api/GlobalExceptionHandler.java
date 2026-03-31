package com.payflow.api.api;

import com.payflow.common.dto.ErrorResponse;
import com.payflow.common.exception.PaymentNotFoundException;
import com.payflow.common.exception.PaymentProcessingException;
import com.payflow.common.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request.getRequestURI());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Required header '" + ex.getHeaderName() + "' is missing", request.getRequestURI());
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(PaymentProcessingException.class)
    public ResponseEntity<ErrorResponse> handleProcessing(PaymentProcessingException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_PROCESSING_ERROR", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error,
                                                         String message, String path) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(error, message, Instant.now(), path));
    }
}
