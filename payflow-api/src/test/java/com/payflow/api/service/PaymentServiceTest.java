package com.payflow.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.api.domain.Payment;
import com.payflow.api.kafka.PaymentEventProducer;
import com.payflow.api.repository.PaymentRepository;
import com.payflow.api.service.PaymentService.PaymentInitiationResult;
import com.payflow.common.dto.PaymentRequest;
import com.payflow.common.dto.PaymentStatusResponse;
import com.payflow.common.exception.PaymentNotFoundException;
import com.payflow.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventProducer paymentEventProducer;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        paymentService = new PaymentService(paymentRepository, paymentEventProducer,
                stringRedisTemplate, objectMapper);
    }

    // --- initiatePayment happy path ---

    @Test
    void initiatePayment_newPayment_returnsCreatedResultAndPublishesEvent() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("100.00"), "USD");

        when(valueOperations.get(anyString())).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentInitiationResult result = paymentService.initiatePayment(idempotencyKey, request);

        assertThat(result.created()).isTrue();
        assertThat(result.response().status()).isEqualTo("INITIATED");
        assertThat(result.response().paymentId()).isNotNull();
        verify(paymentEventProducer).publishPaymentInitiated(any());
        verify(paymentRepository).save(any());
    }

    @Test
    void initiatePayment_redisCacheHit_returnsCachedResponseWithoutCreating() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        UUID existingId = UUID.randomUUID();
        String cachedJson = "{\"paymentId\":\"" + existingId + "\",\"status\":\"INITIATED\","
                + "\"createdAt\":\"2024-01-01T12:00:00Z\"}";

        when(valueOperations.get("idempotency:" + idempotencyKey)).thenReturn(cachedJson);

        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("50.00"), "USD");
        PaymentInitiationResult result = paymentService.initiatePayment(idempotencyKey, request);

        assertThat(result.created()).isFalse();
        assertThat(result.response().paymentId()).isEqualTo(existingId);
        verifyNoInteractions(paymentRepository, paymentEventProducer);
    }

    @Test
    void initiatePayment_dbDuplicateButRedisExpired_returnsCachedResultWithoutNewPayment() {
        String idempotencyKey = UUID.randomUUID().toString();
        UUID existingId = UUID.randomUUID();
        Payment existing = existingPayment(existingId, idempotencyKey, "INITIATED");

        when(valueOperations.get(anyString())).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("50.00"), "USD");
        PaymentInitiationResult result = paymentService.initiatePayment(idempotencyKey, request);

        assertThat(result.created()).isFalse();
        assertThat(result.response().paymentId()).isEqualTo(existingId);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentEventProducer);
    }

    @Test
    void initiatePayment_redisFailsOnRead_proceedsWithPaymentCreation() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("75.00"), "USD");

        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentInitiationResult result = paymentService.initiatePayment(idempotencyKey, request);

        assertThat(result.created()).isTrue();
        verify(paymentEventProducer).publishPaymentInitiated(any());
    }

    // --- initiatePayment validation ---

    @Test
    void initiatePayment_nullSenderId_throwsValidationException() {
        PaymentRequest request = new PaymentRequest(null, "user_2", new BigDecimal("100.00"), "USD");
        assertThatThrownBy(() -> paymentService.initiatePayment("key", request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("senderId");
    }

    @Test
    void initiatePayment_blankReceiverId_throwsValidationException() {
        PaymentRequest request = new PaymentRequest("user_1", "  ", new BigDecimal("100.00"), "USD");
        assertThatThrownBy(() -> paymentService.initiatePayment("key", request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("receiverId");
    }

    @Test
    void initiatePayment_zeroAmount_throwsValidationException() {
        PaymentRequest request = new PaymentRequest("user_1", "user_2", BigDecimal.ZERO, "USD");
        assertThatThrownBy(() -> paymentService.initiatePayment("key", request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void initiatePayment_negativeAmount_throwsValidationException() {
        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("-1.00"), "USD");
        assertThatThrownBy(() -> paymentService.initiatePayment("key", request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void initiatePayment_unsupportedCurrency_throwsValidationException() {
        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("100.00"), "XYZ");
        assertThatThrownBy(() -> paymentService.initiatePayment("key", request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("currency");
    }

    // --- getPayment ---

    @Test
    void getPayment_existingId_returnsStatusResponse() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = existingPayment(paymentId, "key", "COMPLETED");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        PaymentStatusResponse response = paymentService.getPayment(paymentId);

        assertThat(response.paymentId()).isEqualTo(paymentId);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.senderId()).isEqualTo("user_1");
    }

    @Test
    void getPayment_unknownId_throwsPaymentNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(paymentRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(unknownId))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    // --- helpers ---

    private Payment existingPayment(UUID id, String idempotencyKey, String status) {
        return new Payment(id, idempotencyKey, "user_1", "user_2",
                new BigDecimal("100.00"), "USD", status, Instant.now(), Instant.now());
    }
}
