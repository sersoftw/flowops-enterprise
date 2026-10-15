package com.flowops.orders;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

abstract class OrderApiContract {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    String payload(UUID customer) {
        return """
                {"customerId":"%s","currency":"EUR","items":[{"productId":"sku-1","quantity":2,"unitPrice":12.50}]}
                """.formatted(customer);
    }

    HttpResponse<String> post(String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    @Test void createsReadsFiltersAndPaginates() throws Exception {
        var customer = UUID.randomUUID();
        var created = post(UUID.randomUUID().toString(), payload(customer));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(OpenApiSchemas.validate("Order", created.body()).isEmpty(), created.body());
        assertEquals("PENDING", body(created).get("status").asText());
        assertEquals(25.0, body(created).get("total").asDouble());
        assertEquals("false", created.headers().firstValue("Idempotency-Replayed").orElseThrow());
        var location = created.headers().firstValue("Location").orElseThrow();
        var fetched = get(location);
        assertEquals(200, fetched.statusCode());
        assertEquals(body(created).get("id"), body(fetched).get("id"));
        post(UUID.randomUUID().toString(), payload(customer));
        var page = get("/api/orders?customerId=" + customer + "&status=PENDING&size=1&page=0");
        assertEquals(200, page.statusCode(), page.body());
        assertEquals(2, body(page).get("totalElements").asInt());
        assertEquals(2, body(page).get("totalPages").asInt());
        var next = get("/api/orders?customerId=" + customer + "&size=1&page=1");
        assertNotEquals(body(page).get("content").get(0).get("id"), body(next).get("content").get(0).get("id"));
        assertEquals(0, body(get("/api/orders?customerId=" + UUID.randomUUID())).get("totalElements").asInt());
    }

    @Test void replaysNormalizedRequestAndRejectsDifferentContent() throws Exception {
        var key = UUID.randomUUID().toString();
        var payload = payload(UUID.randomUUID());
        var first = post(key, payload);
        var replay = post(key, payload.replace("12.50", "12.5"));
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(body(first).get("id"), body(replay).get("id"));
        assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertProblem(post(key, payload.replace("12.50", "13.00")), 409);
        assertEquals(1, jdbc.queryForObject("select count(*) from orders.purchase_order where idempotency_key = ?", Integer.class, key));
    }

    @Test void concurrentIdenticalRequestsCreateExactlyOneOrder() throws Exception {
        var key = UUID.randomUUID().toString();
        var payload = payload(UUID.randomUUID());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new java.util.ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> { start.await(); return post(key, payload); }));
            start.countDown();
            int created = 0;
            var ids = new java.util.HashSet<String>();
            for (var future : futures) {
                var result = future.get(30, TimeUnit.SECONDS);
                assertTrue(result.statusCode() == 201 || result.statusCode() == 200, result.body());
                if (result.statusCode() == 201) created++;
                ids.add(body(result).get("id").asText());
            }
            assertEquals(1, created);
            assertEquals(1, ids.size());
            assertEquals(1, jdbc.queryForObject("select count(*) from orders.purchase_order where idempotency_key = ?", Integer.class, key));
        }
    }

    @Test void concurrentDifferentRequestsConflict() throws Exception {
        var key = UUID.randomUUID().toString();
        var payload = payload(UUID.randomUUID());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return post(key, payload); });
            var b = executor.submit(() -> { start.await(); return post(key, payload.replace("12.50", "13.00")); });
            start.countDown();
            var statuses = List.of(a.get(30, TimeUnit.SECONDS).statusCode(), b.get(30, TimeUnit.SECONDS).statusCode());
            assertTrue(statuses.contains(201) && statuses.contains(409), statuses.toString());
        }
    }

    @Test void rejectsInvalidBodiesAndKeysWithoutPersisting() throws Exception {
        var customer = UUID.randomUUID();
        var valid = payload(customer);
        for (var invalid : List.of("{}", "{", valid.replace("\"quantity\":2", "\"quantity\":0"),
                valid.replace("12.50", "-1"), valid.replace("12.50", "0.001"), valid.replace("12.50", "1000000000"),
                valid.replace("\"quantity\":2", "\"quantity\":1.5"), valid.replace("\"currency\"", "\"total\":1,\"currency\""),
                valid.replace("sku-1", " "), valid.replace("EUR", "ZZZ"),
                valid.replace("[{\"productId\":\"sku-1\",\"quantity\":2,\"unitPrice\":12.50}]", "[]"),
                valid.replace("[{\"productId\":\"sku-1\",\"quantity\":2,\"unitPrice\":12.50}]", "[null]"))) {
            assertProblem(post(UUID.randomUUID().toString(), invalid), 400);
        }
        assertProblem(post(null, valid), 400);
        assertProblem(post("bad key", valid), 400);
        assertProblem(post("x".repeat(129), valid), 400);
        assertEquals(0, jdbc.queryForObject("select count(*) from orders.purchase_order where customer_id = ?", Integer.class, customer));
    }

    @Test void returnsConsistentErrorsForInvalidQueriesAndMissingOrders() throws Exception {
        for (var path : List.of("/api/orders/not-a-uuid", "/api/orders?page=-1", "/api/orders?size=101",
                "/api/orders?size=0", "/api/orders?status=UNKNOWN", "/api/orders?customerId=invalid", "/api/orders?page=abc")) {
            assertProblem(get(path), 400);
        }
        assertProblem(get("/api/orders/" + UUID.randomUUID()), 404);
    }

    @Test void unknownPropertiesMatchPublishedContractAndDoNotReserveKey() throws Exception {
        var customer = UUID.randomUUID();
        var valid = payload(customer);
        for (String invalid : List.of(valid.replace("\"currency\"", "\"total\":1,\"currency\""),
                valid.replace("\"quantity\"", "\"discount\":1,\"quantity\""))) {
            var key = UUID.randomUUID().toString();
            assertFalse(OpenApiSchemas.validate("CreateOrder", invalid).isEmpty());
            assertProblem(post(key, invalid), 400);
            assertEquals(0, jdbc.queryForObject("select count(*) from orders.purchase_order where idempotency_key = ?", Integer.class, key));
            assertEquals(201, post(key, valid).statusCode());
        }
    }

    @Test void nulProductIdsReturnValidationErrorsWithoutPersistenceAndAllowCorrectedRetry() throws Exception {
        for (String product : List.of("\\u0000sku", "sku\\u0000one", "sku\\u0000")) {
            var customer = UUID.randomUUID();
            var key = UUID.randomUUID().toString();
            var valid = payload(customer);
            var response = post(key, valid.replace("sku-1", product));
            assertProblem(response, 400);
            assertEquals("items[0].productId", body(response).get("errors").get(0).get("field").asText());
            assertEquals(0, jdbc.queryForObject("select count(*) from orders.purchase_order where customer_id = ?", Integer.class, customer));
            var corrected = post(key, valid);
            assertEquals(201, corrected.statusCode(), corrected.body());
            assertEquals(200, post(key, valid).statusCode());
        }
    }

    @Test void databaseRejectsInvalidItemAndRollsBack() throws Exception {
        var created = post(UUID.randomUUID().toString(), payload(UUID.randomUUID()));
        var id = UUID.fromString(body(created).get("id").asText());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "update orders.order_item set quantity = 0 where order_id = ?", id));
        assertEquals(2, jdbc.queryForObject("select quantity from orders.order_item where order_id = ?", Integer.class, id));
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(status, body(response).get("status").asInt());
        assertEquals("urn:flowops:problem:http-" + status, body(response).get("type").asText());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"));
    }
}
