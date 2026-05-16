import { useCallback, useEffect, useRef, useState } from "react";
import type { WirePing, WirePong } from "../types";

/**
 * 5-ping clock-sync handshake. Stage 1 estimate:
 *
 *   offset = serverRecvTs - (clientPingTs + rtt/2)
 *
 * We take the median across samples to reject outliers. Stage 3 replaces this
 * with full NTP-style 4-timestamp solve over a sliding window.
 *
 * Until the first sample arrives, offset is null and the HUD shows "—".
 */

const SAMPLE_TARGET = 5;
const PING_INTERVAL_MS = 60;
const PING_TIMEOUT_MS = 3000;

export type ClockSync = {
  offsetMs: number | null;
  rttSamples: number[];
  isSyncing: boolean;
};

export type UseClockSyncResult = ClockSync & {
  handlePong: (pong: WirePong) => void;
  reset: () => void;
};

export type UseClockSyncOptions = {
  roomId: string;
  senderId: string;
  sendPing: (ping: WirePing) => boolean;
  connectionOpen: boolean;
};

export function useClockSync({
  roomId,
  senderId,
  sendPing,
  connectionOpen,
}: UseClockSyncOptions): UseClockSyncResult {
  const [offsetMs, setOffsetMs] = useState<number | null>(null);
  const [rttSamples, setRttSamples] = useState<number[]>([]);
  const pendingPings = useRef<Map<number, number>>(new Map()); // clientPingTs -> sentAt
  const offsetSamples = useRef<number[]>([]);

  const isSyncing = offsetMs === null && connectionOpen;

  const reset = useCallback(() => {
    pendingPings.current.clear();
    offsetSamples.current = [];
    setOffsetMs(null);
    setRttSamples([]);
  }, []);

  const handlePong = useCallback((pong: WirePong) => {
    const sentAt = pendingPings.current.get(pong.clientPingTs);
    if (sentAt == null) return; // stale or duplicate

    pendingPings.current.delete(pong.clientPingTs);
    const now = Date.now();
    const rtt = now - sentAt;

    // Estimate offset (server clock vs local clock):
    // assume the server received and sent at roughly its midpoint
    const serverMid = (pong.serverRecvTs + pong.serverSendTs) / 2;
    const clientMid = (sentAt + now) / 2;
    const offset = serverMid - clientMid;

    offsetSamples.current = [...offsetSamples.current, offset].slice(-SAMPLE_TARGET);
    setRttSamples((prev) => [...prev, rtt].slice(-SAMPLE_TARGET));

    if (offsetSamples.current.length >= 1) {
      const sorted = [...offsetSamples.current].sort((a, b) => a - b);
      const median = sorted[Math.floor(sorted.length / 2)]!;
      setOffsetMs(median);
    }
  }, []);

  // Initial handshake: send SAMPLE_TARGET pings spaced PING_INTERVAL_MS apart.
  useEffect(() => {
    if (!connectionOpen) return;
    reset();

    let cancelled = false;
    const timers: number[] = [];

    for (let i = 0; i < SAMPLE_TARGET; i++) {
      const t = window.setTimeout(() => {
        if (cancelled) return;
        const clientPingTs = Date.now();
        pendingPings.current.set(clientPingTs, clientPingTs);
        const ok = sendPing({
          type: "ping",
          roomId,
          senderId,
          clientPingTs,
        });
        if (!ok) pendingPings.current.delete(clientPingTs);

        // Time out stale pings so the map doesn't leak.
        const cleanup = window.setTimeout(() => {
          pendingPings.current.delete(clientPingTs);
        }, PING_TIMEOUT_MS);
        timers.push(cleanup);
      }, i * PING_INTERVAL_MS);
      timers.push(t);
    }

    return () => {
      cancelled = true;
      timers.forEach(clearTimeout);
    };
  }, [connectionOpen, roomId, senderId, sendPing, reset]);

  return { offsetMs, rttSamples, isSyncing, handlePong, reset };
}
