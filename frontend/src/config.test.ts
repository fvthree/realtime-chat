import { describe, it, expect } from "vitest";
import { MAX_MESSAGE_TEXT_LENGTH, API_BASE } from "./config";

describe("config constants", () => {
  it("MAX_MESSAGE_TEXT_LENGTH is 10_000 — must match backend ChatWebSocketHandler constant", () => {
    expect(MAX_MESSAGE_TEXT_LENGTH).toBe(10_000);
  });

  it("API_BASE defaults to localhost:8080 when VITE_API_BASE is not set", () => {
    // In the Vitest environment VITE_API_BASE is undefined, so the fallback applies.
    expect(API_BASE).toBe("http://localhost:8080");
  });
});
