package com.flowops.inventory.messaging;

import com.flowops.inventory.persistence.InventoryMessaging;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="flowops.messaging.enabled", havingValue="true", matchIfMissing=true)
public class KafkaTransport {
    private final InventoryMessaging handler;
    public KafkaTransport(InventoryMessaging handler) { this.handler = handler; }

    @Bean NewTopic ordersTopic() { return TopicBuilder.name("flowops.orders.v1").partitions(3).replicas(1).build(); }
    @Bean NewTopic inventoryTopic() { return TopicBuilder.name("flowops.inventory.v1").partitions(3).replicas(1).build(); }

    @Bean DefaultErrorHandler kafkaErrorHandler() {
        // Never silently discard a failed database operation or unknown version. DLT policy is deferred.
        var errors = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setClassifications(java.util.Map.of(Exception.class, true), true);
        errors.setCommitRecovered(false);
        return errors;
    }

    @KafkaListener(id="flowops-inventory-v1", topics="flowops.orders.v1", groupId="flowops-inventory-v1")
    public void consume(ConsumerRecord<String,String> record) {
        handler.accept(record.key(), record.value());
    }
}
