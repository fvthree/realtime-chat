import { describe, it, expect } from "vitest";
import { chatReducer, initialChatState } from "./chatReducer";
import type { WireMessage } from "../types";

const baseMsg: WireMessage = {
  type: "msg",
  id: "server-1",
  tempId: "tmp-1",
  roomId: "lobby",
  senderId: "guest-aa",
  text: "hi",
  clientSendTs: 1000,
  serverRecvTs: 1005,
};

describe("chatReducer", () => {
  it("queue-send appends a pending message", () => {
    const s = chatReducer(initialChatState, {
      kind: "queue-send",
      tempId: "tmp-1",
      senderId: "guest-aa",
      text: "hi",
      clientSendTs: 1000,
    });
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0]!.status).toBe("pending");
    expect(s.messages[0]!.id).toBe("tmp-1");
  });

  it("ack reconciles in place by tempId for self-sent messages", () => {
    const queued = chatReducer(initialChatState, {
      kind: "queue-send",
      tempId: "tmp-1",
      senderId: "guest-aa",
      text: "hi",
      clientSendTs: 1000,
    });
    const acked = chatReducer(queued, {
      kind: "ack",
      serverMsg: baseMsg,
      selfSenderId: "guest-aa",
    });
    expect(acked.messages).toHaveLength(1);
    expect(acked.messages[0]!.status).toBe("acked");
    expect(acked.messages[0]!.id).toBe("server-1");
    expect(acked.messages[0]!.serverRecvTs).toBe(1005);
  });

  it("ack appends when message is from a different sender", () => {
    const s = chatReducer(initialChatState, {
      kind: "ack",
      serverMsg: baseMsg,
      selfSenderId: "guest-bb",
    });
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0]!.id).toBe("server-1");
  });

  it("ack deduplicates by id when received twice", () => {
    const once = chatReducer(initialChatState, {
      kind: "ack",
      serverMsg: baseMsg,
      selfSenderId: "guest-bb",
    });
    const twice = chatReducer(once, {
      kind: "ack",
      serverMsg: baseMsg,
      selfSenderId: "guest-bb",
    });
    expect(twice.messages).toHaveLength(1);
  });

  it("fail flips status; retry resets to pending", () => {
    const queued = chatReducer(initialChatState, {
      kind: "queue-send",
      tempId: "tmp-1",
      senderId: "guest-aa",
      text: "hi",
      clientSendTs: 1000,
    });
    const failed = chatReducer(queued, { kind: "fail", tempId: "tmp-1" });
    expect(failed.messages[0]!.status).toBe("failed");

    const retried = chatReducer(failed, {
      kind: "retry",
      tempId: "tmp-1",
      clientSendTs: 2000,
    });
    expect(retried.messages[0]!.status).toBe("pending");
    expect(retried.messages[0]!.clientSendTs).toBe(2000);
  });

  it("typing-start ignores self", () => {
    const s = chatReducer(initialChatState, {
      kind: "typing-start",
      senderId: "guest-aa",
      selfSenderId: "guest-aa",
    });
    expect(s.typing.size).toBe(0);
  });

  it("typing-start tracks others; typing-stop clears them", () => {
    const a = chatReducer(initialChatState, {
      kind: "typing-start",
      senderId: "guest-bb",
      selfSenderId: "guest-aa",
    });
    expect(a.typing.has("guest-bb")).toBe(true);
    const b = chatReducer(a, { kind: "typing-stop", senderId: "guest-bb" });
    expect(b.typing.has("guest-bb")).toBe(false);
  });

  it("presence updates the connected count", () => {
    const s = chatReducer(initialChatState, { kind: "presence", connected: 3 });
    expect(s.connected).toBe(3);
  });

  it("presence with 0 resets connected count to zero", () => {
    const withTwo = chatReducer(initialChatState, { kind: "presence", connected: 2 });
    const withZero = chatReducer(withTwo, { kind: "presence", connected: 0 });
    expect(withZero.connected).toBe(0);
  });

  it("reset returns initial state", () => {
    const withMessages = chatReducer(initialChatState, {
      kind: "queue-send",
      tempId: "tmp-x",
      senderId: "fvthree",
      text: "hello",
      clientSendTs: 1000,
    });
    const withTyping = chatReducer(withMessages, {
      kind: "typing-start",
      senderId: "alice",
      selfSenderId: "fvthree",
    });
    const reset = chatReducer(withTyping, { kind: "reset" });
    expect(reset.messages).toHaveLength(0);
    expect(reset.typing.size).toBe(0);
    expect(reset.connected).toBe(0);
  });
});
