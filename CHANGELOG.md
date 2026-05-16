# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/) (extended to 4 digits: MAJOR.MINOR.PATCH.MICRO).

## [0.1.0.0] - 2026-05-15

### Added

- **Stage 1 chat MVP.** Two-tab local demo with per-message latency HUD.
- Spring Boot 3.3 + WebFlux backend on port 8080. Single WebSocket endpoint `/ws/chat/{roomId}`.
- Reactive room model: `RoomRegistry` (concurrent map) + `Room` (Reactor `Sinks.Many` with `onBackpressureBuffer(1024)`, slow-subscriber disconnect on overflow).
- `ChatWebSocketHandler` composes inbound + outbound streams via `Mono.zip`, handles message broadcast, ping/pong with three timestamps for clock sync, typing indicators, and presence.
- Sealed `ChatEvent` hierarchy (`Message`, `Ping`, `Pong`, `TypingStart`, `TypingStop`, `Presence`, `Hello`) with Jackson polymorphic JSON.
- React 18 + TypeScript (strict) frontend on port 5173 via Vite, with WebSocket proxy to the backend.
- `useWebSocket` hook with exponential-backoff reconnect (500ms → 30s cap).
- `useClockSync` 5-ping handshake with median offset estimate.
- `chatReducer` with optimistic UI: temp-id reconciliation in place (no remount on server echo), failed-message retry affordance.
- Latency HUD: three-tier color thresholds (<30ms green, 30–100 amber, >100 red), two rows (self / peers), last / p50 / p99, 32-sample sparkline, tabular monospace numerals.
- Connection state strip: red disconnected → amber reconnecting with countdown.
- Empty-room first-impression copy with composer affordance arrow.
- Typing indicator strip (italic dim, single or "N people typing").
- Accessibility baseline: `role="log"` + `aria-live="polite"` on message list, `role="status"` + `aria-live="assertive"` on connection strip, keyboard map (⏎ send, ⇧⏎ newline, Esc blur), 2px accent focus rings, AAA contrast on primary text.
- Random `guest-XXXX` display name persisted in localStorage.
- `?room=<slug>` URL parameter for switching rooms (regex-validated; default `lobby`).
- Tests: 5 backend tests (`RoomRegistryTest` concurrent-creation + 2 integration tests via `WebTestClient` covering two-client message exchange and ping/pong timestamp echo); 7 frontend reducer tests covering optimistic reconciliation, dedup, failed/retry transitions, and typing self-ignore.
- GitHub Actions CI workflow running `gradle test`, `npm run typecheck`, and `vitest`.
- Design system stub at `frontend/DESIGN.md`: typography (Inter + JetBrains Mono), color tokens (dark monochrome + mint accent), HUD spec, a11y baseline.
- `CLAUDE.md` with project layout, run instructions, staged build plan (Stages 1–7), and gstack skill routing.
- `TODOS.md` capturing Stage 2+ deferred work (empty-room eviction, full NTP clock sync, WebSocket auth context, Redis bridge, load test, mobile/PWA, E2E reconnect test).
