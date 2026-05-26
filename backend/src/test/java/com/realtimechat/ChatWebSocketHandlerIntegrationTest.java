package com.realtimechat;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
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
class ChatWebSocketHandlerIntegrationTest extends AbstractChatWebSocketTest {

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

        // Give the async DB write time to complete before the replayer joins.
        // A plain sleep avoids calling .block() on a reactor chain from an Awaitility
        // thread, which can starve the shared Netty event loop in the full test suite.
        // 2000ms provides headroom for loaded CI runners and Testcontainers cold-start.
        Thread.sleep(2000);

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

    @Test
    void typingStartAndStopAreBroadcastToOtherClients() throws Exception {
        WebSocketClient sender = new ReactorNettyWebSocketClient();
        WebSocketClient observer = new ReactorNettyWebSocketClient();
        URI uri = uri("typing-test");

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

        // Send typing_start, then typing_stop.
        sender.execute(uri, session ->
                Mono.fromCallable(() -> json.writeValueAsString(
                                new TypingStart("typing-test", "userA")))
                        .flatMap(p -> session.send(Mono.just(session.textMessage(p))))
                        .then(Mono.delay(Duration.ofMillis(100)))
                        .then(Mono.fromCallable(() -> json.writeValueAsString(
                                        new TypingStop("typing-test", "userA")))
                                .flatMap(p -> session.send(Mono.just(session.textMessage(p)))))
                        .then()
        ).block(Duration.ofSeconds(5));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(observed).anyMatch(s -> s.contains("\"type\":\"typing_start\"")
                            && s.contains("\"senderId\":\"userA\""));
                    assertThat(observed).anyMatch(s -> s.contains("\"type\":\"typing_stop\"")
                            && s.contains("\"senderId\":\"userA\""));
                });

        observerSession.dispose();
    }

    @Test
    void ghostTypingIsCleanedUpWhenSenderDisconnects() throws Exception {
        WebSocketClient typer = new ReactorNettyWebSocketClient();
        WebSocketClient observer = new ReactorNettyWebSocketClient();
        URI uri = uri("ghost-typing-test");

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

        // Typer connects, sends typing_start, then disconnects without sending typing_stop.
        Disposable typerSession = typer.execute(uri, session ->
                Mono.fromCallable(() -> json.writeValueAsString(
                                new TypingStart("ghost-typing-test", "typerA")))
                        .flatMap(p -> session.send(Mono.just(session.textMessage(p))))
                        .then()
        ).subscribe();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"typing_start\"")
                                && s.contains("\"senderId\":\"typerA\"")));

        // Disconnect without typing_stop — server must auto-emit typing_stop.
        typerSession.dispose();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(observed)
                        .anyMatch(s -> s.contains("\"type\":\"typing_stop\"")
                                && s.contains("\"senderId\":\"typerA\"")));

        observerSession.dispose();
    }

    @Test
    void typingStartWithBlankSenderIdIsDropped() throws Exception {
        WebSocketClient sender = new ReactorNettyWebSocketClient();
        WebSocketClient observer = new ReactorNettyWebSocketClient();
        URI uri = uri("typing-blank-id-test");

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

        // Send TypingStart with blank senderId — server guard at ChatWebSocketHandler:179 must drop it.
        sender.execute(uri, session ->
                Mono.fromCallable(() -> json.writeValueAsString(
                                new TypingStart("typing-blank-id-test", "")))
                        .flatMap(p -> session.send(Mono.just(session.textMessage(p))))
                        .then()
        ).block(Duration.ofSeconds(5));

        Thread.sleep(300);

        assertThat(observed)
                .noneMatch(s -> s.contains("\"type\":\"typing_start\""));

        observerSession.dispose();
    }

    @Test
    void typingStopWithBlankSenderIdAfterTypingStartClearsIndicator() throws Exception {
        // Regression test for: blank-senderId TypingStop removes session from typingBySession
        // but previously skipped room.emit, leaving ghost typing in all peers. The fix uses
        // the server-stored senderId (from typingBySession) instead of the client-supplied one.
        WebSocketClient typer = new ReactorNettyWebSocketClient();
        WebSocketClient observer = new ReactorNettyWebSocketClient();
        URI uri = uri("typing-blank-stop-test");

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

        // Typer sends TypingStart with valid senderId, then TypingStop with blank senderId.
        typer.execute(uri, session ->
                Mono.fromCallable(() -> json.writeValueAsString(
                                new TypingStart("typing-blank-stop-test", "typerA")))
                        .flatMap(p -> session.send(Mono.just(session.textMessage(p))))
                        .then(Mono.delay(Duration.ofMillis(100)))
                        .then(Mono.fromCallable(() -> json.writeValueAsString(
                                        new TypingStop("typing-blank-stop-test", "")))
                                .flatMap(p -> session.send(Mono.just(session.textMessage(p)))))
                        .then()
        ).block(Duration.ofSeconds(5));

        // Observer must see typing_start followed by typing_stop with the server-stored senderId.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(observed).anyMatch(s -> s.contains("\"type\":\"typing_start\"")
                            && s.contains("\"senderId\":\"typerA\""));
                    assertThat(observed).anyMatch(s -> s.contains("\"type\":\"typing_stop\"")
                            && s.contains("\"senderId\":\"typerA\""));
                });

        observerSession.dispose();
    }
}
