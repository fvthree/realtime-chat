package com.realtimechat;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Rooms are created lazily on first join and evicted when the last subscriber
 * disconnects. compute() is used for eviction so the subscriber-count check and
 * map removal are atomic with respect to getOrCreate's computeIfAbsent, preventing
 * a room with a concurrent new joiner from being incorrectly evicted.
 */
@Component
public class RoomRegistry {
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();

    public Room getOrCreate(String roomId) {
        return rooms.computeIfAbsent(roomId, Room::new);
    }

    public void evictIfEmpty(String roomId, Room room) {
        // compute() is per-key atomic: interleaves safely with computeIfAbsent.
        // Only remove if this is still the same room object AND it has no subscribers.
        rooms.compute(roomId, (k, existing) ->
                (existing == room && room.currentSubscribers() == 0) ? null : existing);
    }

    public int roomCount() {
        return rooms.size();
    }
}
