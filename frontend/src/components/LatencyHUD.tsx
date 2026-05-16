import { useMemo } from "react";
import type { LatencySample } from "../types";

/**
 * Locked tier thresholds (Pass 7A): <30ms green, 30-100 amber, >100 red.
 * Two rows (self / peers, Pass 7B): self = sender's own send→ack RTT;
 * peers = inferred from incoming serverRecvTs vs local clock + offset.
 *
 * Tabular numerals (`.mono` class) prevent digit jitter as values change.
 */

type Tier = "green" | "amber" | "red" | "none";

function tierFor(ms: number | null): Tier {
  if (ms == null) return "none";
  if (ms < 30) return "green";
  if (ms <= 100) return "amber";
  return "red";
}

function percentile(samples: number[], p: number): number | null {
  if (samples.length === 0) return null;
  const sorted = [...samples].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.floor(p * sorted.length));
  return Math.round(sorted[idx]!);
}

function fmt(ms: number | null): string {
  return ms == null ? "—" : String(ms);
}

type Stats = {
  last: number | null;
  p50: number | null;
  p99: number | null;
};

function stats(samples: LatencySample[], kind: "self" | "peer"): Stats {
  const subset = samples.filter((s) => s.kind === kind).map((s) => s.ms);
  return {
    last: subset.length > 0 ? Math.round(subset[subset.length - 1]!) : null,
    p50: percentile(subset, 0.5),
    p99: percentile(subset, 0.99),
  };
}

function Cell({ label, ms }: { label: string; ms: number | null }) {
  const tier = tierFor(ms);
  return (
    <div className="hud__cell">
      <span className="hud__cell-label">{label}</span>
      <span className={`hud__cell-value mono hud__cell-value--tier-${tier}`}>{fmt(ms)}</span>
    </div>
  );
}

function Sparkline({ samples }: { samples: LatencySample[] }) {
  if (samples.length === 0) return null;
  const vals = samples.slice(-32).map((s) => s.ms);
  const max = Math.max(...vals, 1);
  return (
    <div className="hud__sparkline" aria-hidden="true">
      {vals.map((v, i) => (
        <span
          key={i}
          className="hud__spark-bar"
          style={{ height: `${Math.max(2, (v / max) * 18)}px` }}
        />
      ))}
    </div>
  );
}

export function LatencyHUD({
  samples,
  syncing,
}: {
  samples: LatencySample[];
  syncing: boolean;
}) {
  const self = useMemo(() => stats(samples, "self"), [samples]);
  const peer = useMemo(() => stats(samples, "peer"), [samples]);

  const ariaLabel = useMemo(() => {
    const parts: string[] = [];
    if (self.p50 != null) parts.push(`self median ${self.p50} ms`);
    if (peer.p50 != null) parts.push(`peers median ${peer.p50} ms`);
    return parts.length > 0 ? `latency: ${parts.join(", ")}` : "latency: syncing";
  }, [self.p50, peer.p50]);

  return (
    <aside
      className={`hud${syncing ? " hud--syncing" : ""}`}
      role="region"
      aria-label={ariaLabel}
    >
      <div className="hud__title">
        ◆ LATENCY HUD {syncing && <span>syncing…</span>}
      </div>

      <div className="hud__row">
        <span className="hud__label">self</span>
        <Cell label="last" ms={self.last} />
        <Cell label="p50" ms={self.p50} />
        <Cell label="p99" ms={self.p99} />
      </div>
      <div className="hud__row">
        <span className="hud__label">peers</span>
        <Cell label="last" ms={peer.last} />
        <Cell label="p50" ms={peer.p50} />
        <Cell label="p99" ms={peer.p99} />
      </div>

      <Sparkline samples={samples} />
    </aside>
  );
}
