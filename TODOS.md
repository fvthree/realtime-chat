# TODOs

Captured during /plan-eng-review and /plan-design-review for Stage 1, deferred to later stages on purpose.

## Stage 2 — Persistence
- [x] **Empty-room eviction** — subscriber-refcount based: `RoomRegistry.evictIfEmpty` called in `doFinally` when count hits 0. Uses `ConcurrentHashMap.compute()` to atomically check subscriber count and remove, preventing eviction of a room that already has a new joiner.
- [ ] **Message ordering under fan-out** — once Stage 5 multi-instance arrives, decide between client-side sort by `serverRecvTs` vs server monotonic id from Postgres.
- [x] **senderId is not room-scoped in localStorage** — key is now `realtime-chat:senderId:{roomId}`, scoped per room.

## Stage 3 — Honest HUD
- [x] **Full NTP-style clock sync** — sliding-window NTP (WINDOW_SIZE=20, σ-outlier rejection, 30s periodic resync) in `useClockSync.ts`. Median of surviving samples published as `offsetMs`.

## Before Stage 4 (auth)
- [ ] **DB migration tooling** — `schema.sql` with `CREATE TABLE IF NOT EXISTS` handles initial creation but silently no-ops on column additions. Stage 4 will need an `auth_id` or `sender_principal` column. Add Flyway (`org.flywaydb:flyway-database-postgresql`) or Liquibase before Stage 4 starts; rename `schema.sql` to `V1__init.sql`. Without this, schema changes require manual `ALTER TABLE` and production deploys are fragile.
- [ ] **`pendingPings` key collision under Firefox `privacy.resistFingerprinting`** — `useClockSync.ts` uses `clientPingTs` (ms-resolution `Date.now()`) as the correlation key for the pending pings map. Firefox with `privacy.resistFingerprinting` clamps `Date.now()` to 100ms resolution; two burst pings within the same 100ms window overwrite each other's `sentAt`, corrupting the RTT sample. Fix: use a separate monotonic `pingId` counter as the map key; pass it alongside `clientPingTs` in the wire protocol. Low priority for localhost demo; relevant before Stage 7 mobile/PWA where Firefox mobile users may have this enabled.

## Before Stage 5 deploy
- [ ] **WebSocket protocol-level keepalive** — application-level clock-sync pings are JSON frames, not RFC 6455 protocol-level Ping/Pong frames. Fly.io's load balancer kills idle WebSocket connections at ~60s. Before Stage 5, configure a Netty-level WebSocket ping interval (3-line Spring bean or `application.yml` property). Without this, idle connections silently die behind the proxy.

## Stage 4 — Auth
- [x] **WebSocket auth context propagation** — `session.getHandshakeInfo().getPrincipal()` wired in `ChatWebSocketHandler.handle()`; `switchIfEmpty` guard closes unauthenticated sessions with NOT_ACCEPTABLE.
- [x] **Per-user rate limit** on the WebSocket inbound stream — Guava RateLimiter per principal in `RateLimiterService`; per-IP deferred to Stage 5.

## Stage 7 — Mobile / polish
- [x] **HUD overlaps composer on all viewports** — `position: fixed; bottom: 24px` placed the HUD inside the composer region. Fixed: `bottom: 80px` globally; HUD hidden below 640px via `@media` (compact metric still shows in top bar). Fixed by /qa on kolkata-v1, 2026-05-26.
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
- [x] **TypingStop senderId impersonation** — fixed: TypingStop handler now uses the server-stored senderId from `typingBySession` and ignores the client-supplied value entirely. A client can no longer suppress another user's typing indicator by sending `{"type":"typing_stop","senderId":"victimUser"}`. (kolkata-v1, 2026-05-27)
- [x] **Fire-and-forget save() has no timeout** — `.timeout(Duration.ofSeconds(5))` added before `.subscribe()` in `ChatWebSocketHandler.java`.
- [x] **application.yml hardcoded credentials** — DB credentials already use `${DB_USERNAME:postgres}` / `${DB_PASSWORD:postgres}`; GitHub OAuth uses `${GITHUB_CLIENT_ID}` / `${GITHUB_CLIENT_SECRET}` in the committed file.
- [x] **No server-side message text length cap** — `m.text().length() > 10_000` guard added in `ChatWebSocketHandler.java`; `maxLength={10_000}` added to `Composer.tsx` input.
- [x] **Room ID max-length mismatch** — standardized to 32 chars in `ChatWebSocketHandler.extractRoomId()` to match `identity.ts`.

## Test gaps deferred from v0.2.0.0 (P1 — close before Stage 4)
- [x] **chatReducer: presence + reset action coverage** — covered in `chatReducer.test.ts` (presence: lines 113-121, reset: lines 124-141).
- [x] **Send-when-disconnected → "failed" state test** — `useWebSocket.test.ts` now tests that `send()` returns false while in `reconnecting` state; `chatReducer.test.ts` covers the fail/retry state transitions end-to-end. App.tsx wiring (`if (!ok) dispatch({kind:"fail"})`) is a 1-line branch from verified primitives.
- [ ] **Retry-failed-message integration test** — `doRetry` in App.tsx re-dispatches and re-sends, but no test exercises the full round-trip: send → fail → retry → ack. A rendered App.tsx component test would close this; deferred to Stage 5.
