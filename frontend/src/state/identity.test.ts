import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fetchCurrentUser, getRoomIdFromUrl } from "./identity";

// ── fetchCurrentUser ──────────────────────────────────────────────────────────

describe("fetchCurrentUser", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns {login, avatarUrl} on 200 response", async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      json: async () => ({ login: "fvthree", avatarUrl: "https://avatars.github.com/u/1" }),
    } as Response);

    const user = await fetchCurrentUser();
    expect(user).toEqual({ login: "fvthree", avatarUrl: "https://avatars.github.com/u/1" });
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/me"),
      expect.objectContaining({ credentials: "include" })
    );
  });

  it("returns null on 401 response", async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 401 } as Response);
    const user = await fetchCurrentUser();
    expect(user).toBeNull();
  });

  it("throws on network error (so callers can avoid spurious OAuth redirects)", async () => {
    vi.mocked(fetch).mockRejectedValue(new Error("Network error"));
    await expect(fetchCurrentUser()).rejects.toThrow("Network error");
  });

  it("throws on non-401 HTTP error (e.g. 500 backend failure)", async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 500 } as Response);
    await expect(fetchCurrentUser()).rejects.toThrow("/api/me returned 500");
  });

  it("returns null when avatarUrl is missing from response", async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      json: async () => ({ login: "fvthree" }), // no avatarUrl
    } as Response);
    const user = await fetchCurrentUser();
    expect(user).toEqual({ login: "fvthree", avatarUrl: "" });
  });
});

// ── getRoomIdFromUrl ──────────────────────────────────────────────────────────

describe("getRoomIdFromUrl", () => {
  afterEach(() => vi.unstubAllGlobals());

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
