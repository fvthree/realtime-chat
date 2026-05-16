package com.realtimechat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
