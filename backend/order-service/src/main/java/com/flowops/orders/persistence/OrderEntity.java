package com.flowops.orders.persistence;

import com.flowops.orders.domain.Order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "purchase_order")
class OrderEntity {
    @Id UUID id;
    @Column(nullable = false) UUID customerId;
    @Column(nullable = false, length = 3) String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24) Order.Status status;
    @Column(nullable = false) Instant createdAt;
    @Column(nullable = false, precision = 19, scale = 2) BigDecimal total;
    @Column(nullable = false, unique = true, length = 128) String idempotencyKey;
    @ElementCollection
    @CollectionTable(name = "order_item", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "item_index")
    List<Item> items = new ArrayList<>();

    protected OrderEntity() { }

    OrderEntity(String key, Order order) {
        id = order.id(); customerId = order.customerId(); currency = order.currency();
        status = order.status(); createdAt = order.createdAt(); total = order.total(); idempotencyKey = key;
        items = order.items().stream().map(Item::new).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    Order domain() {
        return new Order(id, customerId, currency, status, createdAt,
                items.stream().map(i -> new Order.Item(i.productId, i.quantity, i.unitPrice)).toList());
    }

    @Embeddable
    static class Item {
        @Column(nullable = false, length = 100) String productId;
        @Column(nullable = false) int quantity;
        @Column(nullable = false, precision = 11, scale = 2) BigDecimal unitPrice;
        protected Item() { }
        Item(Order.Item item) {
            productId = item.productId(); quantity = item.quantity(); unitPrice = item.unitPrice();
        }
    }
}
