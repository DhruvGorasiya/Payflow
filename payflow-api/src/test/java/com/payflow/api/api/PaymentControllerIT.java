package com.payflow.api.api;

import com.payflow.common.dto.PaymentRequest;
import com.payflow.common.dto.PaymentResponse;
import com.payflow.common.dto.PaymentStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"payment.initiated"})
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@DirtiesContext
class PaymentControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void postPayment_validRequest_returns202WithPaymentId() {
        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("100.00"), "USD");
        HttpHeaders headers = headersWithIdempotencyKey(UUID.randomUUID().toString());

        ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                "/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(request, headers), PaymentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().paymentId()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("INITIATED");
    }

    @Test
    void postPayment_duplicateIdempotencyKey_returns200FromCache() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("50.00"), "USD");
        HttpHeaders headers = headersWithIdempotencyKey(idempotencyKey);

        ResponseEntity<PaymentResponse> first = restTemplate.exchange(
                "/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(request, headers), PaymentResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<PaymentResponse> second = restTemplate.exchange(
                "/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(request, headers), PaymentResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().paymentId()).isEqualTo(first.getBody().paymentId());
    }

    @Test
    void getPayment_afterCreation_returnsPaymentStatus() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = new PaymentRequest("user_A", "user_B", new BigDecimal("200.00"), "EUR");
        HttpHeaders headers = headersWithIdempotencyKey(idempotencyKey);

        ResponseEntity<PaymentResponse> created = restTemplate.exchange(
                "/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(request, headers), PaymentResponse.class);
        UUID paymentId = created.getBody().paymentId();

        ResponseEntity<PaymentStatusResponse> status = restTemplate.getForEntity(
                "/api/v1/payments/" + paymentId, PaymentStatusResponse.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).isNotNull();
        assertThat(status.getBody().paymentId()).isEqualTo(paymentId);
        assertThat(status.getBody().senderId()).isEqualTo("user_A");
        assertThat(status.getBody().amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void getPayment_unknownId_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/payments/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void postPayment_missingIdempotencyKeyHeader_returns400() {
        PaymentRequest request = new PaymentRequest("user_1", "user_2", new BigDecimal("100.00"), "USD");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void postPayment_invalidAmount_returns400() {
        PaymentRequest request = new PaymentRequest("user_1", "user_2", BigDecimal.ZERO, "USD");
        HttpHeaders headers = headersWithIdempotencyKey(UUID.randomUUID().toString());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void health_returnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    private HttpHeaders headersWithIdempotencyKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }
}
