package com.realtimechat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertSame;

class RoomRegistryTest {

    @Test
    void concurrentGetOrCreateReturnsSameRoom() throws Exception {
        RoomRegistry registry = new RoomRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(16);

        try {
            var futures = IntStream.range(0, 200)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> registry.getOrCreate("lobby"), pool))
                    .toList();

            Set<Room> distinct = new HashSet<>();
            for (var f : futures) {
                distinct.add(f.get());
            }

            assertEquals(1, distinct.size(), "computeIfAbsent must return the same Room");
            assertEquals(1, registry.roomCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void distinctRoomIdsCreateDistinctRooms() {
        RoomRegistry registry = new RoomRegistry();
        Room a = registry.getOrCreate("lobby");
        Room b = registry.getOrCreate("kitchen");
        assertEquals(2, registry.roomCount());
        assertEquals("lobby", a.id);
        assertEquals("kitchen", b.id);
    }

    @Test
    void getOrCreateIsIdempotent() {
        RoomRegistry registry = new RoomRegistry();
        Room first = registry.getOrCreate("lobby");
        Room second = registry.getOrCreate("lobby");
        assertSame(first, second);
    }

    @Test
    void evictIfEmptyRemovesRoomWithNoSubscribers() {
        RoomRegistry registry = new RoomRegistry();
        Room room = registry.getOrCreate("lobby");
        assertEquals(1, registry.roomCount());

        registry.evictIfEmpty("lobby", room);

        assertEquals(0, registry.roomCount());
    }

    @Test
    void evictIfEmptyDoesNotRemoveRoomWithActiveSubscribers() {
        RoomRegistry registry = new RoomRegistry();
        Room room = registry.getOrCreate("lobby");
        room.incrementSubscribers();
        assertEquals(1, registry.roomCount());

        registry.evictIfEmpty("lobby", room);

        assertEquals(1, registry.roomCount(), "room with subscribers must not be evicted");
    }

    @Test
    void evictIfEmptyIgnoresStaleRoomObject() {
        // If the room was already replaced by a new joiner, evictIfEmpty must not remove the new room.
        RoomRegistry registry = new RoomRegistry();
        Room stale = registry.getOrCreate("lobby");
        // Evict the stale room first so the map is empty, then a new joiner creates a fresh room.
        registry.evictIfEmpty("lobby", stale);
        Room fresh = registry.getOrCreate("lobby");
        assertNotSame(stale, fresh);

        // Trying to evict with the stale reference must be a no-op.
        registry.evictIfEmpty("lobby", stale);

        assertEquals(1, registry.roomCount(), "fresh room must survive eviction with stale reference");
        assertSame(fresh, registry.getOrCreate("lobby"));
    }

    @Test
    void evictIfEmptyRaceWithGetOrCreate() throws Exception {
        // Interleave 100 evictions with 100 concurrent getOrCreate calls.
        // The registry must never be left in a state where roomCount() > 1.
        RoomRegistry registry = new RoomRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);

        var futures = IntStream.range(0, 100).mapToObj(i ->
                CompletableFuture.runAsync(() -> {
                    try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    Room r = registry.getOrCreate("lobby");
                    r.decrementSubscribers(); // simulate disconnect (count stays at 0)
                    registry.evictIfEmpty("lobby", r);
                }, pool)
        ).toList();

        start.countDown();
        for (var f : futures) f.get();

        // After all races settle, the room count must be 0 or 1 — never more.
        assertTrue(registry.roomCount() <= 1, "room count must not exceed 1 after concurrent evictions");
        pool.shutdownNow();
    }
}
