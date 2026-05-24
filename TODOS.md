# TODOs

Captured during /plan-eng-review and /plan-design-review for Stage 1, deferred to later stages on purpose.

## Stage 2 — Persistence
- [x] **Empty-room eviction** — subscriber-refcount based: `RoomRegistry.evictIfEmpty` called in `doFinally` when count hits 0. Uses `ConcurrentHashMap.compute()` to atomically check subscriber count and remove, preventing eviction of a room that already has a new joiner.
- [ ] **Message ordering under fan-out** — once Stage 5 multi-instance arrives, decide between client-side sort by `serverRecvTs` vs server monotonic id from Postgres.
- [x] **senderId is not room-scoped in localStorage** — key is now `realtime-chat:senderId:{roomId}`, scoped per room.

## Stage 3 — Honest HUD
- [x] **Full NTP-style clock sync** — sliding-window NTP (WINDOW_SIZE=20, σ-outlier rejection, 30s periodic resync) in `useClockSync.ts`. Median of surviving samples published as `offsetMs`.

## Before Stage 5 deploy
- [ ] **WebSocket protocol-level keepalive** — application-level clock-sync pings are JSON frames, not RFC 6455 protocol-level Ping/Pong frames. Fly.io's load balancer kills idle WebSocket connections at ~60s. Before Stage 5, configure a Netty-level WebSocket ping interval (3-line Spring bean or `application.yml` property). Without this, idle connections silently die behind the proxy.

## Stage 4 — Auth
- [ ] **WebSocket auth context propagation** — Spring Security reactive `SecurityWebFilterChain` does NOT automatically populate the WebSocket session's principal. Need to wire `ReactorContextWebFilter` + read `session.getHandshakeInfo().getPrincipal()` inside the handler. Common footgun.
- [ ] **Per-IP + per-user rate limit** on the WebSocket inbound stream.

## Stage 7 — Mobile / polish
- [ ] **iOS Safari aggressive socket closure on tab background** — backgrounded > ~30s kills the WS. Plan: visibilitychange listener triggers immediate reconnect on foreground.
- [ ] **Light theme toggle** — system-pref detect + manual override stored in localStorage.
- [ ] **Failed-message browser notification** — sound + page-title flash when a message fails while the tab is backgrounded.
- [ ] **Animation choreography** — message enter/exit, typing-strip fade. Subtle, ≤120ms.

## Design system
- [ ] **Run /design-consultation** after Stage 2 ships, to formalize the de-facto micro-system in `frontend/DESIGN.md` (tokens, type stack, color tiers, HUD spec, a11y baseline) into a complete design system that covers auth screens, room list, mobile breakpoints.

## Open product questions
- [ ] **End-to-end encryption** — out of scope or Stage 8? Decide explicitly before Stage 4 auth lands.
- [ ] **Anonymous-only or accounts?** — Stage 4 picks one; Stage 1 ships with random guest names persisted in localStorage.

## Testing gaps to close before /ship
- [x] **Two-tab E2E with HUD assertion** — Playwright two-context test verifying message arrives in tab B with HUD value < 100ms on localhost. `e2e/chat.spec.ts` added in v0.2.0.0.
- [ ] **Reconnect-storm regression test** — E2E test written in `e2e/chat.spec.ts` but skipped in CI (`test.skip(!!process.env.CI)`). Needs a backend process-management fixture before it can run in CI.

## Code quality deferred from v0.2.0.0 (P2 — fix before any deployment)
- [ ] **application.yml hardcoded credentials** — `username: postgres` / `password: postgres` committed plaintext. Change to `${DB_USERNAME:postgres}` / `${DB_PASSWORD:postgres}` env-var pattern before Stage 4 or any non-localhost deployment. Flagged in pre-landing review.
- [ ] **No server-side message text length cap** — `ChatWebSocketHandler.java:140` only guards blank text; Netty allows up to 64 KB frames which persist unchecked to the `TEXT` column. Add `m.text().length() > 10_000` guard alongside the blank check; mirror with `maxLength={10000}` on the `<input>` in Composer.tsx.
- [ ] **Room ID max-length mismatch** — `identity.ts:37` enforces `/^[a-z0-9-]{1,32}$/` (32 chars) while `ChatWebSocketHandler.java:206` allows up to 64 chars. Standardize on 64 in the frontend regex, or on 32 in the backend constant. Choose one before Stage 5 multi-instance work.

## Test gaps deferred from v0.2.0.0 (P1 — close before Stage 4)
- [ ] **chatReducer: presence + reset action coverage** — `chatReducer.test.ts` has no tests for the `presence` (updates `chat.connected`) or `reset` actions. Add at least one test each; the file is otherwise well-covered.
- [ ] **Send-when-disconnected → "failed" state test** — App.tsx dispatches `{kind:"fail",tempId}` when `useWebSocket.send()` returns false, but there is no integration-level test that opens a socket, closes it, sends a message, and asserts the message enters `status:"failed"` state. Wire-level gap; the unit tests in `useWebSocket.test.ts` cover the `send()=false` path in isolation.
- [ ] **Retry-failed-message integration test** — `doRetry` in App.tsx re-dispatches and re-sends, but no test exercises the full round-trip: send → fail → retry → ack. A `WebTestClient` integration test or a Vitest mock-WebSocket test would close this.
