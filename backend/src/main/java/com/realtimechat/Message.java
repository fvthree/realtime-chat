package com.realtimechat;

/**
 * A chat message. tempId is set by the sender for optimistic UI reconciliation;
 * server fills id. clientSendTs is the sender's Date.now() at keypress;
 * serverRecvTs is set when the server receives the frame. Both flow back to
 * receivers so the HUD can compute end-to-end latency honestly.
 */
public record Message(
        String id,
        String tempId,
        String roomId,
        String senderId,
        String text,
        long clientSendTs,
        long serverRecvTs
) implements ChatEvent {}
