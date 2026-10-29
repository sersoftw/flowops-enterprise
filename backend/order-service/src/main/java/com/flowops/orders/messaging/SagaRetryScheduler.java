package com.flowops.orders.messaging;
import com.flowops.orders.persistence.OrderMessaging;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="flowops.messaging.enabled",havingValue="true",matchIfMissing=true)
public class SagaRetryScheduler {
    private final OrderMessaging messaging;
    public SagaRetryScheduler(OrderMessaging messaging) { this.messaging=messaging; }
    @Scheduled(fixedDelayString="${flowops.saga.scan-delay-ms:1000}")
    public void scan() { messaging.retryCompensations(java.time.Instant.now()); }
}
