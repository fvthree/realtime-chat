import type { ConnectionState } from "../types";

export function ConnectionStrip({ state }: { state: ConnectionState }) {
  if (state.kind === "open" || state.kind === "disconnected") return null;

  if (state.kind === "connecting") {
    return (
      <div className="conn-strip conn-strip--reconnecting" role="status" aria-live="assertive">
        connecting…
      </div>
    );
  }

  // reconnecting
  const seconds = Math.max(0, Math.ceil(state.retryInMs / 1000));
  return (
    <div className="conn-strip conn-strip--reconnecting" role="status" aria-live="assertive">
      disconnected — reconnecting in {seconds}s (attempt {state.attempt})
    </div>
  );
}
