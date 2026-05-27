// Stage 4 identity: GitHub login derived from the authenticated principal via /api/me.
// Guest identity (Stage 1-3) is replaced by OAuth2-derived identity.

export interface CurrentUser {
  login: string;
  avatarUrl: string;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export async function fetchCurrentUser(): Promise<CurrentUser | null> {
  try {
    const res = await fetch(`${API_BASE}/api/me`, { credentials: "include" });
    if (!res.ok) return null;
    const data = await res.json();
    return { login: data.login, avatarUrl: data.avatarUrl ?? "" };
  } catch {
    return null;
  }
}

export function getRoomIdFromUrl(): string {
  const params = new URLSearchParams(window.location.search);
  const room = params.get("room");
  return room && /^[a-z0-9-]{1,32}$/.test(room.toLowerCase()) ? room.toLowerCase() : "lobby";
}
