CREATE TABLE IF NOT EXISTS messages (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id        VARCHAR(64) NOT NULL,
    sender_id      VARCHAR(128) NOT NULL,
    text           TEXT        NOT NULL,
    client_send_ts BIGINT      NOT NULL,
    server_recv_ts BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for fast last-N lookup per room
CREATE INDEX IF NOT EXISTS idx_messages_room_ts
    ON messages (room_id, server_recv_ts DESC);
