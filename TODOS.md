# TODOs

Captured during /plan-eng-review and /plan-design-review for Stage 1, deferred to later stages on purpose.

## Stage 2 — Persistence
- [ ] **Empty-room eviction** — currently `RoomRegistry` leaks `Room` entries forever. Either Caffeine TTL (10min expireAfterAccess) or subscriber-refcount → remove. Pick once persistence lands (TTL is easier with R2DBC).
- [ ] **Message ordering under fan-out** — once Stage 5 multi-instance arrives, decide between client-side sort by `serverRecvTs` vs server monotonic id from Postgres.

## Stage 3 — Honest HUD
- [ ] **Full NTP-style clock sync** — current Stage 1 estimate uses serverRecvTs/serverSendTs midpoint. Replace with sliding-window median offset every 30s, reject outliers > 2σ.

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
- [ ] **Reconnect-storm regression test** — kill server, restart within 2s, assert clean recovery (Playwright). Identified as critical gap in eng review.
- [ ] **Two-tab E2E with HUD assertion** — Playwright two-context test verifying message arrives in tab B with HUD value < 100ms on localhost.
