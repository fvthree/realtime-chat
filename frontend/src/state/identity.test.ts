import { beforeEach, describe, it, expect, vi } from "vitest";
import { loadOrCreateSenderId, getRoomIdFromUrl } from "./identity";

beforeEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe("loadOrCreateSenderId", () => {
  it("creates a new guest id when none exists", () => {
    const id = loadOrCreateSenderId("lobby");
    expect(id).toMatch(/^guest-[0-9a-f]{4}$/);
  });

  it("persists and reloads the same id on subsequent calls", () => {
    const first = loadOrCreateSenderId("lobby");
    const second = loadOrCreateSenderId("lobby");
    expect(second).toBe(first);
  });

  it("scopes the id per room so different rooms get different ids", () => {
    const a = loadOrCreateSenderId("room-a");
    const b = loadOrCreateSenderId("room-b");
    // Both are valid guest ids but stored under separate keys
    expect(a).toMatch(/^guest-/);
    expect(b).toMatch(/^guest-/);
    expect(a).not.toBe(b);
  });

  it("returns a fresh id without throwing when localStorage is unavailable", () => {
    vi.stubGlobal("localStorage", {
      getItem: () => { throw new Error("SecurityError"); },
      setItem: () => { throw new Error("SecurityError"); },
    });
    const id = loadOrCreateSenderId("lobby");
    expect(id).toMatch(/^guest-[0-9a-f]{4}$/);
  });

  it("ignores stored values that do not start with guest-", () => {
    // Simulate a corrupted or future-format value
    localStorage.setItem("realtime-chat:senderId:lobby", "oauth-user-123");
    const id = loadOrCreateSenderId("lobby");
    expect(id).toMatch(/^guest-[0-9a-f]{4}$/);
  });
});

describe("getRoomIdFromUrl", () => {
  it("returns lobby when no room param is present", () => {
    vi.stubGlobal("location", { search: "" });
    expect(getRoomIdFromUrl()).toBe("lobby");
  });

  it("returns the room param when valid", () => {
    vi.stubGlobal("location", { search: "?room=my-room" });
    expect(getRoomIdFromUrl()).toBe("my-room");
  });

  it("lowercases an uppercase room param", () => {
    vi.stubGlobal("location", { search: "?room=MyRoom" });
    expect(getRoomIdFromUrl()).toBe("myroom");
  });

  it("falls back to lobby when room contains invalid characters", () => {
    vi.stubGlobal("location", { search: "?room=bad room!" });
    expect(getRoomIdFromUrl()).toBe("lobby");
  });

  it("falls back to lobby when room exceeds 32 characters", () => {
    vi.stubGlobal("location", { search: "?room=" + "a".repeat(33) });
    expect(getRoomIdFromUrl()).toBe("lobby");
  });
});
