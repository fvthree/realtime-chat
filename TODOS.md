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
- [ ] **TypingStop senderId impersonation** — any session can send `{"type":"typing_stop","senderId":"victimUser"}` to suppress another user's typing indicator. Client-supplied `senderId` is not validated against the session's stored identity in `typingBySession`. Fix: verify `t.senderId()` matches `room.typingBySession.get(session.getId())` before emitting, or wait for Stage 4 auth which provides a real session principal. (The blank-senderId guard was added, but sender identity is still client-controlled.)
- [ ] **Fire-and-forget save() has no timeout** — `messageRepository.save(...).subscribe(...)` is an untracked subscription with no timeout. Under R2DBC connection pool exhaustion, save calls queue up indefinitely. Add `.timeout(Duration.ofSeconds(5))` before `.subscribe()` to bound the window. Low priority until Stage 5 multi-instance or real load.


- [ ] **application.yml hardcoded credentials** — `username: postgres` / `password: postgres` committed plaintext. Change to `${DB_USERNAME:postgres}` / `${DB_PASSWORD:postgres}` env-var pattern before Stage 4 or any non-localhost deployment. Flagged in pre-landing review.
- [ ] **No server-side message text length cap** — `ChatWebSocketHandler.java:140` only guards blank text; Netty allows up to 64 KB frames which persist unchecked to the `TEXT` column. Add `m.text().length() > 10_000` guard alongside the blank check; mirror with `maxLength={10000}` on the `<input>` in Composer.tsx.
- [ ] **Room ID max-length mismatch** — `identity.ts:37` enforces `/^[a-z0-9-]{1,32}$/` (32 chars) while `ChatWebSocketHandler.java:206` allows up to 64 chars. Standardize on 64 in the frontend regex, or on 32 in the backend constant. Choose one before Stage 5 multi-instance work.

## Test gaps deferred from v0.2.0.0 (P1 — close before Stage 4)
- [ ] **chatReducer: presence + reset action coverage** — `chatReducer.test.ts` has no tests for the `presence` (updates `chat.connected`) or `reset` actions. Add at least one test each; the file is otherwise well-covered.
- [ ] **Send-when-disconnected → "failed" state test** — App.tsx dispatches `{kind:"fail",tempId}` when `useWebSocket.send()` returns false, but there is no integration-level test that opens a socket, closes it, sends a message, and asserts the message enters `status:"failed"` state. Wire-level gap; the unit tests in `useWebSocket.test.ts` cover the `send()=false` path in isolation.
- [ ] **Retry-failed-message integration test** — `doRetry` in App.tsx re-dispatches and re-sends, but no test exercises the full round-trip: send → fail → retry → ack. A `WebTestClient` integration test or a Vitest mock-WebSocket test would close this.
