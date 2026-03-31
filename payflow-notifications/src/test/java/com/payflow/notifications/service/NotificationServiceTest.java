package com.payflow.notifications.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        notificationService = new NotificationService(stringRedisTemplate);
    }

    @Test
    void notifyPaymentCompleted_tracksCompletedStatusInRedis() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                UUID.randomUUID(), "sender_1", "receiver_1", new BigDecimal("100.00"), "USD");

        notificationService.notifyPaymentCompleted(event);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), any());

        assertThat(keyCaptor.getValue()).isEqualTo("notification:" + event.paymentId());
        assertThat(valueCaptor.getValue()).isEqualTo("COMPLETED");
    }

    @Test
    void notifyPaymentFailed_tracksFailedStatusInRedis() {
        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID(), PaymentFailedEvent.EVENT_TYPE, Instant.now(),
                UUID.randomUUID(), "Insufficient funds");

        notificationService.notifyPaymentFailed(event);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), any());

        assertThat(keyCaptor.getValue()).isEqualTo("notification:" + event.paymentId());
        assertThat(valueCaptor.getValue()).isEqualTo("FAILED");
    }

    @Test
    void notifyPaymentCompleted_redisFailure_doesNotPropagateException() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                UUID.randomUUID(), "sender_1", "receiver_1", new BigDecimal("50.00"), "USD");

        doThrow(new RuntimeException("Redis down")).when(valueOperations)
                .set(anyString(), anyString(), any());

        // Should not throw
        notificationService.notifyPaymentCompleted(event);
    }

    @Test
    void notifyPaymentFailed_redisFailure_doesNotPropagateException() {
        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID(), PaymentFailedEvent.EVENT_TYPE, Instant.now(),
                UUID.randomUUID(), "Timeout");

        doThrow(new RuntimeException("Redis down")).when(valueOperations)
                .set(anyString(), anyString(), any());

        // Should not throw
        notificationService.notifyPaymentFailed(event);
    }
}
