package com.realtimechat;

import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

/**
 *                 inbound (from any session in this room)
 *                              │
 *                              ▼
 *                       ┌─────────────┐
 *                       │ sink.tryEmit │
 *                       └──────┬──────┘
 *                              │ multicast()
 *                              │ onBackpressureBuffer(1024)
 *                              ▼
 *           ┌──────────────────┴──────────────────┐
 *           ▼                  ▼                   ▼
 *       session A          session B           session C
 *       (Flux out)         (Flux out)          (Flux out)
 *
 * A slow subscriber whose outbound buffer overflows is disconnected by the
 * handler; the client reconnects with exponential backoff. This trades one
 * specific failure mode (a stuck tab) for prevention of OOM and silent drops.
 */
public final class Room {
    public final String id;
    public final Sinks.Many<ChatEvent> sink;
    public final AtomicInteger subscriberCount = new AtomicInteger(0);

    public Room(String id) {
        this.id = id;
        this.sink = Sinks.many().multicast().onBackpressureBuffer(1024, false);
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
