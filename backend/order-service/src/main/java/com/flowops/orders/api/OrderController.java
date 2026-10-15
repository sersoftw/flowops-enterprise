package com.flowops.orders.api;

import com.flowops.orders.application.OrderService;
import com.flowops.orders.application.OrderStore;
import com.flowops.orders.domain.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @PostMapping
    ResponseEntity<OrderResponse> create(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreateOrder request) {
        var result = service.create(key, request.customerId(), request.currency(),
                request.items().stream().map(i -> new Order.Item(i.productId(), i.quantity(), i.unitPrice())).toList());
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .location(URI.create("/api/orders/" + result.order().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(OrderResponse.from(result.order()));
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable UUID id) { return OrderResponse.from(service.get(id)); }

    @GetMapping
    PageResponse list(@RequestParam(required = false) UUID customerId,
                      @RequestParam(required = false) Order.Status status,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "20") int size) {
        var result = service.list(customerId, status, page, size);
        return new PageResponse(result.content().stream().map(OrderResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    public record CreateOrder(@NotNull UUID customerId,
                              @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
                              @NotEmpty @Size(max = 100) List<@NotNull @Valid ItemRequest> items) { }
    public record ItemRequest(@NotBlank @Size(max = 100)
                              @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters") String productId,
                              @NotNull @Min(1) @Max(10000) Integer quantity,
                              @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99")
                              @Digits(integer = 9, fraction = 2) BigDecimal unitPrice) { }
    public record OrderResponse(UUID id, UUID customerId, String currency, Order.Status status,
                                Instant createdAt, List<Order.Item> items, BigDecimal total) {
        static OrderResponse from(Order order) {
            return new OrderResponse(order.id(), order.customerId(), order.currency(), order.status(),
                    order.createdAt(), order.items(), order.total());
        }
    }
    public record PageResponse(List<OrderResponse> content, int page, int size, long totalElements, int totalPages) { }
}
