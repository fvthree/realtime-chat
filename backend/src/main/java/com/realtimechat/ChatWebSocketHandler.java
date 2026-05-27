package com.realtimechat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 *  Per WebSocket connection (session):
 *
 *           session.receive() ─────► parse JSON ─────► handleInbound()
 *                                                      │  (side-effects:
 *                                                      │   persist to DB,
 *                                                      │   room.emit,
 *                                                      │   pong reply via personal sink)
 *                                                      ▼
 *                                              Room.sink ──┐
 *                                                          │ multicast()
 *                                          ┌───────────────┘
 *                                          │
 *                       history (DB) ──► concat ──────► session.send()
 *
 *  History is replayed before live: Flux.concat(history, live) ensures the
 *  last 50 DB messages arrive first, then the live stream picks up seamlessly.
 *  Duplicates are deduped client-side by server message id.
 *
 *  CRITICAL: handle() must return Mono.zip(inboundDrain, outboundDrain). Returning
 *  only one terminates the duplex stream. This is the #1 WebFlux WebSocket footgun.
 */
public class ChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final RoomRegistry registry;
    private final ObjectMapper json;
    private final MessageRepository messageRepository;

    public ChatWebSocketHandler(RoomRegistry registry, ObjectMapper json, MessageRepository messageRepository) {
        this.registry = registry;
        this.json = json;
        this.messageRepository = messageRepository;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String roomId = extractRoomId(session);
        Room room = registry.getOrCreate(roomId);

        // Personal sink: receives pongs and other unicast-to-this-session events.
        // Distinct from room.sink which is shared across all subscribers.
        var personalSink = reactor.core.publisher.Sinks.many()
                .unicast()
                .<ChatEvent>onBackpressureBuffer();

        room.incrementSubscribers();
        broadcastPresence(room);

        // History: last 50 persisted messages, oldest-first.
        Flux<ChatEvent> history = messageRepository.findLast50ByRoomId(roomId)
                .map(e -> (ChatEvent) new Message(
                        e.id().toString(),
                        null,            // tempId: null for replayed messages
                        e.roomId(),
                        e.senderId(),
                        e.text(),
                        e.clientSendTs(),
                        e.serverRecvTs()
                ))
                .onErrorResume(err -> {
                    log.warn("history fetch failed for room {}: {}", roomId, err.getMessage());
                    return Flux.empty();
                });

        // Connect a replay buffer BEFORE the history query starts. Flux.concat only subscribes
        // to the live stream after history completes; without this buffer, messages emitted to
        // the room during the DB fetch are permanently lost for this joiner. The replay buffer
        // captures those events so concat can drain them immediately after history completes.
        // Client deduplicates by server message id, so any overlap with history is harmless.
        // replay(1024) is the per-connection event buffer. No additional onBackpressureBuffer needed here —
        // room.sink already has its own 1024-element buffer (see Room.java). A second buffer here just delays
        // the drop log without changing behaviour; events that exceed the replay window are silently dropped
        // (slow subscriber stays connected with gaps, not disconnected — see Room.java Javadoc).
        ConnectableFlux<ChatEvent> live = Flux.merge(room.sink.asFlux(), personalSink.asFlux())
                .replay(1024);
        Disposable liveConnection = live.connect();

        Mono<Void> outboundDrain = session.send(
                Flux.concat(history, live)
                        .map(this::toJsonOrEmpty)
                        .filter(s -> !s.isEmpty())
                        .map(session::textMessage)
        );

        // concatMap (not flatMap) preserves per-session message ordering.
        // flatMap would silently reorder messages if handleInbound ever becomes async (e.g., Stage 4 auth check).
        Mono<Void> inboundDrain = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(payload -> parseEvent(payload)
                        .map(evt -> handleInbound(evt, room, session, personalSink))
                        .orElseGet(Mono::empty))
                .then();

        return Mono.zip(inboundDrain, outboundDrain)
                .doFinally(sig -> {
                    liveConnection.dispose();
                    // Ghost-typing fix: emit TypingStop for the disconnecting session.
                    String typingSenderId = room.typingBySession.remove(session.getId());
                    if (typingSenderId != null) {
                        room.emit(new TypingStop(room.id, typingSenderId));
                    }
                    int remaining = room.decrementSubscribers();
                    personalSink.tryEmitComplete();
                    broadcastPresence(room);
                    if (remaining == 0) {
                        registry.evictIfEmpty(room.id, room);
                    }
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
                if (m.senderId() == null || m.senderId().isBlank() || m.text() == null || m.text().isBlank()) {
                    log.warn("dropping malformed message with blank senderId or text from session {}", session.getId());
                    yield Mono.empty();
                }
                Message stamped = new Message(
                        UUID.randomUUID().toString(),
                        m.tempId(),
                        room.id,
                        m.senderId(),
                        m.text(),
                        m.clientSendTs(),
                        now
                );
                // Persist fire-and-forget; broadcast immediately regardless of DB outcome.
                messageRepository.save(new MessageEntity(
                        UUID.fromString(stamped.id()),
                        stamped.roomId(),
                        stamped.senderId(),
                        stamped.text(),
                        stamped.clientSendTs(),
                        stamped.serverRecvTs(),
                        Instant.now()
                )).subscribe(
                        saved -> {},
                        err -> log.warn("persist failed for message {}: {}", stamped.id(), err.getMessage())
                );
                room.emit(stamped);
                yield Mono.empty();
            }
            case Ping p -> {
                long sendTs = System.currentTimeMillis();
                Pong pong = new Pong(room.id, p.clientPingTs(), now, sendTs);
                personalSink.tryEmitNext(pong);
                yield Mono.empty();
            }
            case TypingStart t -> {
                if (t.senderId() == null || t.senderId().isBlank()) {
                    yield Mono.empty();
                }
                room.typingBySession.put(session.getId(), t.senderId());
                room.emit(t);
                yield Mono.empty();
            }
            case TypingStop t -> {
                // Use the server-stored senderId, not the client-supplied one.
                // This prevents: (a) ghost typing when blank senderId clears the map but skips emit,
                // and (b) impersonation via {"type":"typing_stop","senderId":"victimUser"}.
                String storedSenderId = room.typingBySession.remove(session.getId());
                if (storedSenderId != null) {
                    room.emit(new TypingStop(room.id, storedSenderId));
                }
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

    private void broadcastPresence(Room room) {
        room.emit(new Presence(room.id, room.currentSubscribers()));
    }

    private String extractRoomId(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) return "lobby";
        String candidate = path.substring(idx + 1);
        if (candidate.length() > 64 || !candidate.matches("[a-z0-9-]+")) return "lobby";
        return candidate;
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
