package com.payflow.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.api.domain.Payment;
import com.payflow.api.kafka.PaymentEventProducer;
import com.payflow.api.repository.PaymentRepository;
import com.payflow.common.dto.PaymentRequest;
import com.payflow.common.dto.PaymentResponse;
import com.payflow.common.dto.PaymentStatusResponse;
import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.common.exception.PaymentNotFoundException;
import com.payflow.common.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    private static final long IDEMPOTENCY_TTL_SECONDS = 86_400L;
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "EUR", "GBP", "SGD", "INR");

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentEventProducer paymentEventProducer,
                          StringRedisTemplate stringRedisTemplate,
                          ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventProducer = paymentEventProducer;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Initiates a new payment, enforcing idempotency via Redis and DB.
     *
     * @return result containing the response and whether it was newly created
     */
    @Transactional
    public PaymentInitiationResult initiatePayment(String idempotencyKey, PaymentRequest request) {
        validateRequest(request);

        Optional<PaymentResponse> cached = getCachedResponse(idempotencyKey);
        if (cached.isPresent()) {
            return new PaymentInitiationResult(cached.get(), false);
        }

        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PaymentResponse response = toPaymentResponse(existing.get());
            cacheResponse(idempotencyKey, response);
            return new PaymentInitiationResult(response, false);
        }

        Instant now = Instant.now();
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment(paymentId, idempotencyKey, request.senderId(),
                request.receiverId(), request.amount(), request.currency(),
                "INITIATED", now, now);
        paymentRepository.save(payment);

        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                UUID.randomUUID(), PaymentInitiatedEvent.EVENT_TYPE, now,
                paymentId, idempotencyKey, request.senderId(), request.receiverId(),
                request.amount(), request.currency());
        paymentEventProducer.publishPaymentInitiated(event);

        PaymentResponse response = new PaymentResponse(paymentId, "INITIATED", now);
        cacheResponse(idempotencyKey, response);

        return new PaymentInitiationResult(response, true);
    }

    /** Fetches payment status by ID. */
    public PaymentStatusResponse getPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
        return new PaymentStatusResponse(
                payment.getId(), payment.getStatus(), payment.getSenderId(),
                payment.getReceiverId(), payment.getAmount(), payment.getCurrency(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }

    private void validateRequest(PaymentRequest request) {
        if (request.senderId() == null || request.senderId().isBlank()) {
            throw new ValidationException("senderId must not be blank");
        }
        if (request.receiverId() == null || request.receiverId().isBlank()) {
            throw new ValidationException("receiverId must not be blank");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new ValidationException("amount must be greater than 0");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            throw new ValidationException("currency must not be blank");
        }
        if (!SUPPORTED_CURRENCIES.contains(request.currency().toUpperCase())) {
            throw new ValidationException("unsupported currency: " + request.currency());
        }
    }

    private Optional<PaymentResponse> getCachedResponse(String idempotencyKey) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(IDEMPOTENCY_KEY_PREFIX + idempotencyKey);
            if (cached != null) {
                return Optional.of(objectMapper.readValue(cached, PaymentResponse.class));
            }
        } catch (Exception e) {
            log.error("Redis read failed for idempotency key {}, proceeding without cache", idempotencyKey, e);
        }
        return Optional.empty();
    }

    private void cacheResponse(String idempotencyKey, PaymentResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(
                    IDEMPOTENCY_KEY_PREFIX + idempotencyKey, json,
                    Duration.ofSeconds(IDEMPOTENCY_TTL_SECONDS));
        } catch (Exception e) {
            log.error("Redis write failed for idempotency key {}", idempotencyKey, e);
        }
    }

    private PaymentResponse toPaymentResponse(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getStatus(), payment.getCreatedAt());
    }

    /** Wrapper returned by initiatePayment to carry both response and creation flag. */
    public record PaymentInitiationResult(PaymentResponse response, boolean created) {}
}
