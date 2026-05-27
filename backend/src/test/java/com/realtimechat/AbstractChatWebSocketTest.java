package com.realtimechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;

/**
 * Shared Testcontainers + Spring wiring for WebSocket integration and resilience tests.
 * Concrete subclasses add @Testcontainers + @SpringBootTest (and @MockBean where needed).
 */
abstract class AbstractChatWebSocketTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("realtimechat")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    URI uri(String room) {
        return URI.create("ws://localhost:" + port + "/ws/chat/" + room);
    }
}
