// Wire types — must match backend ChatEvent sealed hierarchy exactly.
// Backend uses Jackson's @JsonTypeInfo with property "type"; we mirror it here.

export type WireMessage = {
  type: "msg";
  id: string;
  tempId: string | null;
  roomId: string;
  senderId: string;
  text: string;
  clientSendTs: number;
  serverRecvTs: number;
};

export type WirePing = {
  type: "ping";
  roomId: string;
  senderId: string;
  clientPingTs: number;
};

export type WirePong = {
  type: "pong";
  roomId: string;
  clientPingTs: number;
  serverRecvTs: number;
  serverSendTs: number;
};

export type WireTypingStart = {
  type: "typing_start";
  roomId: string;
  senderId: string;
};

export type WireTypingStop = {
  type: "typing_stop";
  roomId: string;
  senderId: string;
};

export type WirePresence = {
  type: "presence";
  roomId: string;
  connected: number;
};

export type WireHello = {
  type: "hello";
  roomId: string;
  senderId: string;
};

export type WireEvent =
  | WireMessage
  | WirePing
  | WirePong
  | WireTypingStart
  | WireTypingStop
  | WirePresence
  | WireHello;

// ─── Local state types ─────────────────────────────────────────────────

export type MessageStatus = "pending" | "acked" | "failed";

export type LocalMessage = {
  id: string; // tempId until acked, then server id
  tempId: string | null;
  senderId: string;
  text: string;
  clientSendTs: number;
  serverRecvTs: number | null;
  status: MessageStatus;
};

export type ConnectionState =
  | { kind: "disconnected" }
  | { kind: "connecting" }
  | { kind: "open" }
  | { kind: "reconnecting"; retryInMs: number; attempt: number };

export type LatencySample = {
  ms: number;
  kind: "self" | "peer";
  at: number;
};
