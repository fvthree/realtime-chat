# realtime-chat

Stack: Spring WebFlux + React (TypeScript) + WebSockets. Staged build — Stage 1 is a localhost-only two-tab demo with a latency HUD.

## Layout

```
backend/    Spring Boot 3.x + WebFlux. Java 21. Gradle. Port 8080.
frontend/   Vite + React 18 + TS strict. Port 5173 (dev). WS proxy /ws → :8080.
e2e/        Playwright (Stage 1+: not yet scaffolded).
.github/    CI workflow (gradle test + npm typecheck + vitest).
```

## Run locally

Two terminals. Both required for the two-tab demo.

```bash
# Terminal 1: backend
cd backend && gradle bootRun

# Terminal 2: frontend
cd frontend && npm install && npm run dev

# Then open http://localhost:5173/ in two tabs.
# Add ?room=foo to swap rooms.
```

## Testing

```bash
cd backend && gradle test     # JUnit + WebTestClient WebSocket integration
cd frontend && npm test       # Vitest unit tests
cd frontend && npm run typecheck
```

## Staged plan

- **Stage 1** (this branch) — Spring WebFlux in-memory room registry, Sinks.Many per room, optimistic UI, 3-tier latency HUD, GitHub Actions CI.
- Stage 2 — R2DBC + Postgres for message persistence + replay-on-join.
- Stage 3 — Honest NTP-style clock sync over a sliding window.
- Stage 4 — Spring Security reactive + GitHub OAuth. WebSocket auth context propagation.
- Stage 5 — Redis Pub/Sub bridge for horizontal scale (multiple instances).
- Stage 6 — k6/Gatling load test of 1k concurrent WS clients. The demo video.
- Stage 7 — Mobile/PWA polish, light theme, iOS Safari socket revival.

## Key architectural decisions

- **`Sinks.many().multicast().onBackpressureBuffer(1024)`** per room, slow-subscriber disconnect on overflow. Trades a stuck tab for no OOM, no silent drops.
- **`Mono.zip(inboundDrain, outboundDrain)`** in `ChatWebSocketHandler.handle` — the #1 WebFlux WebSocket footgun if you only return one side.
- **Optimistic UI with tempId reconciliation** — sender sees own message instantly; server echo replaces in-place using React key=tempId so no remount/flicker.
- **Latency HUD tiers** locked at <30ms green / 30–100 amber / >100 red (supports the "sub-50ms feel" claim).
- **Two HUD rows: self and peers** — self = sender's own round-trip echo; peer = receiver-side wire latency adjusted by clock-sync offset.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas / brainstorming → /office-hours
- Strategy / scope → /plan-ceo-review
- Architecture → /plan-eng-review
- Design system / plan review → /design-consultation or /plan-design-review
- Full review pipeline → /autoplan
- Bugs / errors → /investigate
- QA / testing site behavior → /qa or /qa-only
- Code review / diff check → /review
- Visual polish → /design-review
- Ship / deploy / PR → /ship or /land-and-deploy
- Save progress → /context-save
- Resume context → /context-restore
