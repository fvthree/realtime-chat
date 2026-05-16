package com.realtimechat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 *  Per WebSocket connection (session):
 *
 *           session.receive() ─────► parse JSON ─────► handleInbound()
 *                                                      │  (side-effects:
 *                                                      │   sink.tryEmitNext,
 *                                                      │   pong reply via personal sink)
 *                                                      ▼
 *                                              Room.sink ──┐
 *                                                          │ multicast()
 *                                          ┌───────────────┘
 *                                          │
 *                              merge personal pongs ───────► session.send()
 *
 *  CRITICAL: handle() must return Mono.zip(inboundDrain, outboundDrain). Returning
 *  only one terminates the duplex stream. This is the #1 WebFlux WebSocket footgun.
 */
public class ChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final RoomRegistry registry;
    private final ObjectMapper json;

    public ChatWebSocketHandler(RoomRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String roomId = extractRoomId(session);
        Room room = registry.getOrCreate(roomId);

        // Personal sink: receives pongs and other unicast-to-this-session events
        // (e.g. the Hello echo back). Distinct from room.sink which is shared.
        var personalSink = reactor.core.publisher.Sinks.many()
                .unicast()
                .<ChatEvent>onBackpressureBuffer();

        room.incrementSubscribers();
        broadcastPresence(room);

        Flux<ChatEvent> outboundEvents = Flux.merge(
                room.sink.asFlux(),
                personalSink.asFlux()
        ).onBackpressureBuffer(1024,
                dropped -> log.warn("dropping outbound event for slow subscriber {}: {}",
                        session.getId(), dropped));

        Mono<Void> outboundDrain = session.send(
                outboundEvents
                        .map(this::toJsonOrEmpty)
                        .filter(s -> !s.isEmpty())
                        .map(session::textMessage)
        );

        Mono<Void> inboundDrain = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> parseEvent(payload)
                        .map(evt -> handleInbound(evt, room, session, personalSink))
                        .orElseGet(Mono::empty))
                .then();

        return Mono.zip(inboundDrain, outboundDrain)
                .doFinally(sig -> {
                    int remaining = room.decrementSubscribers();
                    personalSink.tryEmitComplete();
                    broadcastPresence(room);
                    log.debug("session {} closed, {} subscribers remain in room {}",
                            session.getId(), remaining, room.id);
                })
                .then();
    }

    private Mono<Void> handleInbound(
            ChatEvent evt,
            Room room,
            WebSocketSession session,
            reactor.core.publisher.Sinks.Many<ChatEvent> personalSink
    ) {
        long now = System.currentTimeMillis();

        return switch (evt) {
            case Message m -> {
                Message stamped = new Message(
                        UUID.randomUUID().toString(),
                        m.tempId(),
                        room.id,
                        m.senderId(),
                        m.text(),
                        m.clientSendTs(),
                        now
                );
                emitToRoom(room, stamped);
                yield Mono.empty();
            }
            case Ping p -> {
                long sendTs = System.currentTimeMillis();
                Pong pong = new Pong(room.id, p.clientPingTs(), now, sendTs);
                personalSink.tryEmitNext(pong);
                yield Mono.empty();
            }
            case TypingStart t -> {
                emitToRoom(room, t);
                yield Mono.empty();
            }
            case TypingStop t -> {
                emitToRoom(room, t);
                yield Mono.empty();
            }
            case Hello h -> {
                broadcastPresence(room);
                yield Mono.empty();
            }
            case Pong ignored -> Mono.empty();
            case Presence ignored -> Mono.empty();
        };
    }

    private void emitToRoom(Room room, ChatEvent event) {
        var result = room.sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("emit failed for room {}: {}", room.id, result);
        }
    }

    private void broadcastPresence(Room room) {
        emitToRoom(room, new Presence(room.id, room.currentSubscribers()));
    }

    private String extractRoomId(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) return "lobby";
        return path.substring(idx + 1);
    }

    private java.util.Optional<ChatEvent> parseEvent(String payload) {
        try {
            return java.util.Optional.of(json.readValue(payload, ChatEvent.class));
        } catch (JsonProcessingException e) {
            log.warn("dropping malformed event: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String toJsonOrEmpty(ChatEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("dropping unserializable event: {}", e.getMessage());
            return "";
        }
    }
}
