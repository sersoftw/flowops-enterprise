package com.flowops.orders;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:order-messages;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password="})
class OrderMessagingTest extends OrderMessagingContract {}