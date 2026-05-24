// Stage 2 identity: random guest-XXXX persisted in localStorage so refresh keeps
// the same name. Key is scoped by roomId so two tabs on different rooms get different
// senderIds (fixes: messages from ?room=foo appearing on the "self" side in ?room=bar).
// Stage 4 replaces this with the authenticated principal from OAuth.

function storageKey(roomId: string): string {
  return `realtime-chat:senderId:${roomId}`;
}

function randomGuestId(): string {
  const buf = new Uint8Array(2);
  crypto.getRandomValues(buf);
  const hex = Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
  return `guest-${hex}`;
}

export function loadOrCreateSenderId(roomId: string): string {
  const key = storageKey(roomId);
  try {
    const existing = localStorage.getItem(key);
    if (existing && existing.startsWith("guest-")) return existing;
  } catch {
    // localStorage may be disabled (private mode, sandboxed iframes) — fall through
  }
  const fresh = randomGuestId();
  try {
    localStorage.setItem(key, fresh);
  } catch {
    // ignore — we'll pick a new id on every load
  }
  return fresh;
}

export function getRoomIdFromUrl(): string {
  const params = new URLSearchParams(window.location.search);
  const room = params.get("room");
  return room && /^[a-z0-9-]{1,32}$/.test(room.toLowerCase()) ? room.toLowerCase() : "lobby";
}
