package com.payflow.notifications.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "payment.completed", "payment.failed",
            "payment.completed.dlt", "payment.failed.dlt"
        }
)
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@DirtiesContext
class NotificationServiceIT {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void onPaymentCompleted_writesCompletedKeyToRedis() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(), PaymentCompletedEvent.EVENT_TYPE, Instant.now(),
                paymentId, "sender_1", "receiver_1", new BigDecimal("100.00"), "USD");

        kafkaTemplate.send("payment.completed", paymentId.toString(), event).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            String value = stringRedisTemplate.opsForValue().get("notification:" + paymentId);
            assertThat(value).isEqualTo("COMPLETED");
        });
    }

    @Test
    void onPaymentFailed_writesFailedKeyToRedis() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID(), PaymentFailedEvent.EVENT_TYPE, Instant.now(),
                paymentId, "Insufficient funds");

        kafkaTemplate.send("payment.failed", paymentId.toString(), event).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            String value = stringRedisTemplate.opsForValue().get("notification:" + paymentId);
            assertThat(value).isEqualTo("FAILED");
        });
    }
}
