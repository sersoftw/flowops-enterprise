package com.flowops.payment;
import com.flowops.payment.domain.*;
import com.flowops.payment.persistence.PaymentMessaging;
import com.flowops.payment.messaging.OutboxRelay;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PaymentDomainTest {
    @Test void simulatorBoundaryIsDeterministicAndValidatesMoney() {
        var order=UUID.randomUUID(); var now=Instant.now();
        for(int i=0;i<10;i++) {
            assertEquals(Payment.Status.SUCCEEDED,PaymentSimulator.simulate(order,new BigDecimal("1000"),"EUR",now).status());
            assertEquals(Payment.Status.FAILED,PaymentSimulator.simulate(order,new BigDecimal("1000.01"),"EUR",now).status());
        }
        for(String amount:java.util.List.of("0","-1","1.001","100000000000000000.00"))
            assertThrows(IllegalArgumentException.class,()->PaymentSimulator.simulate(order,new BigDecimal(amount),"EUR",now));
        assertThrows(IllegalArgumentException.class,()->PaymentSimulator.simulate(order,BigDecimal.ONE,"bad",now));
        assertThrows(IllegalArgumentException.class,()->new PaymentAttempt(UUID.randomUUID(),order,Payment.Status.SUCCEEDED,2,now));
    }
    @Test void schemaIdentifiersAreValidatedBeforeSqlConstruction() {
        for(String schema:java.util.List.of("payments;DROP SCHEMA orders","payments.alt","bad\"","x".repeat(64))) {
            assertThrows(IllegalArgumentException.class,()->new PaymentMessaging(null,null,null,schema));
            assertThrows(IllegalArgumentException.class,()->new OutboxRelay(null,null,null,schema));
        }
    }
}
