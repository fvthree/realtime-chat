import { API_BASE } from "../config";

// Stage 4 identity: GitHub login derived from the authenticated principal via /api/me.
// Guest identity (Stage 1-3) is replaced by OAuth2-derived identity.

export interface CurrentUser {
  login: string;
  avatarUrl: string;
}

// Returns null only on HTTP 401 (unauthenticated). Throws on network errors or
// non-401 HTTP failures so callers can distinguish "not logged in" from "can't reach server".
export async function fetchCurrentUser(): Promise<CurrentUser | null> {
  const res = await fetch(`${API_BASE}/api/me`, { credentials: "include" });
  if (res.status === 401) return null;
  if (!res.ok) throw new Error(`/api/me returned ${res.status}`);
  const data = await res.json();
  return { login: data.login, avatarUrl: data.avatarUrl ?? "" };
}

export function getRoomIdFromUrl(): string {
  const params = new URLSearchParams(window.location.search);
  const room = params.get("room");
  return room && /^[a-z0-9-]{1,32}$/.test(room.toLowerCase()) ? room.toLowerCase() : "lobby";
}
