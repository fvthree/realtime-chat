import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useClockSync } from "./useClockSync";
import type { WirePing, WirePong } from "../types";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

function makePong(clientPingTs: number, serverRecvTs: number, serverSendTs: number): WirePong {
  return { type: "pong", roomId: "test", clientPingTs, serverRecvTs, serverSendTs };
}

// Advance time and deliver one pong per captured ping. Returns sample count delta.
function deliverPongs(capturedPings: number[], result: ReturnType<typeof useClockSync>) {
  for (const ts of capturedPings) {
    act(() => {
      vi.setSystemTime(ts + 20);
      result.handlePong(makePong(ts, ts + 10, ts + 12));
    });
  }
}

// ── Offset math ──────────────────────────────────────────────────────────────

describe("handlePong offset math", () => {
  it("computes offset = serverMid - clientMid", () => {
    // Use vi.fn() for a stable reference; inline functions change identity each render,
    // which would re-trigger the ping useEffect (and call reset()) on every state update.
    const capturedPings: number[] = [];
    const sendPing = vi.fn((p: WirePing) => {
      capturedPings.push(p.clientPingTs);
      return true;
    });

    const { result } = renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: true })
    );

    // Fire the first ping timer (delay = 0ms).
    act(() => vi.advanceTimersByTime(0));
    expect(capturedPings).toHaveLength(1);

    const clientPingTs = capturedPings[0]!;
    const serverRecvTs = clientPingTs + 10;
    const serverSendTs = clientPingTs + 12;

    // Receive pong 14ms after send (RTT = 14ms).
    act(() => {
      vi.setSystemTime(clientPingTs + 14);
      result.current.handlePong(makePong(clientPingTs, serverRecvTs, serverSendTs));
    });

    // serverMid = (clientPingTs+10 + clientPingTs+12) / 2 = clientPingTs + 11
    // clientMid = (clientPingTs + clientPingTs+14) / 2 = clientPingTs + 7
    // offset = 11 - 7 = 4ms
    expect(result.current.offsetMs).toBe(4);
  });

  it("ignores pong for unknown clientPingTs (stale or duplicate)", () => {
    const sendPing = vi.fn(() => true);
    const { result } = renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: true })
    );

    act(() =>
      result.current.handlePong(makePong(999999, 1000009, 1000011))
    );
    expect(result.current.offsetMs).toBeNull();
  });

  it("uses median of multiple samples to reject outliers", () => {
    const capturedPings: number[] = [];
    const sendPing = vi.fn((p: WirePing) => {
      capturedPings.push(p.clientPingTs);
      return true;
    });

    const { result } = renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: true })
    );

    // Fire all 5 pings (spaced 60ms apart).
    act(() => vi.advanceTimersByTime(5 * 60));
    expect(capturedPings).toHaveLength(5);

    // Deliver pongs with offsets [10, 10, 10, 10, 100]; median should be 10.
    const offsets = [10, 10, 10, 10, 100];
    for (let i = 0; i < 5; i++) {
      const ts = capturedPings[i]!;
      // We want offset = offsets[i]. With rtt=20, clientMid = ts + 10.
      // serverMid = clientMid + offsets[i] = ts + 10 + offsets[i].
      const serverMid = ts + 10 + offsets[i]!;
      const serverRecvTs = serverMid - 1;
      const serverSendTs = serverMid + 1;
      act(() => {
        vi.setSystemTime(ts + 20);
        result.current.handlePong(makePong(ts, serverRecvTs, serverSendTs));
      });
    }

    expect(result.current.offsetMs).toBe(10);
  });
});

// ── Periodic resync ───────────────────────────────────────────────────────────

describe("periodic resync", () => {
  it("sends a fresh ping every 30 s to keep the window alive", () => {
    const capturedPings: number[] = [];
    const sendPing = vi.fn((p: WirePing) => {
      capturedPings.push(p.clientPingTs);
      return true;
    });

    renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: true })
    );

    // Initial burst: 5 pings at t=0,60,120,180,240ms.
    act(() => vi.advanceTimersByTime(5 * 60));
    const afterBurst = capturedPings.length;
    expect(afterBurst).toBe(5);

    // Advance 30 s — periodic resync should fire one more ping.
    act(() => vi.advanceTimersByTime(30_000));
    expect(capturedPings.length).toBe(afterBurst + 1);

    // Another 30 s — one more.
    act(() => vi.advanceTimersByTime(30_000));
    expect(capturedPings.length).toBe(afterBurst + 2);
  });

  it("stops sending resync pings when connection closes", () => {
    const capturedPings: number[] = [];
    const sendPing = vi.fn((p: WirePing) => {
      capturedPings.push(p.clientPingTs);
      return true;
    });

    const { rerender } = renderHook(
      ({ open }: { open: boolean }) =>
        useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: open }),
      { initialProps: { open: true } }
    );

    act(() => vi.advanceTimersByTime(5 * 60));
    const afterBurst = capturedPings.length;

    // Close the connection — interval should be cleared.
    rerender({ open: false });

    act(() => vi.advanceTimersByTime(30_000));
    expect(capturedPings.length).toBe(afterBurst); // no new pings
  });
});

// ── Sliding window ────────────────────────────────────────────────────────────

describe("sliding window", () => {
  it("caps rttSamples at 20 after more than 20 pongs arrive", () => {
    const capturedPings: number[] = [];
    const sendPing = vi.fn((p: WirePing) => {
      capturedPings.push(p.clientPingTs);
      return true;
    });

    const { result } = renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: true })
    );

    // Initial burst: 5 pings.
    act(() => vi.advanceTimersByTime(5 * 60));
    deliverPongs(capturedPings.slice(), result.current);
    expect(result.current.rttSamples).toHaveLength(5);

    // Trigger 20 periodic resyncs (30s each), delivering each pong.
    for (let i = 0; i < 20; i++) {
      const before = capturedPings.length;
      act(() => vi.advanceTimersByTime(30_000));
      const newPings = capturedPings.slice(before);
      deliverPongs(newPings, result.current);
    }

    // 5 burst + 20 periodic = 25 total received; window caps at 20.
    expect(result.current.rttSamples.length).toBeLessThanOrEqual(20);
  });
});

// ── isSyncing flag ────────────────────────────────────────────────────────────

describe("isSyncing flag", () => {
  it("is true while connection is open and no offset yet", () => {
    const sendPing = vi.fn(() => true);
    const { result } = renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: true })
    );
    expect(result.current.isSyncing).toBe(true);
  });

  it("is false when connection is closed", () => {
    const sendPing = vi.fn(() => true);
    const { result } = renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: false })
    );
    expect(result.current.isSyncing).toBe(false);
  });
});

// ── reset ─────────────────────────────────────────────────────────────────────

describe("reset", () => {
  it("clears offset and rttSamples", () => {
    const capturedPings: number[] = [];
    const sendPing = vi.fn((p: WirePing) => {
      capturedPings.push(p.clientPingTs);
      return true;
    });

    const { result } = renderHook(() =>
      useClockSync({ roomId: "test", senderId: "user", sendPing, connectionOpen: true })
    );

    act(() => vi.advanceTimersByTime(0));
    const ts = capturedPings[0]!;
    act(() => {
      vi.setSystemTime(ts + 20);
      result.current.handlePong(makePong(ts, ts + 10, ts + 12));
    });
    expect(result.current.offsetMs).not.toBeNull();

    act(() => result.current.reset());
    expect(result.current.offsetMs).toBeNull();
    expect(result.current.rttSamples).toHaveLength(0);
  });
});
