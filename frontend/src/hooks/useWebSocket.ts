import { useEffect, useRef, useState, useCallback } from "react";
import type { ConnectionState, WireEvent } from "../types";

/**
 * State machine:
 *
 *   DISCONNECTED ──open──► CONNECTING ──open──► OPEN
 *                              │                  │
 *                              │ error/close      │ close
 *                              ▼                  ▼
 *                         RECONNECTING ◄──── (schedule reconnect)
 *                              │
 *                              │ backoff: 500 → 1000 → 2000 → 4000 → … cap 30000
 *                              ▼
 *                         CONNECTING …
 */

const BACKOFF_MS = [500, 1000, 2000, 4000, 8000, 16000, 30000];

export type UseWebSocketOptions = {
  url: string;
  onEvent: (event: WireEvent) => void;
  enabled?: boolean;
};

export type UseWebSocketResult = {
  state: ConnectionState;
  send: (event: WireEvent) => boolean;
};

export function useWebSocket({ url, onEvent, enabled = true }: UseWebSocketOptions): UseWebSocketResult {
  const [state, setState] = useState<ConnectionState>({ kind: "disconnected" });
  const wsRef = useRef<WebSocket | null>(null);
  const attemptRef = useRef(0);
  const closedByEffectRef = useRef(false);
  const reconnectTimerRef = useRef<number | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  const connect = useCallback(() => {
    setState({ kind: "connecting" });
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      attemptRef.current = 0;
      setState({ kind: "open" });
    };

    ws.onmessage = (e) => {
      try {
        const evt = JSON.parse(e.data as string) as WireEvent;
        onEventRef.current(evt);
      } catch {
        // drop malformed; backend should never send these
      }
    };

    const handleDrop = () => {
      if (closedByEffectRef.current) return;
      const attempt = attemptRef.current;
      const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]!;
      attemptRef.current = attempt + 1;
      setState({ kind: "reconnecting", retryInMs: delay, attempt: attempt + 1 });
      reconnectTimerRef.current = window.setTimeout(() => {
        if (!closedByEffectRef.current) connect();
      }, delay);
    };

    ws.onerror = handleDrop;
    ws.onclose = handleDrop;
  }, [url]);

  useEffect(() => {
    if (!enabled) return;
    closedByEffectRef.current = false;
    connect();
    return () => {
      closedByEffectRef.current = true;
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      if (wsRef.current) {
        wsRef.current.onopen = null;
        wsRef.current.onclose = null;
        wsRef.current.onerror = null;
        wsRef.current.onmessage = null;
        wsRef.current.close();
        wsRef.current = null;
      }
      setState({ kind: "disconnected" });
    };
  }, [connect, enabled]);

  const send = useCallback((event: WireEvent): boolean => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    try {
      ws.send(JSON.stringify(event));
      return true;
    } catch {
      return false;
    }
  }, []);

  return { state, send };
}

// Exported for testing the backoff schedule deterministically.
export function backoffMsForAttempt(attempt: number): number {
  return BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]!;
}
