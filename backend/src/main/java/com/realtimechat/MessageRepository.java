package com.realtimechat;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface MessageRepository extends ReactiveCrudRepository<MessageEntity, UUID> {

    // Returns the 50 most recent messages in the room, ordered oldest-first for display.
    // created_at (microsecond resolution) breaks ties when two messages share the same server_recv_ts millisecond.
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages
            WHERE room_id = :roomId
            ORDER BY server_recv_ts DESC, created_at DESC
            LIMIT 50
        ) AS recent
        ORDER BY server_recv_ts ASC, created_at ASC
    """)
    Flux<MessageEntity> findLast50ByRoomId(String roomId);
}
