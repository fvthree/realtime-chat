import { useCallback, useEffect, useRef, useState } from "react";
import type { WirePing, WirePong } from "../types";

/**
 * Stage 3 NTP-style clock sync over a sliding window.
 *
 * Four-timestamp offset estimate (NTP):
 *   offset = ((serverRecvTs - clientPingTs) + (serverSendTs - clientRecvTs)) / 2
 *          = serverMid - clientMid
 *
 * Samples accumulate in a sliding window (WINDOW_SIZE). σ-outlier rejection
 * guards against sudden network spikes inflating the estimate. Median of the
 * surviving samples is the published offsetMs.
 *
 * A periodic resync (RESYNC_INTERVAL_MS) keeps the window fresh over long sessions.
 */

const SAMPLE_TARGET = 5;
const PING_INTERVAL_MS = 60;
const PING_TIMEOUT_MS = 3000;
const RESYNC_INTERVAL_MS = 30_000;
const WINDOW_SIZE = 20;
const OUTLIER_SIGMA = 2;

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

function computeOffsetFromSamples(samples: number[]): number | null {
  if (samples.length === 0) return null;

  let filtered = samples;
  if (samples.length >= 5) {
    const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
    const variance =
      samples.reduce((sum, v) => sum + (v - mean) ** 2, 0) / samples.length;
    const sd = Math.sqrt(variance);
    const within = samples.filter((v) => Math.abs(v - mean) <= OUTLIER_SIGMA * sd);
    if (within.length > 0) filtered = within;
  }

  const sorted = [...filtered].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)]!;
}

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

    const serverMid = (pong.serverRecvTs + pong.serverSendTs) / 2;
    const clientMid = (sentAt + now) / 2;
    const offset = serverMid - clientMid;

    const next = [...offsetSamples.current, offset].slice(-WINDOW_SIZE);
    offsetSamples.current = next;
    setRttSamples((prev) => [...prev, rtt].slice(-WINDOW_SIZE));
    setOffsetMs(computeOffsetFromSamples(next));
  }, []);

  // Initial handshake: SAMPLE_TARGET pings spaced PING_INTERVAL_MS apart.
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
        const ok = sendPing({ type: "ping", roomId, senderId, clientPingTs });
        if (!ok) pendingPings.current.delete(clientPingTs);

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

  // Periodic resync: one ping every RESYNC_INTERVAL_MS to keep the window fresh.
  useEffect(() => {
    if (!connectionOpen) return;

    let cleanupTimer: number | undefined;

    const id = window.setInterval(() => {
      const clientPingTs = Date.now();
      pendingPings.current.set(clientPingTs, clientPingTs);
      const ok = sendPing({ type: "ping", roomId, senderId, clientPingTs });
      if (!ok) pendingPings.current.delete(clientPingTs);

      window.clearTimeout(cleanupTimer);
      cleanupTimer = window.setTimeout(() => {
        pendingPings.current.delete(clientPingTs);
      }, PING_TIMEOUT_MS);
    }, RESYNC_INTERVAL_MS);

    return () => {
      clearInterval(id);
      clearTimeout(cleanupTimer);
    };
  }, [connectionOpen, roomId, senderId, sendPing]);

  return { offsetMs, rttSamples, isSyncing, handlePong, reset };
}
