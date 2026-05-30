package com.realtimechat;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAuthWebFilter.class)
class ChatWebSocketHandlerIntegrationTest extends AbstractChatWebSocketTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ReactorNettyWebSocketClient client() {
        return new ReactorNettyWebSocketClient();
    }

    // ── Auth gate ─────────────────────────────────────────────────────────────

    @Test
    void unauthenticatedUpgradeIsRejected() {
        // No X-Auth-User header → TestAuthWebFilter injects no principal.
        // Spring Security's authorizeExchange rejects the upgrade (non-101 response).
        // This locks in the critical Stage 4 security invariant: anonymous WS connections
        // are refused before any message processing occurs.
        assertThatThrownBy(() ->
                client().execute(uri("unauth-test"), new HttpHeaders(),
                        session -> session.receive().then()
                ).block(Duration.ofSeconds(5))
        ).isInstanceOf(Exception.class)
         .hasMessageContaining("Invalid handshake response");
    }

    // ── Core message exchange ─────────────────────────────────────────────────

    @Test
    void twoClientsExchangeMessage() throws Exception {
        URI uri = uri("exchange-test");
        List<String> receivedByB = new CopyOnWriteArrayList<>();

        // clientB authenticates as "bob"
        Disposable bSession = client().execute(uri, authHeaders("bob"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(receivedByB::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByB)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        // clientA authenticates as "alice", sends a message
        client().execute(uri, authHeaders("alice"), session ->
                Mono.fromCallable(() -> {
                            // senderId in the wire is ignored — server stamps "alice" from principal
                            Message m = new Message(null, "tmp-1", "exchange-test", "ignored", "hello B", 1000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then()
        ).block(Duration.ofSeconds(5));

        // Message must be echoed with server-stamped senderId="alice", not "ignored"
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByB)
                        .anyMatch(s -> s.contains("hello B")
                                && s.contains("\"type\":\"msg\"")
                                && s.contains("\"senderId\":\"alice\"")));

        bSession.dispose();
    }

    @Test
    void serverStampsSenderIdFromPrincipalNotWirePayload() throws Exception {
        URI uri = uri("senderid-stamp-test");
        List<String> received = new CopyOnWriteArrayList<>();

        Disposable observer = client().execute(uri, authHeaders("observer"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        // Send a message claiming to be "victim" — server must ignore and stamp "attacker"
        client().execute(uri, authHeaders("attacker"), session ->
                Mono.fromCallable(() -> {
                            Message m = new Message(null, "tmp-spoof", "senderid-stamp-test",
                                    "victim", "impersonated", 1000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then()
        ).block(Duration.ofSeconds(5));

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received)
                        .anyMatch(s -> s.contains("impersonated")
                                && s.contains("\"senderId\":\"attacker\"")
                                && !s.contains("\"senderId\":\"victim\"")));

        observer.dispose();
    }

    @Test
    void rateLimitDropsExcessMessages() throws Exception {
        URI uri = uri("ratelimit-test");
        List<String> received = new CopyOnWriteArrayList<>();

        Disposable observer = client().execute(uri, authHeaders("watcher"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        // Send 30 messages as fast as possible; at 3 msg/sec only a few should go through
        client().execute(uri, authHeaders("spammer"), session -> {
            Mono<Void> sends = Mono.empty();
            for (int i = 0; i < 30; i++) {
                final int idx = i;
                sends = sends.then(Mono.fromCallable(() -> {
                            Message m = new Message(null, "tmp-" + idx, "ratelimit-test",
                                    "spammer", "msg-" + idx, 1000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload)))));
            }
            return sends;
        }).block(Duration.ofSeconds(5));

        Thread.sleep(500);

        // With 3 msg/sec rate limit and ~0.5s window, at most 2-3 messages should get through
        long msgCount = received.stream().filter(s -> s.contains("\"type\":\"msg\"")).count();
        assertThat(msgCount).isLessThanOrEqualTo(5);

        observer.dispose();
    }

    @Test
    void oversizedMessageIsDropped() throws Exception {
        URI uri = uri("oversize-test");
        List<String> received = new CopyOnWriteArrayList<>();

        Disposable observer = client().execute(uri, authHeaders("observer"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        String oversized = "x".repeat(10_001);
        client().execute(uri, authHeaders("sender"), session ->
                Mono.fromCallable(() -> {
                            Message m = new Message(null, "tmp-big", "oversize-test",
                                    "sender", oversized, 1000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then(Mono.delay(Duration.ofMillis(500)))
                        .then()
        ).block(Duration.ofSeconds(6));

        assertThat(received).noneMatch(s -> s.contains("\"type\":\"msg\""));

        observer.dispose();
    }

    @Test
    void roomIdExceeding32CharsRedirectsToLobby() throws Exception {
        // A 33-char room ID exceeds the max and must fall back to "lobby"
        String oversizedRoomId = "a".repeat(33);
        List<String> lobbyReceived = new CopyOnWriteArrayList<>();

        Disposable lobbySession = client().execute(uri("lobby"), authHeaders("lobby-user"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(lobbyReceived::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(lobbyReceived)
                        .anyMatch(s -> s.contains("\"connected\":1")));

        // Client using 33-char room ID should land in lobby (server falls back)
        Disposable oversizedSession = client().execute(uri(oversizedRoomId), authHeaders("oversized-user"), session ->
                session.receive().then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(lobbyReceived)
                        .anyMatch(s -> s.contains("\"connected\":2")));

        lobbySession.dispose();
        oversizedSession.dispose();
    }

    @Test
    void pingReceivesPongWithThreeTimestamps() throws Exception {
        URI uri = uri("ping-test");
        List<String> received = new CopyOnWriteArrayList<>();

        client().execute(uri, authHeaders("pinger"), session ->
                Mono.fromCallable(() -> {
                            Ping ping = new Ping("ping-test", "pinger", 9999L);
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
        URI uri = uri("presence-test");
        List<String> receivedByA = new CopyOnWriteArrayList<>();

        Disposable aSession = client().execute(uri, authHeaders("userA"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(receivedByA::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByA)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"") && s.contains("\"connected\":1")));

        Disposable bSession = client().execute(uri, authHeaders("userB"), session ->
                session.receive().map(WebSocketMessage::getPayloadAsText).then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(receivedByA)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"") && s.contains("\"connected\":2")));

        bSession.dispose();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(
                        receivedByA.stream()
                                .filter(s -> s.contains("\"type\":\"presence\"") && s.contains("\"connected\":1"))
                                .count()
                ).isGreaterThanOrEqualTo(2));

        aSession.dispose();
    }

    @Test
    void helloTriggersPresenceBroadcast() {
        URI uri = uri("hello-test");
        List<String> received = new CopyOnWriteArrayList<>();

        client().execute(uri, authHeaders("hello-user"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .take(Duration.ofMillis(600))
                        .then()
                        .doFirst(() ->
                                Mono.delay(Duration.ofMillis(100))
                                        .then(Mono.fromCallable(() ->
                                                json.writeValueAsString(new Hello("hello-test", "hello-user"))))
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
        URI uri = uri("replay-test");
        List<String> senderReceived = new CopyOnWriteArrayList<>();

        Disposable senderSession = client().execute(uri, authHeaders("alice"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(senderReceived::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(senderReceived)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        client().execute(uri, authHeaders("alice"), session ->
                Mono.fromCallable(() -> {
                            Message m = new Message(null, "tmp-replay", "replay-test",
                                    "alice", "replay-me", 5000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then()
        ).block(Duration.ofSeconds(5));

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(senderReceived)
                        .anyMatch(s -> s.contains("replay-me")));

        senderSession.dispose();
        Thread.sleep(2000);

        List<String> replayReceived = new CopyOnWriteArrayList<>();
        Disposable replaySession = client().execute(uri, authHeaders("bob"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(replayReceived::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(replayReceived)
                        .anyMatch(s -> s.contains("replay-me") && s.contains("\"type\":\"msg\"")));

        replaySession.dispose();
    }

    // ── Typing tests ──────────────────────────────────────────────────────────

    @Test
    void typingStartBroadcastsWithPrincipalSenderIdNotWireValue() throws Exception {
        URI uri = uri("typing-principal-test");
        List<String> observed = new CopyOnWriteArrayList<>();

        Disposable observerSession = client().execute(uri, authHeaders("observer"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(observed::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        // Wire senderId is "spoofed" — server must use "real-user" from the principal
        client().execute(uri, authHeaders("real-user"), session ->
                Mono.fromCallable(() -> json.writeValueAsString(
                                new TypingStart("typing-principal-test", "spoofed")))
                        .flatMap(p -> session.send(Mono.just(session.textMessage(p))))
                        .then()
        ).block(Duration.ofSeconds(5));

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"typing_start\"")
                                && s.contains("\"senderId\":\"real-user\"")));

        observerSession.dispose();
    }

    @Test
    void ghostTypingIsCleanedUpWhenSenderDisconnects() throws Exception {
        URI uri = uri("ghost-typing-test");
        List<String> observed = new CopyOnWriteArrayList<>();

        Disposable observerSession = client().execute(uri, authHeaders("observer"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(observed::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        Disposable typerSession = client().execute(uri, authHeaders("typer"), session ->
                Mono.fromCallable(() -> json.writeValueAsString(
                                new TypingStart("ghost-typing-test", "typer")))
                        .flatMap(p -> session.send(Mono.just(session.textMessage(p))))
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"typing_start\"")
                                && s.contains("\"senderId\":\"typer\"")));

        typerSession.dispose();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"typing_stop\"")
                                && s.contains("\"senderId\":\"typer\"")));

        observerSession.dispose();
    }

    @Test
    void typingStopClearsIndicatorRegardlessOfWireSenderId() throws Exception {
        // Regression: blank-senderId TypingStop used to leave ghost typing.
        // In Stage 4, server uses the stored principal senderId — wire value is irrelevant.
        URI uri = uri("typing-stop-principal-test");
        List<String> observed = new CopyOnWriteArrayList<>();

        Disposable observerSession = client().execute(uri, authHeaders("observer"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(observed::add)
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"presence\"")));

        Sinks.One<Void> shutdownSignal = Sinks.one();

        Disposable typerSession = client().execute(uri, authHeaders("typer"), session ->
                Mono.fromCallable(() -> json.writeValueAsString(
                                new TypingStart("typing-stop-principal-test", "typer")))
                        .flatMap(p -> session.send(Mono.just(session.textMessage(p))))
                        .then(Mono.delay(Duration.ofMillis(100)))
                        // Send TypingStop with blank wire senderId — server must still emit stop with "typer"
                        .then(Mono.fromCallable(() -> json.writeValueAsString(
                                        new TypingStop("typing-stop-principal-test", "")))
                                .flatMap(p -> session.send(Mono.just(session.textMessage(p)))))
                        .then(shutdownSignal.asMono())
                        .then()
        ).subscribe();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(observed).anyMatch(s -> s.contains("\"type\":\"typing_start\"")
                            && s.contains("\"senderId\":\"typer\""));
                    assertThat(observed).anyMatch(s -> s.contains("\"type\":\"typing_stop\"")
                            && s.contains("\"senderId\":\"typer\""));
                });

        shutdownSignal.tryEmitEmpty();
        typerSession.dispose();
        observerSession.dispose();
    }
}
