package com.flowops.orders;

import com.flowops.orders.application.OrderService;
import com.flowops.orders.application.OrderStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OrderConfiguration {
    @Bean OrderService orderService(OrderStore store) { return new OrderService(store, Clock.systemUTC()); }
}
