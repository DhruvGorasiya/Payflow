package com.payflow.notifications.service;

import com.payflow.common.events.PaymentCompletedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String NOTIFICATION_KEY_PREFIX = "notification:";
    private static final long NOTIFICATION_TTL_SECONDS = 86_400L;

    private final StringRedisTemplate stringRedisTemplate;

    public NotificationService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** Sends a completion notification and tracks delivery status in Redis. */
    public void notifyPaymentCompleted(PaymentCompletedEvent event) {
        log.info("NOTIFICATION [COMPLETED] paymentId={} senderId={} receiverId={} amount={} {}",
                event.paymentId(), event.senderId(), event.receiverId(),
                event.amount(), event.currency());
        trackDelivery(event.paymentId().toString(), "COMPLETED");
    }

    /** Sends a failure notification and tracks delivery status in Redis. */
    public void notifyPaymentFailed(PaymentFailedEvent event) {
        log.info("NOTIFICATION [FAILED] paymentId={} reason={}",
                event.paymentId(), event.reason());
        trackDelivery(event.paymentId().toString(), "FAILED");
    }

    private void trackDelivery(String paymentId, String status) {
        try {
            stringRedisTemplate.opsForValue().set(
                    NOTIFICATION_KEY_PREFIX + paymentId, status,
                    Duration.ofSeconds(NOTIFICATION_TTL_SECONDS));
        } catch (Exception e) {
            log.error("Failed to track notification delivery status for paymentId={}", paymentId, e);
        }
    }
}
