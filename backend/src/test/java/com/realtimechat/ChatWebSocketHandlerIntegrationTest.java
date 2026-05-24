package com.realtimechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketHandlerIntegrationTest {

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private URI uri(String room) {
        return URI.create("ws://localhost:" + port + "/ws/chat/" + room);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void twoClientsExchangeMessage() throws Exception {
        WebSocketClient clientA = new ReactorNettyWebSocketClient();
        WebSocketClient clientB = new ReactorNettyWebSocketClient();
        URI uri = uri("exchange-test");

        List<String> receivedByB = new CopyOnWriteArrayList<>();

        Disposable bSession = clientB.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(receivedByB::add)
                        .then()
        ).subscribe();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByB)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        clientA.execute(uri, session ->
                Mono.fromCallable(() -> {
                            Message m = new Message(null, "tmp-1", "exchange-test", "userA", "hello B", 1000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then()
        ).block(Duration.ofSeconds(5));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByB)
                        .anyMatch(s -> s.contains("hello B") && s.contains("\"type\":\"msg\"")));

        bSession.dispose();
    }

    @Test
    void pingReceivesPongWithThreeTimestamps() throws Exception {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = uri("ping-test");

        List<String> received = new CopyOnWriteArrayList<>();

        client.execute(uri, session ->
                Mono.fromCallable(() -> {
                            Ping ping = new Ping("ping-test", "userA", 9999L);
                            return json.writeValueAsString(ping);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .thenMany(session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(received::add)
                                .take(Duration.ofMillis(800)))
                        .then()
        ).block(Duration.ofSeconds(5));

        assertThat(received)
                .anyMatch(s -> s.contains("\"type\":\"pong\"")
                        && s.contains("\"clientPingTs\":9999")
                        && s.contains("serverRecvTs")
                        && s.contains("serverSendTs"));
    }

    @Test
    void presenceCountIncrementsOnJoinAndDecrementsOnLeave() {
        WebSocketClient clientA = new ReactorNettyWebSocketClient();
        WebSocketClient clientB = new ReactorNettyWebSocketClient();
        URI uri = uri("presence-test");

        List<String> receivedByA = new CopyOnWriteArrayList<>();

        Disposable aSession = clientA.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(receivedByA::add)
                        .then()
        ).subscribe();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByA)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"") && s.contains("\"connected\":1")));

        Disposable bSession = clientB.execute(uri, session ->
                session.receive().map(WebSocketMessage::getPayloadAsText).then()
        ).subscribe();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByA)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"") && s.contains("\"connected\":2")));

        bSession.dispose();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(
                        receivedByA.stream()
                                .filter(s -> s.contains("\"type\":\"presence\"") && s.contains("\"connected\":1"))
                                .count()
                ).isGreaterThanOrEqualTo(2));

        aSession.dispose();
    }

    @Test
    void helloTriggersPresenceBroadcast() {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = uri("hello-test");

        List<String> received = new CopyOnWriteArrayList<>();

        client.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .take(Duration.ofMillis(600))
                        .then()
                        .doFirst(() ->
                                Mono.delay(Duration.ofMillis(100))
                                        .then(Mono.fromCallable(() ->
                                                json.writeValueAsString(new Hello("hello-test", "userA"))))
                                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                                        .subscribe()
                        )
        ).block(Duration.ofSeconds(5));

        assertThat(
                received.stream().filter(s -> s.contains("\"type\":\"presence\"")).count()
        ).isGreaterThanOrEqualTo(2);
    }

    @Test
    void messagesAreReplayedOnJoin() throws Exception {
        WebSocketClient sender = new ReactorNettyWebSocketClient();
        WebSocketClient replayer = new ReactorNettyWebSocketClient();
        URI uri = uri("replay-test");

        // Send a message and wait for it to be broadcast (confirms DB write is in-flight).
        List<String> senderReceived = new CopyOnWriteArrayList<>();
        Disposable senderSession = sender.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(senderReceived::add)
                        .then()
        ).subscribe();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(senderReceived)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        sender.execute(uri, session ->
                Mono.fromCallable(() -> {
                            Message m = new Message(null, "tmp-replay", "replay-test",
                                    "userA", "replay-me", 5000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then()
        ).block(Duration.ofSeconds(5));

        // Wait for broadcast to confirm the message was processed (and DB write is in-flight).
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(senderReceived)
                        .anyMatch(s -> s.contains("replay-me")));

        senderSession.dispose();

        // Wait for the async DB write to complete before replaying history.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollDelay(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(
                        messageRepository.findLast50ByRoomId("replay-test").collectList().block())
                        .anyMatch(e -> "replay-me".equals(e.text())));

        // A new client joining the same room should receive the message from history.
        List<String> replayReceived = new CopyOnWriteArrayList<>();
        Disposable replaySession = replayer.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(replayReceived::add)
                        .then()
        ).subscribe();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(replayReceived)
                        .anyMatch(s -> s.contains("replay-me") && s.contains("\"type\":\"msg\"")));

        replaySession.dispose();
    }
}
