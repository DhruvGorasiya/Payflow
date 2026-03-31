package com.payflow.api.api;

import com.payflow.api.service.PaymentService;
import com.payflow.api.service.PaymentService.PaymentInitiationResult;
import com.payflow.common.dto.PaymentRequest;
import com.payflow.common.dto.PaymentResponse;
import com.payflow.common.dto.PaymentStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        PaymentInitiationResult result = paymentService.initiatePayment(idempotencyKey, request);
        HttpStatus status = result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> getPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
