package com.flowops.orders.persistence;

import com.flowops.orders.application.OrderStore;
import com.flowops.orders.domain.Order;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JpaOrderStore implements OrderStore {
    private final OrderRepository repository;
    private final TransactionTemplate transaction;

    public JpaOrderStore(OrderRepository repository, PlatformTransactionManager manager) {
        this.repository = repository;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Creation create(String key, Order order) {
        try {
            return transaction.execute(tx -> repository.findByIdempotencyKey(key)
                    .map(existing -> replay(existing.domain(), order))
                    .orElseGet(() -> new Creation(repository.saveAndFlush(new OrderEntity(key, order)).domain(), false)));
        } catch (DataIntegrityViolationException ex) {
            // A competing insert can win the unique key. Read only after the losing transaction rolls back.
            return transaction.execute(tx -> repository.findByIdempotencyKey(key)
                    .map(existing -> replay(existing.domain(), order)).orElseThrow(() -> ex));
        }
    }

    private Creation replay(Order existing, Order requested) {
        if (!existing.customerId().equals(requested.customerId())
                || !existing.currency().equals(requested.currency()) || !existing.items().equals(requested.items())) {
            throw new IdempotencyConflict();
        }
        return new Creation(existing, true);
    }

    @Override
    public Optional<Order> find(UUID id) {
        return transaction.execute(tx -> repository.findById(id).map(OrderEntity::domain));
    }

    @Override
    public Page list(UUID customerId, Order.Status status, int page, int size) {
        return transaction.execute(tx -> {
            Specification<OrderEntity> filter = (root, query, cb) -> cb.conjunction();
            if (customerId != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
            if (status != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
            var result = repository.findAll(filter, PageRequest.of(page, size,
                    Sort.by(Sort.Direction.DESC, "createdAt", "id")));
            return new Page(result.getContent().stream().map(OrderEntity::domain).toList(),
                    page, size, result.getTotalElements(), result.getTotalPages());
        });
    }
}
