// Stage 1 identity: random guest-XXXX in localStorage so refresh keeps the same
// name. Stage 4 replaces this with the authenticated principal from the OAuth
// session cookie.

const STORAGE_KEY = "realtime-chat:senderId";

function randomGuestId(): string {
  const buf = new Uint8Array(2);
  crypto.getRandomValues(buf);
  const hex = Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
  return `guest-${hex}`;
}

export function loadOrCreateSenderId(): string {
  try {
    const existing = localStorage.getItem(STORAGE_KEY);
    if (existing && existing.startsWith("guest-")) return existing;
  } catch {
    // localStorage may be disabled (private mode, sandboxed iframes) — fall through
  }
  const fresh = randomGuestId();
  try {
    localStorage.setItem(STORAGE_KEY, fresh);
  } catch {
    // ignore — we'll just pick a new id on every load
  }
  return fresh;
}

export function getRoomIdFromUrl(): string {
  const params = new URLSearchParams(window.location.search);
  const room = params.get("room");
  return room && /^[a-z0-9-]{1,32}$/i.test(room) ? room : "lobby";
}
