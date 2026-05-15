import type { LocalMessage, WireMessage } from "../types";

export type ChatState = {
  messages: LocalMessage[];
  typing: Set<string>; // senderIds currently typing (excluding self)
  connected: number;
};

export const initialChatState: ChatState = {
  messages: [],
  typing: new Set<string>(),
  connected: 0,
};

export type ChatAction =
  | { kind: "queue-send"; tempId: string; senderId: string; text: string; clientSendTs: number }
  | { kind: "ack"; serverMsg: WireMessage; selfSenderId: string }
  | { kind: "fail"; tempId: string }
  | { kind: "retry"; tempId: string; clientSendTs: number }
  | { kind: "typing-start"; senderId: string; selfSenderId: string }
  | { kind: "typing-stop"; senderId: string }
  | { kind: "presence"; connected: number }
  | { kind: "reset" };

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.kind) {
    case "queue-send": {
      const optimistic: LocalMessage = {
        id: action.tempId,
        tempId: action.tempId,
        senderId: action.senderId,
        text: action.text,
        clientSendTs: action.clientSendTs,
        serverRecvTs: null,
        status: "pending",
      };
      return { ...state, messages: [...state.messages, optimistic] };
    }

    case "ack": {
      const { serverMsg, selfSenderId } = action;
      // Reconcile-in-place by tempId (Fork C pattern 1) — preserves React key,
      // no remount, no flicker. If this is someone else's message there will
      // be no matching tempId; we just append.
      const existingIdx = state.messages.findIndex((m) => {
        if (serverMsg.senderId !== selfSenderId) return false;
        if (!serverMsg.tempId) return false;
        return m.tempId === serverMsg.tempId;
      });

      if (existingIdx >= 0) {
        const updated = [...state.messages];
        updated[existingIdx] = {
          ...updated[existingIdx]!,
          id: serverMsg.id,
          serverRecvTs: serverMsg.serverRecvTs,
          status: "acked",
        };
        return { ...state, messages: updated };
      }

      // Message from a different sender (or no tempId match) — append.
      // Deduplicate by id in case the server retransmits.
      if (state.messages.some((m) => m.id === serverMsg.id)) return state;

      const arrived: LocalMessage = {
        id: serverMsg.id,
        tempId: serverMsg.tempId,
        senderId: serverMsg.senderId,
        text: serverMsg.text,
        clientSendTs: serverMsg.clientSendTs,
        serverRecvTs: serverMsg.serverRecvTs,
        status: "acked",
      };
      return { ...state, messages: [...state.messages, arrived] };
    }

    case "fail": {
      const idx = state.messages.findIndex((m) => m.id === action.tempId);
      if (idx < 0) return state;
      const updated = [...state.messages];
      updated[idx] = { ...updated[idx]!, status: "failed" };
      return { ...state, messages: updated };
    }

    case "retry": {
      const idx = state.messages.findIndex((m) => m.id === action.tempId);
      if (idx < 0) return state;
      const updated = [...state.messages];
      updated[idx] = {
        ...updated[idx]!,
        status: "pending",
        clientSendTs: action.clientSendTs,
      };
      return { ...state, messages: updated };
    }

    case "typing-start": {
      if (action.senderId === action.selfSenderId) return state;
      const typing = new Set(state.typing);
      typing.add(action.senderId);
      return { ...state, typing };
    }

    case "typing-stop": {
      if (!state.typing.has(action.senderId)) return state;
      const typing = new Set(state.typing);
      typing.delete(action.senderId);
      return { ...state, typing };
    }

    case "presence":
      return { ...state, connected: action.connected };

    case "reset":
      return initialChatState;
  }
}
