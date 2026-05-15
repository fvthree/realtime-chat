package com.realtimechat;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stage 1: rooms are created lazily on first join and never evicted.
 * Acceptable trade-off for a localhost demo. Stage 2 will add either
 * Caffeine TTL eviction or refcount-based removal when subscriberCount
 * hits zero.
 */
@Component
public class RoomRegistry {
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();

    public Room getOrCreate(String roomId) {
        return rooms.computeIfAbsent(roomId, Room::new);
    }

    public int roomCount() {
        return rooms.size();
    }
}
