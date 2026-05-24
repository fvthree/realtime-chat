package com.realtimechat;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("messages")
public record MessageEntity(
        @Id                        UUID    id,
        @Column("room_id")         String  roomId,
        @Column("sender_id")       String  senderId,
                                   String  text,
        @Column("client_send_ts")  Long    clientSendTs,
        @Column("server_recv_ts")  Long    serverRecvTs,
        @Column("created_at")      Instant createdAt
) implements Persistable<UUID> {

    // Always new: messages are immutable, we never UPDATE them.
    // Without this, Spring Data R2DBC would try UPDATE (not INSERT) for any non-null id.
    @Override public boolean isNew() { return true; }
    @Override public UUID   getId()  { return id; }
}
