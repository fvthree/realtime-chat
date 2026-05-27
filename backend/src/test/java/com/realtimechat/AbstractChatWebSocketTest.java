package com.realtimechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.URI;

/**
 * Shared Testcontainers + Spring wiring for WebSocket integration and resilience tests.
 * Concrete subclasses add @Testcontainers + @SpringBootTest (and @MockBean where needed).
 *
 * Auth in tests: the TestAuthWebFilter reads X-Auth-User from the WS upgrade request
 * and injects a mock principal. Use authHeaders("alice") to produce authenticated headers.
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
        // Prevent Spring Security from requiring a real GitHub OAuth app during tests.
        registry.add("spring.security.oauth2.client.registration.github.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.github.client-secret", () -> "test-client-secret");
        registry.add("app.oauth2-success-redirect", () -> "http://localhost:5173/");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    URI uri(String room) {
        return URI.create("ws://localhost:" + port + "/ws/chat/" + room);
    }

    /** Returns HTTP headers that authenticate the WS upgrade as the given user via TestAuthWebFilter. */
    HttpHeaders authHeaders(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(TestAuthWebFilter.HEADER, username);
        return headers;
    }
}
