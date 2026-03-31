package com.payflow.transactions.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.transactions.repository.PaymentRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "payment.initiated", "payment.completed", "payment.failed",
            "payment.initiated.dlt", "payment.completed.dlt", "payment.failed.dlt"
        }
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DirtiesContext
class PaymentTransactionServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private BlockingQueue<ConsumerRecord<String, Object>> completedEvents;
    private BlockingQueue<ConsumerRecord<String, Object>> failedEvents;
    private KafkaMessageListenerContainer<String, Object> completedContainer;
    private KafkaMessageListenerContainer<String, Object> failedContainer;

    @BeforeEach
    void setUpConsumers() {
        completedEvents = new LinkedBlockingQueue<>();
        failedEvents = new LinkedBlockingQueue<>();

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "test-spy-" + UUID.randomUUID(), "false", embeddedKafka);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.payflow.common.events");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);

        ConsumerFactory<String, Object> cf = new DefaultKafkaConsumerFactory<>(props);

        completedContainer = listenerContainer(cf, "payment.completed", completedEvents);
        completedContainer.start();

        failedContainer = listenerContainer(cf, "payment.failed", failedEvents);
        failedContainer.start();
    }

    @AfterEach
    void tearDownConsumers() {
        completedContainer.stop();
        failedContainer.stop();
    }

    @Test
    void consumePaymentInitiated_validPayment_completesAndPublishesCompletedEvent() throws Exception {
        UUID paymentId = UUID.randomUUID();
        seedPayment(paymentId, "INITIATED", new BigDecimal("100.00"));

        kafkaTemplate.send("payment.initiated", paymentId.toString(),
                initiatedEvent(paymentId, new BigDecimal("100.00"))).get(5, TimeUnit.SECONDS);

        ConsumerRecord<String, Object> record = completedEvents.poll(15, TimeUnit.SECONDS);
        assertThat(record).as("payment.completed event not received within timeout").isNotNull();

        PaymentCompletedEvent completed = (PaymentCompletedEvent) record.value();
        assertThat(completed.paymentId()).isEqualTo(paymentId);
        assertThat(completed.amount()).isEqualByComparingTo("100.00");

        assertThat(paymentRepository.findById(paymentId))
                .isPresent()
                .hasValueSatisfying(p -> assertThat(p.getStatus()).isEqualTo("COMPLETED"));
    }

    @Test
    void consumePaymentInitiated_alreadyCompleted_skipsIdempotently() throws Exception {
        UUID paymentId = UUID.randomUUID();
        seedPayment(paymentId, "COMPLETED", new BigDecimal("200.00"));

        kafkaTemplate.send("payment.initiated", paymentId.toString(),
                initiatedEvent(paymentId, new BigDecimal("200.00"))).get(5, TimeUnit.SECONDS);

        ConsumerRecord<String, Object> record = completedEvents.poll(3, TimeUnit.SECONDS);
        assertThat(record).as("No event should be published for already-completed payment").isNull();

        assertThat(paymentRepository.findById(paymentId).get().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void consumePaymentInitiated_nullAmount_publishesFailedEvent() throws Exception {
        UUID paymentId = UUID.randomUUID();
        seedPayment(paymentId, "INITIATED", new BigDecimal("50.00"));

        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                UUID.randomUUID(), PaymentInitiatedEvent.EVENT_TYPE, Instant.now(),
                paymentId, "key-fail", "sender_1", "receiver_1", null, "USD");

        kafkaTemplate.send("payment.initiated", paymentId.toString(), event).get(5, TimeUnit.SECONDS);

        ConsumerRecord<String, Object> record = failedEvents.poll(15, TimeUnit.SECONDS);
        assertThat(record).as("payment.failed event not received within timeout").isNotNull();

        PaymentFailedEvent failed = (PaymentFailedEvent) record.value();
        assertThat(failed.paymentId()).isEqualTo(paymentId);
        assertThat(failed.reason()).isNotBlank();

        assertThat(paymentRepository.findById(paymentId).get().getStatus()).isEqualTo("FAILED");
    }

    // --- helpers ---

    /** Inserts a payment row directly via JDBC (Payment entity has no public constructor in this service). */
    private void seedPayment(UUID id, String status, BigDecimal amount) {
        jdbcTemplate.update(
                "INSERT INTO payments (id, idempotency_key, sender_id, receiver_id, amount, currency, status, created_at, updated_at) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, now(), now())",
                id.toString(), "key-" + id, "sender_1", "receiver_1", amount, "USD", status);
    }

    private PaymentInitiatedEvent initiatedEvent(UUID paymentId, BigDecimal amount) {
        return new PaymentInitiatedEvent(
                UUID.randomUUID(), PaymentInitiatedEvent.EVENT_TYPE, Instant.now(),
                paymentId, "key-" + paymentId, "sender_1", "receiver_1", amount, "USD");
    }

    private KafkaMessageListenerContainer<String, Object> listenerContainer(
            ConsumerFactory<String, Object> cf, String topic,
            BlockingQueue<ConsumerRecord<String, Object>> queue) {
        KafkaMessageListenerContainer<String, Object> container =
                new KafkaMessageListenerContainer<>(cf, new ContainerProperties(topic));
        container.setupMessageListener((MessageListener<String, Object>) queue::add);
        return container;
    }
}
