package com.realtimechat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 *  Per WebSocket connection (session):
 *
 *           session.receive() ─────► parse JSON ─────► handleInbound()
 *                                                      │  (side-effects:
 *                                                      │   rate-limit check,
 *                                                      │   persist to DB,
 *                                                      │   room.emit with principal senderId,
 *                                                      │   pong reply via personal sink)
 *                                                      ▼
 *                                              Room.sink ──┐
 *                                                          │ multicast()
 *                                          ┌───────────────┘
 *                                          │
 *                       history (DB) ──► concat ──────► session.send()
 *
 *  Auth: handle() extracts the OAuth2 principal once per session.
 *  senderId is always the authenticated GitHub login — the wire-supplied senderId is ignored.
 *
 *  Empty principal guard: if getPrincipal() is empty (session expired between OAuth and
 *  WS upgrade), the session is closed immediately with NOT_ACCEPTABLE.
 *
 *  Rate limiting: RateLimiterService.tryAcquire() is called per Message frame.
 *  Non-blocking; uses Guava tryAcquire().
 *
 *  CRITICAL: handle() must return Mono.zip(inboundDrain, outboundDrain). Returning
 *  only one terminates the duplex stream. This is the #1 WebFlux WebSocket footgun.
 */
public class ChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final int MAX_MESSAGE_TEXT_LENGTH = 10_000;
    private static final java.util.regex.Pattern ROOM_ID_PATTERN = java.util.regex.Pattern.compile("[a-z0-9-]+");

    private final RoomRegistry registry;
    private final ObjectMapper json;
    private final MessageRepository messageRepository;
    private final RateLimiterService rateLimiterService;

    public ChatWebSocketHandler(
            RoomRegistry registry,
            ObjectMapper json,
            MessageRepository messageRepository,
            RateLimiterService rateLimiterService
    ) {
        this.registry = registry;
        this.json = json;
        this.messageRepository = messageRepository;
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return session.getHandshakeInfo().getPrincipal()
                .switchIfEmpty(
                        session.close(CloseStatus.NOT_ACCEPTABLE)
                                .doOnSuccess(v -> log.warn(
                                        "WS upgrade with no principal, closing session {}", session.getId()))
                                .then(Mono.empty())
                )
                .flatMap(principal -> handleAuthenticated(session, extractLogin(principal)));
    }

    private String extractLogin(Principal principal) {
        if (principal instanceof OAuth2AuthenticationToken oauth) {
            Object login = oauth.getPrincipal().getAttributes().get("login");
            if (login instanceof String s && !s.isBlank()) return s;
        }
        // Fallback for test principals (UsernamePasswordAuthenticationToken, @WithMockUser)
        return principal.getName();
    }

    private Mono<Void> handleAuthenticated(WebSocketSession session, String authenticatedUserId) {
        String roomId = extractRoomId(session);
        Room room = registry.getOrCreate(roomId);

        // Personal sink: receives pongs and other unicast-to-this-session events.
        var personalSink = reactor.core.publisher.Sinks.many()
                .unicast()
                .<ChatEvent>onBackpressureBuffer();

        room.incrementSubscribers();
        broadcastPresence(room);

        // History: last 50 persisted messages, oldest-first.
        Flux<ChatEvent> history = messageRepository.findLast50ByRoomId(roomId)
                .map(e -> (ChatEvent) new Message(
                        e.id().toString(),
                        null,
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
        ConnectableFlux<ChatEvent> live = Flux.merge(room.sink.asFlux(), personalSink.asFlux())
                .replay(1024);
        Disposable liveConnection = live.connect();

        Mono<Void> outboundDrain = session.send(
                Flux.concat(history, live)
                        .map(this::toJsonOrEmpty)
                        .filter(s -> !s.isEmpty())
                        .map(session::textMessage)
        );

        // concatMap preserves per-session message ordering and is safe when handleInbound
        // becomes async (e.g., auth cache miss in a future stage).
        Mono<Void> inboundDrain = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(payload -> parseEvent(payload)
                        .map(evt -> handleInbound(evt, room, session, personalSink, authenticatedUserId))
                        .orElseGet(Mono::empty))
                .then();

        return Mono.zip(inboundDrain, outboundDrain)
                .doFinally(sig -> {
                    liveConnection.dispose();
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
                    log.debug("session {} ({}) closed, {} subscribers remain in room {}",
                            session.getId(), authenticatedUserId, remaining, room.id);
                })
                .then();
    }

    private Mono<Void> handleInbound(
            ChatEvent evt,
            Room room,
            WebSocketSession session,
            reactor.core.publisher.Sinks.Many<ChatEvent> personalSink,
            String authenticatedUserId
    ) {
        long now = System.currentTimeMillis();

        return switch (evt) {
            case Message m -> {
                if (m.text() == null || m.text().isBlank()) {
                    log.warn("dropping blank-text message from session {}", session.getId());
                    yield Mono.empty();
                }
                if (m.text().length() > MAX_MESSAGE_TEXT_LENGTH) {
                    log.warn("dropping oversized message ({} chars) from session {}", m.text().length(), session.getId());
                    yield Mono.empty();
                }
                if (!rateLimiterService.tryAcquire(authenticatedUserId)) {
                    yield Mono.empty();
                }
                Message stamped = new Message(
                        UUID.randomUUID().toString(),
                        m.tempId(),
                        room.id,
                        authenticatedUserId,   // server-derived, not client-supplied
                        m.text(),
                        m.clientSendTs(),
                        now
                );
                messageRepository.save(new MessageEntity(
                        UUID.fromString(stamped.id()),
                        stamped.roomId(),
                        stamped.senderId(),
                        stamped.text(),
                        stamped.clientSendTs(),
                        stamped.serverRecvTs(),
                        Instant.now()
                )).timeout(Duration.ofSeconds(5)).subscribe(
                        saved -> {},
                        err -> log.warn("persist failed for message {}: {}", stamped.id(), err.getMessage())
                );
                room.emit(stamped);
                yield Mono.empty();
            }
            case Ping p -> {
                long sendTs = System.currentTimeMillis();
                personalSink.tryEmitNext(new Pong(room.id, p.clientPingTs(), now, sendTs));
                yield Mono.empty();
            }
            case TypingStart t -> {
                // Use authenticated identity — ignore client-supplied senderId.
                room.typingBySession.put(session.getId(), authenticatedUserId);
                room.emit(new TypingStart(room.id, authenticatedUserId));
                yield Mono.empty();
            }
            case TypingStop t -> {
                // Use the server-stored senderId, not the client-supplied one.
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
        if (candidate.length() > 32 || !ROOM_ID_PATTERN.matcher(candidate).matches()) return "lobby";
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
