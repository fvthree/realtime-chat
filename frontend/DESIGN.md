# Design — Stage 1

De-facto micro-system. Will be formalized via `/design-consultation` after Stage 2.

## Category

APP UI (not marketing). Calm surface hierarchy, dense but readable, minimal chrome.

## Typography

```
--font-sans: "Inter Variable", "SF Pro Text", system-ui, sans-serif    /* body */
--font-mono: "JetBrains Mono Variable", "SF Mono", ui-monospace, mono  /* HUD, timestamps */
--font-size-base: 15px
--font-size-meta: 12px
--font-size-hud:  28px   /* the brag */
```

JetBrains Mono has tabular figures, which keep HUD digits from jittering as values change.

## Color (dark, Stage 1)

```
--bg:       #0E1014    canvas
--surface:  #15181E    HUD card, composer
--ink:      #E7E9EC    primary text       (14.5:1 contrast — AAA)
--ink-dim:  #8A8F98    secondary, meta    (5.4:1  contrast — AA)
--rule:     #23272F    hairline dividers
--accent:   #6EE7B7    HUD numerals, presence
--warn:     #F59E0B    HUD amber tier
--danger:   #EF4444    HUD red tier, failed, disconnected
```

One accent. No gradients. No decorative shadows.

## Latency HUD tiers

```
   ms < 30          green   (accent, with glow)
   30 ≤ ms ≤ 100    amber
   ms > 100         red
   no sample yet    faint   ("—")
```

Two rows: `self` (sender's own round-trip echo) and `peers` (receiver-side wire latency, clock-skew-adjusted). Each row has last / p50 / p99.

## Components

| Component | Notes |
|---|---|
| `TopBar` | `#roomId` left, presence dots + count + compact HUD right. |
| `ConnectionStrip` | Red 'disconnected' / amber 'reconnecting in Ns' / hidden when open. |
| `MessageList` | Flat list, no bubbles. Empty state: "No messages yet. Type below to start." + ↓ |
| `MessageRow` | name + tabular timestamp + body. Status: `pending` (●), `failed` (✕ + retry), `acked` (no chrome). |
| `Composer` | Single-line input + send. ⏎ submits; ⇧⏎ newline; Esc blurs. |
| `LatencyHUD` | Fixed bottom-right card. Two rows × three columns. 32-sample sparkline. |

## Accessibility baseline (Stage 1, locked)

- `role="log"` on message list with `aria-live="polite"`.
- `role="status"` `aria-live="assertive"` on connection strip.
- Composer has visible `aria-label`. Send button is real `<button type="submit">`.
- HUD `role="region"` with `aria-label` summarizing latest p50.
- Focus rings: `box-shadow: 0 0 0 2px var(--accent)` on `:focus-visible`. Never `outline:none` without replacement.
- Contrast: AA minimum on every text pair; ink/bg is AAA.
- Touch targets: send button 44×44 min.

## Out of scope

- Light theme — Stage 7
- Mobile breakpoint — Stage 7 (single-col + safe-area inset + compact HUD)
- Avatars — Stage 4
- Animation choreography — Stage 7
