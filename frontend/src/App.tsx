import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";
import { useWebSocket } from "./hooks/useWebSocket";
import { useClockSync } from "./hooks/useClockSync";
import { chatReducer, initialChatState } from "./state/chatReducer";
import { fetchCurrentUser, getRoomIdFromUrl } from "./state/identity";
import type { CurrentUser } from "./state/identity";
import type {
  LatencySample,
  WireEvent,
  WireHello,
  WireMessage,
  WirePing,
  WirePong,
  WireTypingStart,
  WireTypingStop,
} from "./types";

import { TopBar } from "./components/TopBar";
import { ConnectionStrip } from "./components/ConnectionStrip";
import { MessageList } from "./components/MessageList";
import { Composer } from "./components/Composer";
import { LatencyHUD } from "./components/LatencyHUD";
import { API_BASE } from "./config";

function makeTempId(): string {
  return `tmp-${Math.random().toString(36).slice(2, 10)}`;
}

const MAX_SAMPLES = 200;

export function App() {
  const roomId = useMemo(getRoomIdFromUrl, []);
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [authChecked, setAuthChecked] = useState(false);
  const [authError, setAuthError] = useState(false);

  // Auth gate: redirect to GitHub OAuth only on 401. Network/server errors show a retry
  // prompt instead of bouncing an authenticated user through a full OAuth round-trip.
  useEffect(() => {
    fetchCurrentUser()
      .then((me) => {
        if (me) {
          setUser(me);
        } else {
          window.location.href = `${API_BASE}/oauth2/authorization/github`;
        }
      })
      .catch((err: unknown) => { console.error("Auth check failed:", err); setAuthError(true); })
      .finally(() => setAuthChecked(true));
  }, []);

  const senderId = user?.login ?? "";
  const url = useMemo(() => {
    const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${window.location.host}/ws/chat/${roomId}`;
  }, [roomId]);

  const [chat, dispatch] = useReducer(chatReducer, initialChatState);
  const [samples, setSamples] = useState<LatencySample[]>([]);

  const senderIdRef = useRef(senderId);
  useEffect(() => { senderIdRef.current = senderId; }, [senderId]);

  const offsetRef = useRef<number | null>(null);
  const handlePongRef = useRef<(pong: WirePong) => void>(() => {});

  const pushSample = useCallback((s: LatencySample) => {
    setSamples((prev) => {
      const next = [...prev, s];
      return next.length > MAX_SAMPLES ? next.slice(-MAX_SAMPLES) : next;
    });
  }, []);

  const handleEvent = useCallback(
    (event: WireEvent) => {
      switch (event.type) {
        case "msg": {
          dispatch({ kind: "ack", serverMsg: event, selfSenderId: senderIdRef.current });

          if (event.senderId === senderIdRef.current && event.tempId !== null) {
            const rtt = Date.now() - event.clientSendTs;
            if (rtt >= 0 && rtt < 60000) {
              pushSample({ ms: rtt, kind: "self", at: Date.now() });
            }
          } else if (offsetRef.current !== null) {
            const serverRecvLocal = event.serverRecvTs - offsetRef.current;
            const peerMs = Date.now() - serverRecvLocal;
            if (peerMs >= 0 && peerMs < 60000) {
              pushSample({ ms: peerMs, kind: "peer", at: Date.now() });
            }
          }
          break;
        }
        case "pong":
          handlePongRef.current(event);
          break;
        case "typing_start":
          dispatch({
            kind: "typing-start",
            senderId: event.senderId,
            selfSenderId: senderIdRef.current,
          });
          break;
        case "typing_stop":
          dispatch({ kind: "typing-stop", senderId: event.senderId });
          break;
        case "presence":
          dispatch({ kind: "presence", connected: event.connected });
          break;
        case "hello":
        case "ping":
          break;
      }
    },
    [pushSample]
  );

  const { state, send } = useWebSocket({ url, onEvent: handleEvent, enabled: authChecked && !authError && !!senderId });

  const sendPing = useCallback((ping: WirePing) => send(ping), [send]);

  const clock = useClockSync({
    roomId,
    senderId,
    sendPing,
    connectionOpen: state.kind === "open",
  });

  useEffect(() => { offsetRef.current = clock.offsetMs; }, [clock.offsetMs]);
  useEffect(() => { handlePongRef.current = clock.handlePong; }, [clock.handlePong]);

  useEffect(() => {
    if (state.kind === "open" && senderId) {
      const hello: WireHello = { type: "hello", roomId, senderId };
      send(hello);
    }
  }, [state.kind, roomId, senderId, send]);

  const doSend = useCallback(
    (text: string) => {
      const tempId = makeTempId();
      const clientSendTs = Date.now();
      dispatch({ kind: "queue-send", tempId, senderId, text, clientSendTs });
      const wire: WireMessage = {
        type: "msg",
        id: "",
        tempId,
        roomId,
        senderId,
        text,
        clientSendTs,
        serverRecvTs: 0,
      };
      const ok = send(wire);
      if (!ok) dispatch({ kind: "fail", tempId });
    },
    [roomId, senderId, send]
  );

  const doRetry = useCallback(
    (tempId: string) => {
      const msg = chat.messages.find((m) => m.tempId === tempId);
      if (!msg) return;
      const clientSendTs = Date.now();
      dispatch({ kind: "retry", tempId, clientSendTs });
      const wire: WireMessage = {
        type: "msg",
        id: "",
        tempId,
        roomId,
        senderId,
        text: msg.text,
        clientSendTs,
        serverRecvTs: 0,
      };
      const ok = send(wire);
      if (!ok) dispatch({ kind: "fail", tempId });
    },
    [chat.messages, roomId, senderId, send]
  );

  const doTypingStart = useCallback(() => {
    const evt: WireTypingStart = { type: "typing_start", roomId, senderId };
    send(evt);
  }, [roomId, senderId, send]);

  const doTypingStop = useCallback(() => {
    const evt: WireTypingStop = { type: "typing_stop", roomId, senderId };
    send(evt);
  }, [roomId, senderId, send]);

  const compactHud = useMemo(() => {
    const selfP50 = (() => {
      const subset = samples.filter((s) => s.kind === "self").map((s) => s.ms);
      if (subset.length === 0) return null;
      const sorted = [...subset].sort((a, b) => a - b);
      return Math.round(sorted[Math.floor(sorted.length / 2)]!);
    })();
    return selfP50 == null ? null : `${selfP50}ms p50`;
  }, [samples]);

  if (!authChecked) {
    return <div className="app" style={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100vh" }}>Signing in…</div>;
  }
  if (authError) {
    return <div className="app" style={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100vh" }}>Unable to reach the server. Please refresh.</div>;
  }

  return (
    <div className="app">
      <TopBar
        roomId={roomId}
        connected={chat.connected}
        state={state}
        hudCompact={compactHud}
      />
      <ConnectionStrip state={state} />
      <MessageList
        messages={chat.messages}
        typing={chat.typing}
        onRetry={doRetry}
      />
      <Composer
        roomId={roomId}
        disabled={state.kind !== "open"}
        onSend={doSend}
        onTypingStart={doTypingStart}
        onTypingStop={doTypingStop}
      />
      <LatencyHUD samples={samples} syncing={clock.isSyncing} />
    </div>
  );
}
