import type { ConnectionState } from "../types";

function StatusDots({ connected }: { connected: number }) {
  const dots = Math.min(3, Math.max(0, connected));
  return (
    <span className="presence-dots" aria-hidden="true">
      {Array.from({ length: dots }).map((_, i) => (
        <span key={i} className="presence-dot" />
      ))}
    </span>
  );
}

export function TopBar({
  roomId,
  connected,
  state,
  hudCompact,
}: {
  roomId: string;
  connected: number;
  state: ConnectionState;
  hudCompact: string | null;
}) {
  const presenceLabel =
    state.kind !== "open"
      ? "joining…"
      : connected === 0
      ? "alone here"
      : `${connected} connected`;

  return (
    <header className="top-bar">
      <div className="top-bar__room">#{roomId}</div>
      <div className="top-bar__right">
        <div>
          <StatusDots connected={state.kind === "open" ? connected : 0} />
          <span className="presence-count">{presenceLabel}</span>
        </div>
        {hudCompact && <div className="top-bar__hud-compact mono">◆ {hudCompact}</div>}
      </div>
    </header>
  );
}
