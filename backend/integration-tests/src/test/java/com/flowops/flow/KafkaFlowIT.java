package com.flowops.flow;

import com.flowops.contracts.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real packaged JVM processes: only Kafka connects the services, each using its restricted DB role. */
@EnabledIfSystemProperty(named="postgres.tests",matches="true")
@Testcontainers
class KafkaFlowIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("flowops").withInitScript("flow-databases.sql");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.2");
    static final ObjectMapper json = new ObjectMapper();
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static Process orders, inventory, payment;
    static int orderPort, inventoryPort, paymentPort;
    static final Path target = Path.of("target").toAbsolutePath();

    @BeforeAll static void start() throws Exception {
        Files.createDirectories(target);
        orderPort=freePort(); inventoryPort=freePort(); paymentPort=freePort();
        try {
            inventory=startService("inventory",inventoryPort);
            payment=startService("payment",paymentPort);
            orders=startService("order",orderPort);
        } catch (Exception | AssertionError failure) { stop(orders); stop(inventory); stop(payment); throw failure; }
    }
    @AfterAll static void finish() throws Exception { stop(orders); stop(inventory); stop(payment); }
    static int freePort() throws Exception { try (var socket=new java.net.ServerSocket(0)) { return socket.getLocalPort(); } }
    static Process startService(String service, int port) throws Exception {
        String schema=service.equals("order") ? "orders" : service.equals("payment") ? "payments" : "inventory";
        Path jar=Path.of("..",service+"-service","target",service+"-service-0.1.0-SNAPSHOT.jar").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jar),"Run root Maven verify so the service JARs are packaged first: "+jar);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-jar",jar.toString(),
                "--server.port="+port,"--DB_SCHEMA="+schema,
                "--spring.datasource.url="+postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?") ? "&" : "?")+"currentSchema="+schema,
                "--spring.datasource.username="+schema+"_app","--spring.datasource.password=test_"+schema,
                "--spring.kafka.bootstrap-servers="+kafka.getBootstrapServers(),
                "--flowops.messaging.enabled=true","--flowops.messaging.relay-delay-ms=100")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(target.resolve(service+".log").toFile())).start();
        try {
            await(60,()->{
                if (!process.isAlive()) throw new AssertionError(service+" exited; see target/"+service+".log");
                return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;
            });
            return process;
        } catch (Exception | AssertionError failure) { stop(process); throw failure; }
    }
    static void stop(Process process) throws Exception {
        if (process==null) return;
        process.destroy();
        if (!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5,TimeUnit.SECONDS)); }
    }
    static Connection connection(String schema) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(),schema+"_app","test_"+schema);
    }
    static int sql(String schema,String statement,Object... args) throws Exception {
        try (var connection=connection(schema);var command=connection.prepareStatement(statement)) {
            for(int i=0;i<args.length;i++)command.setObject(i+1,args[i]);
            return command.executeUpdate();
        }
    }
    static String value(String schema,String statement,Object... args) throws Exception {
        try (var connection=connection(schema);var command=connection.prepareStatement(statement)) {
            for(int i=0;i<args.length;i++)command.setObject(i+1,args[i]);
            try(var rows=command.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }
    static String product(int stock) throws Exception {
        String sku="flow-"+UUID.randomUUID();
        sql("inventory","INSERT INTO inventory.product(id,name) VALUES (?,?)",sku,"Flow test");
        sql("inventory","INSERT INTO inventory.stock(product_id,available,reserved) VALUES (?,?,0)",sku,stock);
        return sku;
    }
    static UUID create(String sku,int quantity) throws Exception {
        return create(sku,quantity,1);
    }
    static UUID create(String sku,int quantity,int price) throws Exception {
        String raw=json.writeValueAsString(Map.of("customerId",UUID.randomUUID(),"currency","EUR",
                "items",List.of(Map.of("productId",sku,"quantity",quantity,"unitPrice",price))));
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+orderPort+"/api/orders"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(raw)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201,response.statusCode(),response.body());
        UUID id=UUID.fromString(json.readTree(response.body()).get("id").asText());
        assertEquals(id.toString(),response.headers().firstValue("X-Correlation-Id").orElseThrow());
        return id;
    }
    static String status(UUID id) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+orderPort+"/api/orders/"+id))
                .timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body()).get("status").asText();
    }
    static String outbox(String schema,UUID id) throws Exception {
        return value(schema,"SELECT event_json FROM "+schema+".event_outbox WHERE aggregate_id=? AND logical_event_key=?",
                id,schema.equals("orders") ? "OrderCreated" : "InventoryDecision");
    }
    static void send(String topic,UUID order,String raw) throws Exception {
        var props=new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG,"all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        try(var producer=new KafkaProducer<String,String>(props)) {
            producer.send(new ProducerRecord<>(topic,order.toString(),raw)).get(15,TimeUnit.SECONDS);
        }
    }
    static ConsumerRecord<String,String> observe(String topic,UUID order) throws Exception {
        var props=new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,"observer-"+UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,"false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class.getName());
        try(var consumer=new KafkaConsumer<String,String>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(System.nanoTime()<deadline) for(var record:consumer.poll(Duration.ofMillis(500))) {
                if(order.toString().equals(record.key()))return record;
            }
        }
        throw new AssertionError("No event observed for "+order+" on "+topic);
    }

    @Test void orderEventReservesAndUpdatesOrderAndSurvivesConsumerRestart() throws Exception {
        String sku=product(10); UUID first=create(sku,3);
        await(30,()->"COMPLETED".equals(status(first)));
        var created=observe("flowops.orders.v1",first);
        var envelope=json.readTree(created.value());
        assertEquals("OrderCreated",envelope.get("eventType").asText());
        assertEquals(1,envelope.get("eventVersion").asInt());
        assertEquals(first.toString(),envelope.get("aggregateId").asText());
        assertEquals(first.toString(),envelope.get("correlationId").asText());
        assertNotNull(envelope.get("occurredAt")); assertEquals(3,envelope.at("/payload/items/0/quantity").asInt());
        var result=json.readTree(observe("flowops.inventory.v1",first).value());
        assertEquals("InventoryReserved",result.get("eventType").asText());
        assertEquals(envelope.get("eventId").asText(),result.at("/payload/causationId").asText());
        assertEquals(first.toString(),result.get("correlationId").asText());
        assertEquals("7",value("inventory","SELECT available FROM inventory.stock WHERE product_id=?",sku));

        stop(inventory);
        UUID queued=create(sku,2);
        send("flowops.orders.v1",first,created.value());
        // Replay the exact outbox event after the consumer's durable state has been committed.
        inventory=startService("inventory",inventoryPort);
        await(30,()->"COMPLETED".equals(status(queued)));
        var replay=(ObjectNode)json.readTree(created.value());
        UUID replayId=UUID.randomUUID(); replay.put("eventId",replayId.toString());
        send("flowops.orders.v1",first,json.writeValueAsString(replay));
        await(20,()->"1".equals(value("inventory","SELECT count(*) FROM inventory.processed_event WHERE event_id=?",replayId)));
        assertEquals("5",value("inventory","SELECT available FROM inventory.stock WHERE product_id=?",sku));
        assertEquals("5",value("inventory","SELECT reserved FROM inventory.stock WHERE product_id=?",sku));
        assertEquals("1",value("inventory","SELECT count(*) FROM inventory.event_outbox WHERE aggregate_id=?",first));
        assertEquals("1",value("inventory","SELECT count(*) FROM inventory.reservation WHERE order_id=?",first));
    }

    @Test void rejectionRemainsStableAndDelayedContradictoryResultsDoNotRegressState() throws Exception {
        String sku=product(1); UUID order=create(sku,3);
        await(30,()->"INVENTORY_REJECTED".equals(status(order)));
        String raw=outbox("orders",order);
        var decision=json.readTree(observe("flowops.inventory.v1",order).value());
        assertEquals("INSUFFICIENT_STOCK",decision.at("/payload/reason").asText());
        sql("inventory","UPDATE inventory.stock SET available=11 WHERE product_id=?",sku);
        var duplicate=(ObjectNode)json.readTree(raw); UUID duplicateId=UUID.randomUUID();
        duplicate.put("eventId",duplicateId.toString());
        send("flowops.orders.v1",order,json.writeValueAsString(duplicate));
        await(20,()->"1".equals(value("inventory","SELECT count(*) FROM inventory.processed_event WHERE event_id=?",duplicateId)));
        UUID contradictory=UUID.randomUUID();
        var result=new EventEnvelope<>(contradictory,"InventoryReserved",1,order,order,Instant.now(),
                new InventoryReserved(UUID.fromString(json.readTree(raw).get("eventId").asText())));
        send("flowops.inventory.v1",order,json.writeValueAsString(result));
        await(20,()->"1".equals(value("orders","SELECT count(*) FROM orders.processed_event WHERE event_id=?",contradictory)));
        assertEquals("INVENTORY_REJECTED",status(order));
        assertEquals("11",value("inventory","SELECT available FROM inventory.stock WHERE product_id=?",sku));
        assertEquals("0",value("inventory","SELECT count(*) FROM inventory.reservation WHERE order_id=?",order));
    }

    @Test void relayRepublishesStableEventAfterOrderProcessRestart() throws Exception {
        String sku=product(4); UUID order=create(sku,2);
        await(30,()->"COMPLETED".equals(status(order)));
        String before=outbox("orders",order);
        stop(orders);
        sql("orders","UPDATE orders.event_outbox SET published_at=NULL WHERE aggregate_id=? AND event_type='OrderCreated'",order);
        orders=startService("order",orderPort);
        await(20,()->value("orders","SELECT published_at FROM orders.event_outbox WHERE aggregate_id=? AND event_type='OrderCreated'",order)!=null);
        assertEquals(before,outbox("orders",order));
        assertEquals("COMPLETED",status(order));
        assertEquals("2",value("inventory","SELECT reserved FROM inventory.stock WHERE product_id=?",sku));
        assertEquals("1",value("inventory","SELECT count(*) FROM inventory.event_outbox WHERE aggregate_id=?",order));
    }

    @Test void multipleHistoricalEventsDoNotConfuseCausationOrDeduplication() throws Exception {
        String sku=product(6);
        stop(inventory);
        UUID order=create(sku,2);
        for (String schema : List.of("orders","inventory")) {
            for (int i=0;i<2;i++) {
                // Storage-only historical fixtures: do not introduce a new Kafka contract or consumer.
                sql(schema,"INSERT INTO "+schema+".event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json,published_at) "
                        +"VALUES (?,?,?,'ArchitectureProbe','{}',CURRENT_TIMESTAMP)",UUID.randomUUID(),order,order);
            }
        }
        inventory=startService("inventory",inventoryPort);
        await(30,()->"COMPLETED".equals(status(order)));
        String original=outbox("orders",order);
        send("flowops.orders.v1",order,original);
        var duplicate=(ObjectNode)json.readTree(original);
        UUID replay=UUID.randomUUID(); duplicate.put("eventId",replay.toString());
        send("flowops.orders.v1",order,json.writeValueAsString(duplicate));
        await(20,()->"1".equals(value("inventory","SELECT COUNT(*) FROM inventory.processed_event WHERE event_id=?",replay)));
        for (String schema : List.of("orders","inventory")) {
            assertEquals(schema.equals("orders") ? "4" : "3",value(schema,"SELECT COUNT(*) FROM "+schema+".event_outbox WHERE aggregate_id=?",order));
        }
        assertEquals("2",value("inventory","SELECT reserved FROM inventory.stock WHERE product_id=?",sku));
        assertEquals("COMPLETED",status(order));
    }


    @Test void paymentDeclineLeavesStockReservedAndOrderAwaitingCompensation() throws Exception {
        String sku=product(3); UUID order=create(sku,1,1001);
        await(30,()->"PAYMENT_FAILED_PENDING_COMPENSATION".equals(status(order)));
        assertEquals("FAILED",value("payments","SELECT status FROM payments.payment WHERE order_id=?",order));
        assertEquals("1",value("inventory","SELECT reserved FROM inventory.stock WHERE product_id=?",sku));
        assertEquals("RESERVED",value("inventory","SELECT status FROM inventory.reservation WHERE order_id=?",order));
        var request=json.readTree(observe("flowops.payment-requests.v1",order).value());
        var outcome=json.readTree(observe("flowops.payments.v1",order).value());
        assertEquals("PaymentFailed",outcome.get("eventType").asText());
        assertEquals(request.get("eventId").asText(),outcome.at("/payload/causationId").asText());
        assertEquals(order.toString(),outcome.get("correlationId").asText());
        assertEquals(json.readTree(outbox("inventory",order)).get("eventId").asText(),request.at("/payload/causationId").asText());
    }

    @Test void paymentConsumerRestartAndDuplicateRequestsDoNotCreateAnotherAttempt() throws Exception {
        String sku=product(4); UUID first=create(sku,1);
        await(30,()->"COMPLETED".equals(status(first)));
        String original=value("orders","SELECT event_json FROM orders.event_outbox WHERE aggregate_id=? AND event_type='PaymentRequested'",first);
        stop(payment);
        UUID queued=create(sku,1);
        await(20,()->"INVENTORY_RESERVED".equals(status(queued)));
        send("flowops.payment-requests.v1",first,original);
        var duplicate=(ObjectNode)json.readTree(original); UUID duplicateId=UUID.randomUUID();
        duplicate.put("eventId",duplicateId.toString());
        send("flowops.payment-requests.v1",first,json.writeValueAsString(duplicate));
        payment=startService("payment",paymentPort);
        await(30,()->"COMPLETED".equals(status(queued)));
        await(20,()->"1".equals(value("payments","SELECT count(*) FROM payments.processed_event WHERE event_id=?",duplicateId)));
        assertEquals("1",value("payments","SELECT count(*) FROM payments.payment WHERE order_id=?",first));
        assertEquals("1",value("payments","SELECT count(*) FROM payments.payment_attempt a JOIN payments.payment p ON p.id=a.payment_id WHERE p.order_id=?",first));
        assertEquals("1",value("payments","SELECT count(*) FROM payments.event_outbox WHERE aggregate_id=?",first));
        assertEquals("COMPLETED",status(first));
    }

    @Test void paymentRelayRetriesAfterRealKafkaSendAndDatabaseMarkFailure() throws Exception {
        String sku=product(2);
        sql("payments","ALTER TABLE payments.event_outbox ADD CONSTRAINT relay_failure_test CHECK (published_at IS NULL) NOT VALID");
        UUID order;
        try {
            order=create(sku,1);
            await(30,()->"COMPLETED".equals(status(order)));
            assertEquals("1",value("payments","SELECT count(*) FROM payments.event_outbox WHERE aggregate_id=? AND published_at IS NULL",order));
        } finally { sql("payments","ALTER TABLE payments.event_outbox DROP CONSTRAINT relay_failure_test"); }
        await(20,()->value("payments","SELECT published_at FROM payments.event_outbox WHERE aggregate_id=?",order)!=null);
        String original=value("payments","SELECT event_json FROM payments.event_outbox WHERE aggregate_id=?",order);
        send("flowops.payments.v1",order,original);
        assertEquals("1",value("payments","SELECT count(*) FROM payments.payment_attempt a JOIN payments.payment p ON p.id=a.payment_id WHERE p.order_id=?",order));
        assertEquals("COMPLETED",status(order));
    }

    @FunctionalInterface interface Condition { boolean evaluate() throws Exception; }
    static void await(int seconds,Condition condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos(); Exception last=null;
        while(System.nanoTime()<deadline) {
            try { if(condition.evaluate())return; } catch(Exception transientFailure) {last=transientFailure;}
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out; inspect target/order.log and target/inventory.log",last);
    }
}
