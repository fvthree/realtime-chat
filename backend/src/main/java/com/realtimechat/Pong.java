package com.realtimechat;

/**
 * Pong reply for clock-sync. Carries three timestamps so the client can solve
 * for clock offset and round-trip latency (NTP-style). Stage 1 uses a simple
 * RTT/2 estimate; Stage 3 adds median-of-N sampling.
 */
public record Pong(
        String roomId,
        long clientPingTs,
        long serverRecvTs,
        long serverSendTs
) implements ChatEvent {}
