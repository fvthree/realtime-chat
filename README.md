# realtime-chat

The fastest-feeling chat done right. Two-tab demo with a per-message latency HUD.

Spring WebFlux backend, React + TypeScript frontend, WebSockets.

```
┌──────────────┐  WebSocket  ┌────────────────────────┐
│ React + TS   │ ◄─────────► │ Spring WebFlux         │
│ Vite :5173   │   /ws/chat  │ Reactor Sinks per room │
└──────────────┘             └────────────────────────┘
        │
        ▼  Latency HUD (3 tiers: green <30 / amber 30-100 / red >100 ms)
```

## Quickstart

Requires Java 21+, Gradle 8.5+, Node 22+.

```bash
# Terminal 1 — backend
cd backend
gradle bootRun

# Terminal 2 — frontend
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173/> in two tabs. Type in one, watch the other. Glance at the HUD.

Try `?room=kitchen` to switch rooms.

## Stage 1 scope

In-memory single-process MVP. No persistence, no auth, no scale, no mobile polish. See [`CLAUDE.md`](./CLAUDE.md) for the staged plan.

## Tests

```bash
cd backend && gradle test
cd frontend && npm run typecheck && npm test
```

## Why WebFlux for a localhost demo?

It isn't, yet. The framework choice pays off at Stage 6 when a 1k-WebSocket-client load test exercises the non-blocking I/O. Until then, this is API-learning territory — and that's the explicit goal.

See [`CLAUDE.md`](./CLAUDE.md) and `TODOS.md` for the full plan.
