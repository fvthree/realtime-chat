package com.realtimechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketHandlerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Test
    void twoClientsExchangeMessage() throws Exception {
        WebSocketClient clientA = new ReactorNettyWebSocketClient();
        WebSocketClient clientB = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/chat/lobby");

        List<String> receivedByB = new ArrayList<>();

        // Client B subscribes first.
        var bSession = clientB.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(receivedByB::add)
                        .then()
        ).subscribe();

        Thread.sleep(200); // give B time to subscribe to room sink

        // Client A connects and sends a message after a small delay.
        clientA.execute(uri, session ->
                Mono.fromCallable(() -> {
                            Message m = new Message(null, "tmp-1", "lobby", "userA", "hello B", 1000L, 0L);
                            return json.writeValueAsString(m);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .then(Mono.delay(Duration.ofMillis(500)))
                        .then()
        ).block(Duration.ofSeconds(5));

        Thread.sleep(500); // let event propagate
        bSession.dispose();

        assertThat(receivedByB)
                .as("Client B should receive the message sent by A (plus presence events)")
                .anyMatch(s -> s.contains("hello B") && s.contains("\"type\":\"msg\""));
    }

    @Test
    void pingReceivesPongWithThreeTimestamps() throws Exception {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/chat/lobby");

        List<String> received = new ArrayList<>();

        client.execute(uri, session ->
                Mono.fromCallable(() -> {
                            Ping ping = new Ping("lobby", "userA", 9999L);
                            return json.writeValueAsString(ping);
                        })
                        .flatMap(payload -> session.send(Mono.just(session.textMessage(payload))))
                        .thenMany(session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(received::add)
                                .take(Duration.ofMillis(800)))
                        .then()
        ).block(Duration.ofSeconds(3));

        assertThat(received)
                .anyMatch(s -> s.contains("\"type\":\"pong\"")
                        && s.contains("\"clientPingTs\":9999")
                        && s.contains("serverRecvTs")
                        && s.contains("serverSendTs"));
    }
}
