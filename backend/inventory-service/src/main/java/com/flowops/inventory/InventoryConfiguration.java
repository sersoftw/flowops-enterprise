package com.flowops.inventory;

import com.flowops.inventory.application.InventoryService;
import com.flowops.inventory.application.InventoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class InventoryConfiguration {
    @Bean InventoryService inventoryService(InventoryStore store) { return new InventoryService(store); }
}
