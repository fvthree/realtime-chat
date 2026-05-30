import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useWebSocket, backoffMsForAttempt } from "./useWebSocket";
import type { WireEvent } from "../types";

// ── Backoff schedule (pure function, no mocking needed) ─────────────────────

describe("backoffMsForAttempt", () => {
  it("follows the defined schedule", () => {
    expect(backoffMsForAttempt(0)).toBe(500);
    expect(backoffMsForAttempt(1)).toBe(1000);
    expect(backoffMsForAttempt(2)).toBe(2000);
    expect(backoffMsForAttempt(3)).toBe(4000);
    expect(backoffMsForAttempt(4)).toBe(8000);
    expect(backoffMsForAttempt(5)).toBe(16000);
    expect(backoffMsForAttempt(6)).toBe(30000);
  });

  it("caps at 30000ms regardless of attempt number", () => {
    expect(backoffMsForAttempt(7)).toBe(30000);
    expect(backoffMsForAttempt(100)).toBe(30000);
  });
});

// ── WebSocket state machine ──────────────────────────────────────────────────

interface MockWSInstance {
  url: string;
  readyState: number;
  onopen: ((e: Event) => void) | null;
  onclose: ((e: CloseEvent) => void) | null;
  onerror: ((e: Event) => void) | null;
  onmessage: ((e: MessageEvent) => void) | null;
  sent: string[];
  close: () => void;
  send: (data: string) => void;
  simulateOpen: () => void;
  simulateClose: () => void;
  simulateMessage: (evt: WireEvent) => void;
}

let lastMockWS: MockWSInstance | null = null;

class MockWebSocket implements MockWSInstance {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  readyState = MockWebSocket.CONNECTING;
  onopen: ((e: Event) => void) | null = null;
  onclose: ((e: CloseEvent) => void) | null = null;
  onerror: ((e: Event) => void) | null = null;
  onmessage: ((e: MessageEvent) => void) | null = null;
  sent: string[] = [];

  constructor(public url: string) {
    lastMockWS = this;
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.(new CloseEvent("close"));
  }

  simulateOpen() {
    this.readyState = MockWebSocket.OPEN;
    this.onopen?.(new Event("open"));
  }

  simulateClose() {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.(new CloseEvent("close"));
  }

  simulateMessage(evt: WireEvent) {
    this.onmessage?.(new MessageEvent("message", { data: JSON.stringify(evt) }));
  }
}

beforeEach(() => {
  vi.stubGlobal("WebSocket", MockWebSocket);
  vi.useFakeTimers();
  lastMockWS = null;
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("useWebSocket state machine", () => {
  it("starts disconnected then moves to connecting on mount", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    expect(result.current.state.kind).toBe("connecting");
    expect(lastMockWS?.url).toBe("ws://localhost/ws/chat/test");
  });

  it("transitions to open when WebSocket fires onopen", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    act(() => lastMockWS!.simulateOpen());
    expect(result.current.state.kind).toBe("open");
  });

  it("transitions to reconnecting with delay when connection drops", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    act(() => lastMockWS!.simulateOpen());
    expect(result.current.state.kind).toBe("open");

    act(() => lastMockWS!.simulateClose());
    expect(result.current.state.kind).toBe("reconnecting");
    if (result.current.state.kind === "reconnecting") {
      expect(result.current.state.retryInMs).toBe(500); // first attempt
    }
  });

  it("reconnects after the backoff delay", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    act(() => lastMockWS!.simulateOpen());
    act(() => lastMockWS!.simulateClose());
    expect(result.current.state.kind).toBe("reconnecting");

    act(() => vi.advanceTimersByTime(500));
    expect(result.current.state.kind).toBe("connecting");
  });

  it("delivers parsed messages to onEvent", () => {
    const events: WireEvent[] = [];
    renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: (e) => events.push(e) })
    );
    act(() => lastMockWS!.simulateOpen());
    act(() =>
      lastMockWS!.simulateMessage({
        type: "msg",
        id: "1",
        tempId: null,
        roomId: "test",
        senderId: "alice",
        text: "hi",
        clientSendTs: 100,
        serverRecvTs: 110,
      })
    );
    expect(events).toHaveLength(1);
    expect(events[0]!.type).toBe("msg");
  });
});

describe("useWebSocket.send()", () => {
  it("returns false when not connected", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    // Still in connecting state (no simulateOpen).
    const ok = result.current.send({ type: "ping", roomId: "test", senderId: "u", clientPingTs: 1 });
    expect(ok).toBe(false);
  });

  it("returns true and serializes the event when open", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    act(() => lastMockWS!.simulateOpen());
    const ok = result.current.send({ type: "ping", roomId: "test", senderId: "u", clientPingTs: 1 });
    expect(ok).toBe(true);
    expect(lastMockWS!.sent).toHaveLength(1);
    expect(JSON.parse(lastMockWS!.sent[0]!)).toMatchObject({ type: "ping", clientPingTs: 1 });
  });
});

describe("useWebSocket enabled flag", () => {
  it("stays disconnected and never opens a socket when enabled is false", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {}, enabled: false })
    );
    expect(result.current.state.kind).toBe("disconnected");
    expect(lastMockWS).toBeNull();
  });

  it("transitions to connecting when enabled flips from false to true", () => {
    const { result, rerender } = renderHook(
      ({ enabled }: { enabled: boolean }) =>
        useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {}, enabled }),
      { initialProps: { enabled: false } }
    );
    expect(result.current.state.kind).toBe("disconnected");
    rerender({ enabled: true });
    expect(result.current.state.kind).toBe("connecting");
  });

  it("returns false from send() while in reconnecting state (socket not open)", () => {
    const { result } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    act(() => lastMockWS!.simulateOpen());
    act(() => lastMockWS!.simulateClose());
    expect(result.current.state.kind).toBe("reconnecting");
    const ok = result.current.send({ type: "ping", roomId: "test", senderId: "u", clientPingTs: 1 });
    expect(ok).toBe(false);
  });
});

describe("useWebSocket cleanup", () => {
  it("cancels the reconnect timer and does not open a new socket on unmount", () => {
    const { unmount } = renderHook(() =>
      useWebSocket({ url: "ws://localhost/ws/chat/test", onEvent: () => {} })
    );
    const firstWs = lastMockWS!;
    act(() => firstWs.simulateOpen());
    act(() => firstWs.simulateClose());

    unmount();
    // After unmount, advancing through the backoff delay should NOT create a new socket.
    act(() => vi.advanceTimersByTime(2000));
    expect(lastMockWS).toBe(firstWs); // no new instance created
  });
});
