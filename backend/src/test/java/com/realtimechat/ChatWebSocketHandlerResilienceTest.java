package com.realtimechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Resilience tests that mock the MessageRepository to verify the handler
 * degrades gracefully when the DB is unavailable. Kept in a separate class
 * so the @MockBean doesn't affect the Testcontainers-backed integration tests.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketHandlerResilienceTest {

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

    // MockBean replaces the real repo — onErrorResume in the handler must catch the error
    // and allow the client to join with empty history instead of crashing the session.
    @MockBean
    MessageRepository messageRepository;

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    private URI uri(String room) {
        return URI.create("ws://localhost:" + port + "/ws/chat/" + room);
    }

    @Test
    void historyFetchFailureStillJoinsRoom() {
        when(messageRepository.findLast50ByRoomId(anyString()))
                .thenReturn(Flux.error(new RuntimeException("simulated DB failure")));

        WebSocketClient client = new ReactorNettyWebSocketClient();
        List<String> received = new CopyOnWriteArrayList<>();

        client.execute(uri("resilience-test"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .take(Duration.ofMillis(800))
                        .then()
        ).block(Duration.ofSeconds(5));

        // Client must receive a presence event — the session opened successfully.
        // It will NOT receive any history messages (DB error returns empty).
        assertThat(received)
                .anyMatch(s -> s.contains("\"type\":\"presence\""));
        assertThat(received)
                .noneMatch(s -> s.contains("\"type\":\"msg\""));
    }

    @Test
    void savePersistFailureDoesNotKillBroadcast() throws Exception {
        when(messageRepository.findLast50ByRoomId(anyString()))
                .thenReturn(Flux.empty());
        when(messageRepository.save(any()))
                .thenReturn(Mono.error(new RuntimeException("simulated DB save failure")));

        WebSocketClient sender = new ReactorNettyWebSocketClient();
        WebSocketClient observer = new ReactorNettyWebSocketClient();
        URI uri = uri("save-failure-test");

        List<String> observed = new CopyOnWriteArrayList<>();

        Disposable observerSession = observer.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(observed::add)
                        .then()
        ).subscribe();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        Message m = new Message(null, "tmp-save-fail", "save-failure-test", "userA", "fire-and-forget", 1000L, 0L);
        sender.execute(uri, session ->
                Mono.fromCallable(() -> json.writeValueAsString(m))
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then()
        ).block(Duration.ofSeconds(5));

        // Message must be broadcast even though DB save errored (fire-and-forget contract).
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("fire-and-forget") && s.contains("\"type\":\"msg\"")));

        observerSession.dispose();
    }
}
