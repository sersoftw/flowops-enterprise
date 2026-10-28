package com.flowops.payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="postgres.tests",matches="true")
@Testcontainers
@SpringBootTest(properties={"DB_SCHEMA=payments_alt"})
class PostgresPaymentMessagingTest extends PaymentMessagingContract {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17.6-alpine").withDatabaseName("flowops").withInitScript("payment-databases.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",()->"payments_app");
        registry.add("spring.datasource.password",()->"test_payments");
    }
    @Test void restrictedOwnerCannotAccessOtherSchemas() throws Exception {
        try(var c=jdbc.getDataSource().getConnection();var s=c.createStatement()) {
            for(String foreign:java.util.List.of("orders","inventory","payments","public")) {
                var error=assertThrows(java.sql.SQLException.class,()->s.executeQuery("SELECT * FROM "+foreign+".private_data"));
                assertEquals("42501",error.getSQLState());
            }
        }
        assertEquals("payments_app",jdbc.queryForObject("SELECT tableowner FROM pg_tables WHERE schemaname='payments_alt' AND tablename='payment'",String.class));
    }
}
