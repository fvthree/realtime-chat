package com.realtimechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *                 inbound (from any session in this room)
 *                              │
 *                              ▼
 *                       ┌─────────────┐
 *                       │  room.emit  │
 *                       └──────┬──────┘
 *                              │ multicast()
 *                              │ onBackpressureBuffer(1024)
 *                              ▼
 *           ┌──────────────────┴──────────────────┐
 *           ▼                  ▼                   ▼
 *       session A          session B           session C
 *       (Flux out)         (Flux out)          (Flux out)
 *
 * A slow subscriber whose outbound buffer overflows loses events (silent drop with warn log)
 * but stays connected. The handler's replay(1024) window per connection bounds per-connection
 * memory. Stage 5 can layer in session.close() from the drop callback if forced-reconnect
 * semantics are desired.
 */
public final class Room {
    private static final Logger log = LoggerFactory.getLogger(Room.class);

    public final String id;
    final Sinks.Many<ChatEvent> sink;
    public final AtomicInteger subscriberCount = new AtomicInteger(0);
    // sessionId → senderId; tracks who is typing so disconnect can emit TypingStop.
    final ConcurrentHashMap<String, String> typingBySession = new ConcurrentHashMap<>();

    public Room(String id) {
        this.id = id;
        this.sink = Sinks.many().multicast().onBackpressureBuffer(1024, false);
    }

    void emit(ChatEvent event) {
        sink.emitNext(event, (signalType, emitResult) -> {
            if (emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                return true; // concurrent emit — spin and retry
            }
            log.warn("emit failed for room {}: {}", id, emitResult);
            return false;
        });
    }

    public int incrementSubscribers() {
        return subscriberCount.incrementAndGet();
    }

    public int decrementSubscribers() {
        return subscriberCount.decrementAndGet();
    }

    public int currentSubscribers() {
        return subscriberCount.get();
    }
}
