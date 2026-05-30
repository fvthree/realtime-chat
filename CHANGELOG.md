# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to [Semantic Versioning](https://semver.org/) (extended to 4 digits: MAJOR.MINOR.PATCH.MICRO).

## [0.3.1.0] - 2026-05-30

### Fixed

- **Network-error guard in auth gate.** `fetchCurrentUser()` previously swallowed all exceptions (network failures, 5xx responses) and returned `null`, which `App.tsx` interpreted as "unauthenticated" and issued an immediate OAuth redirect. A transient backend blip would bounce an authenticated user through a full GitHub OAuth round-trip. Fix: `fetchCurrentUser` now returns `null` only for HTTP 401; any other failure throws, and `App.tsx` catches it to show "Unable to reach the server. Please refresh." without triggering a redirect.
- **CORS allowed-origins hardcoded to localhost.** `WebSocketConfig.corsConfigurationSource()` had `http://localhost:5173` and `http://127.0.0.1:5173` baked in — any non-localhost deployment got CORS errors. Fixed by reading `CORS_ALLOWED_ORIGINS` env var (comma-separated) with a localhost fallback. Documented in `.env.example`.

### Changed

- **Named constants for `MAX_MESSAGE_TEXT_LENGTH` and `ROOM_ID_PATTERN`.** Backend extracts both as `private static final` constants in `ChatWebSocketHandler`, eliminating a magic `10_000` literal and a `Pattern.compile()` call that ran on every WS frame. Frontend exports `MAX_MESSAGE_TEXT_LENGTH` from `frontend/src/config.ts` so `Composer.tsx` and the backend share the same value by convention.
- **`frontend/src/config.ts` extracted.** `API_BASE` centralised from `App.tsx` and `identity.ts` into a single module. `MAX_MESSAGE_TEXT_LENGTH` added alongside it.
- **Dead `CorsWebFilter` bean removed.** The `CorsWebFilter` in `WebSocketConfig` was registered at `LOWEST_PRECEDENCE` (after Spring Security), so CORS headers were never applied to redirect responses. Removed; Spring Security's `.cors(withDefaults())` picks up the `CorsConfigurationSource` bean directly.
- **`.gitignore` expanded.** Added `frontend/.env`, `frontend/.env.*`, and `.gstack/` to prevent accidental credential and analytics commits.

### Tests

- **`identity.test.ts` contract updated.** `fetchCurrentUser` now throws on network errors and 5xx responses; tests updated to assert `rejects.toThrow` for those cases. New test: 500 backend response throws with status in message.
- **`unauthenticatedUpgradeIsRejected`** — integration test verifying that a WS upgrade without auth headers is closed with a non-2xx handshake.
- **`oversizedMessageIsDropped`** — integration test verifying that a message exceeding `MAX_MESSAGE_TEXT_LENGTH` is never broadcast to room observers.
- **`roomIdExceeding32CharsRedirectsToLobby`** — integration test verifying that a 33-character room ID falls back to lobby and the oversized-room client lands there.
- **`useWebSocket` enabled-flag tests** (3 cases): stays disconnected when `enabled=false`; transitions to connecting when `enabled` flips to `true`; `send()` returns `false` in reconnecting state.

## [0.3.0.0] - 2026-05-27

### Added

- **Stage 4: GitHub OAuth2 authentication.** Spring Security reactive filter chain (`SecurityConfig`) protects `/api/me` and `/ws/chat/**`. Unauthenticated requests redirect to GitHub OAuth2 login. Success redirect URL is configurable via `OAUTH2_SUCCESS_REDIRECT_URL` env var.
- **`/api/me` endpoint.** `ChatController` returns `{login, avatarUrl}` extracted from the OAuth2 principal. Frontend `identity.ts` replaces the old `loadOrCreateSenderId` localStorage approach with `fetchCurrentUser()` — identity is now always the authenticated GitHub login.
- **WebSocket principal stamping.** `ChatWebSocketHandler` extracts the authenticated principal at WS upgrade time; `senderId` on all outbound messages is always the server-authoritative GitHub login — clients can no longer supply arbitrary sender IDs.
- **Per-principal rate limiting.** `RateLimiterService` (Guava `RateLimiter`) maintains one token bucket per principal. Default 3 msg/sec, configurable via `RATE_LIMIT_MSG_PER_SEC`. Rate-exceeded messages are dropped with a WARN log.
- **Frontend auth gate.** `App.tsx` fetches `/api/me` on mount; redirects to `/oauth2/authorization/github` if unauthenticated.
- **`.env.example`** documents all required env vars: `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `OAUTH2_SUCCESS_REDIRECT_URL`, `DB_USERNAME`, `DB_PASSWORD`, `R2DBC_URL`, `RATE_LIMIT_MSG_PER_SEC`.

### Tests

- **`ChatControllerTest`** — `@WebFluxTest` verifying `/api/me` returns login + avatarUrl from OAuth2 principal, and 3xx redirect for unauthenticated requests.
- **`RateLimiterServiceTest`** — unit tests for first-message allowed, within-limit burst allowed, excessive burst denied, and per-principal bucket isolation.
- **`TestAuthWebFilter`** — test-only `@TestConfiguration` WebFilter that reads `X-Auth-User` header and injects a `UsernamePasswordAuthenticationToken` at `HIGHEST_PRECEDENCE`, replacing the live OAuth2 flow in integration tests.
- Integration and resilience tests updated to pass `X-Auth-User` headers via `ReactorNettyWebSocketClient`.

## [0.2.2.0] - 2026-05-27

### Fixed

- **`TypingStop` senderId impersonation and ghost-typing.** Any session could send `{"type":"typing_stop","senderId":"victimUser"}` to suppress another user's typing indicator, or send `{"type":"typing_stop","senderId":""}` which cleared the session's map entry but skipped the broadcast — leaving peers stuck with a ghost typing indicator. The handler now uses the server-stored senderId from `typingBySession` and ignores the client-supplied value entirely. Clients can no longer impersonate or ghost-type each other.

### Tests

- **`typingStopWithBlankSenderIdAfterTypingStartClearsIndicator`** — regression test: typer sends `TypingStart` with `"typerA"`, then `TypingStop` with `""`. Observer must see `typing_stop` with `senderId:"typerA"`. Test uses a `Sinks.One` shutdown signal to hold the session open until the assertion passes, ensuring the typing stop arrives via the blank-senderId handler (not the disconnect cleanup path).
- **`AbstractChatWebSocketTest`** — shared base class extracted from both integration and resilience test classes, eliminating 35 lines of duplicated Testcontainers + Spring wiring boilerplate.

## [0.2.1.0] - 2026-05-26

### Fixed

- **`created_at` tiebreaker precision.** `ChatWebSocketHandler` was setting `created_at` via `Instant.ofEpochMilli(System.currentTimeMillis())` — the same millisecond precision as `server_recv_ts`. The `ORDER BY created_at DESC` tiebreaker in `MessageRepository` was therefore useless for two messages arriving in the same millisecond. Changed to `Instant.now()` for nanosecond precision (Postgres stores microseconds), so the tiebreaker is effective.
- **`TypingStop` missing senderId guard.** A client could send `{"type":"typing_stop","senderId":""}` and the server would broadcast a `typing_stop` event with a blank `senderId`, potentially confusing peer UI state. Added the same null/blank guard that `TypingStart` already had.

### Tests

- **`typingStartWithBlankSenderIdIsDropped`** — new integration test verifying that a `TypingStart` with `senderId=""` is silently dropped and never broadcast to observers.
- **`savePersistFailureDoesNotKillBroadcast`** — new resilience test verifying that a mocked `MessageRepository.save()` failure does not prevent the message from being broadcast to room subscribers (fire-and-forget contract).

## [0.2.0.0] - 2026-05-24

### Added

- **Message persistence.** Chat history is now stored in Postgres via R2DBC. Messages survive server restarts. Joining a room replays the last 50 messages in order, deduplicated client-side by server id.
- **History/live race gap closed.** A `ConnectableFlux` replay buffer (1024 events) starts capturing room events before the history DB query, so messages emitted during the fetch are not lost. Client deduplicates any overlap.
- **NTP-style clock sync.** `useClockSync` now runs a sliding window of 20 ping samples, rejects statistical outliers (σ ≥ 2), and takes the median offset. A fresh 5-ping burst re-syncs every 30 seconds. Peer-side HUD latency is now accurate to within the NTP jitter of the local network.
- **Room-scoped sender identity.** `localStorage` key changed from a shared `senderId` to `realtime-chat:senderId:{roomId}`. Two tabs on different rooms now get independent guest names.
- **Integration tests for presence and history replay.** New `ChatWebSocketHandlerIntegrationTest` cases: presence count increments on join and decrements on leave, `hello` triggers a presence broadcast, and persisted messages are replayed on a fresh join.
- **E2E test scaffold.** `e2e/chat.spec.ts` + `playwright.config.ts` added. Two-tab HUD assertion (`< 100 ms` on localhost) runs locally. Reconnect-storm test is written but skipped in CI pending a process-management fixture.
- **Unit test coverage.** New test files for `useWebSocket` (12 cases: backoff schedule, state machine, send-when-closed, cleanup), `useClockSync` (9 cases: offset math, outlier rejection, periodic resync, sliding-window cap, reset), and `identity.ts` (10 cases: room-scoped localStorage key, fallback, validation). Backend gets 4 new `RoomRegistryTest` cases covering `evictIfEmpty` (basic eviction, active-subscriber guard, stale-object guard, concurrent race).
- **Docker Compose.** `docker-compose.yml` added for one-command Postgres 16-alpine setup on port 5432.

### Fixed

- **SELF RTT HUD inflated after page reload.** History replay messages have `tempId: null` and a stale `clientSendTs`. The RTT calculation now guards `event.tempId !== null`, so history echoes no longer pollute the latency sample set.
- **Ghost-typing on disconnect.** `ChatWebSocketHandler.doFinally` now emits a `TypingStop` event for any session that was actively typing when the WebSocket closed, preventing the typing indicator from sticking in peer tabs.
- **Room ID normalization.** URL parameter is lowercased before validation, so `?room=MyRoom` and `?room=myroom` resolve to the same room.
- **Concurrent room eviction safety.** `RoomRegistry.evictIfEmpty` now uses `ConcurrentHashMap.compute()` to atomically check subscriber count and remove, preventing accidental eviction of a room that received a concurrent new joiner.

### Changed

- `useClockSync` from a 5-ping one-shot estimate to a 20-sample sliding window with σ-outlier rejection and 30 s periodic resync.

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
