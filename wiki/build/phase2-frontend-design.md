# Phase 2.4 Frontend Design — Surface the Moat (StagePerformance)

Author: Ananya · 2026-07-21 · Status: DESIGN ONLY, no code written yet
Source of truth: `wiki/ai-review/meera-label-to-moat-build-plan.md` §2.4 (+ §2.1/2.2 for the payload this depends on)
Depends on: Vikram's `get_campaign_performance` tool (§2.2) and outcome-digest shapes (§2.1) — **currently unbuilt**. This doc is the frontend half of that contract, written first so Vikram can implement against it, per Swapnil's sequencing note ("frontend 2.4, lower priority, starts once API shapes defined but design it now").

TECH-STACK.md was not found at the repo root on this branch (`feat/portfolio-view-tracking`) — only inside `_to_delete/` and a handful of `.claude/worktrees/*` copies (see `wiki/build/project_branch_worktree_divergence` memory: audit/reference docs frequently target a different branch than the one currently checked out). I did not use `_to_delete/TECH-STACK.md` as authoritative since its own directory name says it's slated for removal. Instead I grounded every convention below directly in the real, currently-live sibling components (`StageSnapshot.tsx`, `StageRecommend.tsx`, `ThemeProvenanceBadge.tsx`, `MeeraChatPanel.tsx`) — same effect as reading TECH-STACK.md, verified against actual shipped code rather than a possibly-stale doc. **Flagging for Arjun/Priya:** confirm whether TECH-STACK.md is intentionally absent from this branch or a checkout gap.

---

## 0. Drift found vs the build-plan's description (read this before the file list)

The plan (§2.4) describes the routing piece as "add a `campaign_performance` case in `ToolResultRenderer` (`MeeraChatPanel`) that `advance()`s `useMeeraStage`." Having read both files end to end, **that's not where stage-advancement lives**:

1. **`ToolResultRenderer.tsx` never calls `advance()` or touches `useMeeraStage`.** It's a pure presentational dispatcher — a `toolName` switch that renders a *compact inline card* under a chat bubble (`MeeraChatPanel.tsx:690-700`). It has zero knowledge of the Living Canvas or the stage machine.
2. **The actual stage-advancement gate is `MEERA_FUNCTION_CALLS` + `isMeeraFunctionCall()` inside `MeeraChatPanel.tsx` (lines 84-111).** The SSE `onToolResult` handler (line 493) checks `isMeeraFunctionCall(event.name)` and, only if true and `status === 'ok'`, calls `onFunctionCall(event.name, event.data)` (line 529-531). `onFunctionCall` is a prop — `MeeraWorkspace.tsx:95` wires it directly to `useMeeraStage`'s `advance`.
3. **`useMeeraStage.advance(call, data)`** (`useMeeraStage.ts:38-45`) looks up `stageForFunctionCall(call)` against `STAGE_CONFIG` in `stage-config.ts`, which requires the trigger name to already exist in the `MeeraFunctionCall` union and have a `STAGE_CONFIG` entry — today there are exactly 5, matching the 5 real backend tools 1:1, plus `analyze_site` (mock-only, explicitly excluded from `MEERA_FUNCTION_CALLS` because it's never a real SSE tool name).
4. **There is no `'performance'` stage today.** `MeeraStageId` is a closed union of 5 values (`stage-config.ts:7`), and `LivingCanvas.tsx:94-106` has one hardcoded `{stage === '...' && <Stage.../>}` branch per value — no fallback/default branch, no generic stage renderer.

Net effect: this is a **4-file change**, not a 1-2 file change as the plan's shorthand implies. I've written the file list below against the real mechanism. This doesn't change effort estimate materially (still M+S per the plan) but the "ToolResultRenderer... that advances" framing would have sent whoever implements it hunting in the wrong file first.

Also confirmed: **`nextStage()`** (`stage-config.ts:65-69`) is exported but has zero callers anywhere in `src/` — dead code, unrelated to this work, not touching it.

---

## 1. Files to create

### 1.1 `src/components/feature/meera/StagePerformance.tsx` (new)
Sibling to `StageSnapshot.tsx` / `StageRecommend.tsx`, same shape contract: a `!live` mock branch and a live branch gated by a type guard, falling back to `StageLoadingState` when the payload hasn't arrived yet. See §2 for full component design.

### 1.2 `src/data/meera-mock.ts` (modify, not new — mock data addition)
Needs a `MOCK_CAMPAIGN_PERFORMANCE` constant (ROI, response rate, avg CreatorScore, one narrative sentence) for the `!live` branch, following the existing `MOCK_CAMPAIGN_PLAN` / `MOCK_BRAND_SNAPSHOT` pattern in that file.

### 1.3 Badge component — naming decision needed (see §4)
One new file, `src/components/feature/meera/EstimateBadge.tsx` (or wherever Priya wants shared badges to live — `ThemeProvenanceBadge.tsx` currently lives under `src/components/trendspark/`, which is TrendSpark-specific, not a shared UI location; I'm proposing `src/components/feature/meera/` since this badge is Meera-only, but flagging as an open question in §7).

---

## 2. `StagePerformance.tsx` — component design

### Props (mirrors `StageRecommendProps` exactly — same idiom as every other stage component)
```tsx
interface StagePerformanceProps {
  /** Latest `get_campaign_performance` tool_result payload for this session, if any. */
  toolResult?: unknown
  className?: string
}
```
No other props. Like `StageRecommend`, it reads `isApiLive()` itself and switches mock vs. live internally — LivingCanvas passes nothing but `toolResult`.

### Structure
```
StagePerformance
├── !live branch → mock stat tiles + mock narrative-adjacent copy, MOCK_CAMPAIGN_PERFORMANCE
└── live branch
    ├── !isCampaignPerformancePayload(toolResult) → <StageLoadingState label="Pulling your campaign numbers…" />
    └── payload present →
        ├── 3 stat tiles (StatPair, same component StageRecommend already uses):
        │     - ROI            → formatFn shows "×" multiplier or ₹ return, TBD by backend shape (§3)
        │     - Response rate  → formatFn shows "%"
        │     - Avg CreatorScore → formatFn shows raw 0-100 (matches BrandSafetyBadge/QualityScoreDisplay precedent)
        ├── EstimateBadge/SourceBadge — one per tile IF that tile's underlying number has non-PLATFORM_VERIFIED provenance (see §3, §4). Quiet by default: renders nothing for the common case.
        └── NO narrative text here — the one-sentence Meera-voiced narrative renders in the chat bubble (already-existing `MessageBubble`), NOT duplicated on the canvas card. This is the plan's explicit "card carries numbers, bubble carries narrative, no duplication" requirement — the card's only job is the tiles.
```

### Stat tile layout
Reuses `StatPair` (`src/components/ui/stat-pair.tsx`) exactly as `StageRecommend` does — no new tile primitive needed:
```tsx
<div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
  <StatPair label="ROI" value={...} formatFn={...} />
  <StatPair label="Response rate" value={...} formatFn={(n) => `${n.toFixed(0)}%`} />
  <StatPair label="Avg CreatorScore" value={...} formatFn={(n) => `${Math.round(n)}`} />
</div>
```
2-col on mobile / 3-col ≥sm, matching `StageSnapshot`'s product-tile grid breakpoint convention (`grid-cols-1 sm:grid-cols-3`) rather than `StageRecommend`'s fixed 2-col (which only has 2 stats). Since this stage has 3 stats, 3-col at `sm` reads better than wrapping.

### Where the badge attaches
`StatPair` itself takes no `className`-adjacent slot for a badge today — I'm not modifying `StatPair` (shared primitive, used by StageRecommend and possibly elsewhere; changing its API is a bigger blast radius than this task needs). Instead `StagePerformance` wraps the tile + optional badge itself:
```tsx
<div className="relative">
  <StatPair label="ROI" value={roi} formatFn={fmtRoi} />
  {roiProvenance !== 'high' && <EstimateBadge confidence={roiProvenance} className="absolute right-2 top-2" />}
</div>
```

### Fallback
Same three-state pattern as `StageRecommend`: not-live → mock; live-but-no-payload-yet → `StageLoadingState`; live-with-payload → real tiles. No separate error state component — `ToolResultWrapper`'s `status === 'error'` path (already shared across all stage tools via the chat-inline renderer) covers the error case in chat; the canvas stage, like `StageRecommend`, simply stays on the loading state until a successful payload arrives (existing precedent, not a new pattern).

---

## 3. Frontend↔backend contract — BLOCKED on Vikram, here's exactly what I need

`meera-api.ts` needs a new payload interface + guard, following the exact pattern of `CalculateBudgetPayload`/`isCalculateBudgetPayload` (lines 205-263):

```tsx
/** `MeeraToolDtos.GetCampaignPerformanceResult` (§2.2) — NEEDS VIKRAM'S REAL DTO SHAPE. */
export interface CampaignPerformancePayload {
  roi: number;                    // ??? ratio (1.4 = 140%) or already a percentage? NEEDS ANSWER
  roiSource: 'PLATFORM_VERIFIED' | 'SELF_REPORTED' | 'INFERRED'; // provenance tag, modeled on price_source (§2.1 landmine note)
  responseRate: number;           // 0-100 or 0-1? NEEDS ANSWER
  responseRateSource: 'PLATFORM_VERIFIED' | 'SELF_REPORTED' | 'INFERRED';
  avgCreatorScore: number;        // 0-100, matches existing CreatorScoresResponse scale (lib/types.ts) — CONFIRM same scale
  avgCreatorScoreSource: 'PLATFORM_VERIFIED' | 'SELF_REPORTED' | 'INFERRED';
  campaignId: string;             // needed for the "see full breakdown" link (§5)
  narrative?: string;             // is the one-sentence summary server-generated (deterministic) or is Meera's own LLM turn expected to say it? If server-generated, this field carries it and the frontend never re-derives prose from raw numbers (SR-1 discipline extends naturally to display copy, not just money).
}

export function isCampaignPerformancePayload(data: unknown): data is CampaignPerformancePayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<CampaignPerformancePayload>;
  return (
    typeof d.roi === 'number' &&
    typeof d.responseRate === 'number' &&
    typeof d.avgCreatorScore === 'number'
  );
}
```

**Open questions for Vikram (blocking §2.2 implementation, not blocking this design doc):**
1. Exact field names/casing from the real Java DTO (every existing payload interface in `meera-api.ts` is annotated "verified against the actual Spring DTOs, NOT the stale API-CONTRACT prose" — I will not guess a shape and ship against it; I need the DTO once `GetCampaignPerformanceExecutor.java` exists).
2. Is ROI a ratio, a percentage, or an INR delta? Changes `formatFn` entirely.
3. Is `responseRate` 0-1 or 0-100?
4. Per-field provenance tags (`roiSource` etc.) — does §2.1's `reach_source: PLATFORM_VERIFIED|SELF_REPORTED` pattern extend to all three stats here, or is confidence computed once for the whole payload rather than per-field? The plan says "confidence is high/scraped vs inferred/low" for the badge trigger (§2.4) but §2.1 only names two states (`PLATFORM_VERIFIED|SELF_REPORTED`) while §2.4's badge spec names a third (`inferred`/low) — **these don't obviously match**. I need Vikram/Ash to reconcile whether it's a 2-state or 3-state provenance enum before I can write `EstimateBadge`'s trigger condition precisely (see §4).
5. Does the payload carry `campaignId` (needed for the "see full breakdown" deep link, §5)? `get_campaign_performance` is scoped to one campaign per §2.2, so the campaign is known server-side, but I still need it round-tripped in the response to build the link client-side (the tool call's `input` isn't reliably available to `StagePerformance`, which only sees `stagePayloads.performance`, i.e. the *result*, not the *call arguments* — confirmed by reading `useMeeraStage.ts`, which only stores `data` from `advance(call, data)`, never the tool's input args).

I will not stub a shape and start implementing — that's exactly the "quoting a proxy as verified fact" failure pattern SR-1 calls out, applied to frontend contract-writing instead of money. This section is the deliverable Vikram implements against; I implement against his real DTO once it lands.

---

## 4. Badge component design

### Naming
Plan names it `EstimateBadge`/`SourceBadge` (two names for what reads as one concept). I'm proposing **one component, `EstimateBadge`**, taking a `confidence` prop, rather than two components — `ThemeProvenanceBadge` is the existing precedent and it's a single component with a single boolean-ish trigger (`source !== 'AI_RECOVERED'` → render nothing). Splitting into two components for what's structurally the same quiet-badge idiom seems like unnecessary duplication. Flagging as open question for Priya (§7) in case there's a reason for two.

### API (extends `ThemeProvenanceBadgeProps`'s shape)
```tsx
export interface EstimateBadgeProps {
  /** Provenance/confidence of the number this badge sits next to. `undefined`,
   *  'PLATFORM_VERIFIED', or 'high'/'scraped' (pending Q4 above) render nothing —
   *  quiet by default, same philosophy as ThemeProvenanceBadge. */
  confidence?: 'PLATFORM_VERIFIED' | 'SELF_REPORTED' | 'INFERRED'; // enum pending Vikram (§3 Q4)
  className?: string;
}
```

### Render logic (mirrors `ThemeProvenanceBadge.tsx:30-48` almost exactly)
```tsx
export function EstimateBadge({ confidence, className }: EstimateBadgeProps) {
  if (!confidence || confidence === 'PLATFORM_VERIFIED') return null; // quiet-by-default — only the uncertain case is worth a chip

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border border-meera-border',
        'bg-meera-surface-2 px-2 py-0.5 text-[11px] font-medium text-meera-text-muted',
        className,
      )}
      role="note"
    >
      <TriangleAlert className="h-3 w-3 text-meera-warning" aria-hidden="true" />
      <span aria-hidden="true">Estimated</span>
      <span className="sr-only">
        This number is estimated, not platform-verified — treat it as directional.
      </span>
    </span>
  );
}
```

### Token choice — do NOT use `text-destructive`/`bg-destructive`
Two separate color systems exist in this codebase:
- **`meera-*` tokens** (`meera-warning #E8A317`/`#F4B740` dark, `meera-danger #E0344B`/`#FF5C72` dark, `meera-text-muted`, `meera-accent`) — used by every component under `src/components/feature/meera/` and `src/components/trendspark/`. These are already strong/AA, no pale-on-pale issue.
- **shadcn tokens** (`destructive`/`destructive-foreground`, `muted-foreground`, `primary`) — used by `src/components/analytics/*` (e.g. `BrandSafetyBadge.tsx`, which already correctly uses `text-destructive-foreground`, never the pale `text-destructive`, for its F-grade state).

`StagePerformance`/`EstimateBadge` live in the `meera` family, so they use `meera-warning` (amber, for "estimated" — this is a confidence caveat, not an error) and `meera-text-muted`, not the shadcn destructive pair at all. **The `text-destructive-foreground`-not-`text-destructive` guardrail from project memory is a shadcn-family rule and doesn't literally apply to `meera-*` tokens** — but if a future badge variant needs actual error-red (not just "estimated"), the correct token here is `meera-danger` (already strong contrast, confirmed via `globals.css` — `#E0344B` light / `#FF5C72` dark), analogous to why `destructive-foreground` (not `destructive`) is correct in the shadcn family. Noting this explicitly so nobody "fixes" `meera-danger` into `meera-danger-foreground` under a misapplied version of the same rule — that token pair doesn't exist here and isn't needed.

### a11y checklist for this badge
- Text + icon, never color-only (`TriangleAlert` icon + literal "Estimated" text, matches `ThemeProvenanceBadge`'s `Sparkles` + "Spotted by AI" pattern).
- `role="note"` per the task brief (not `role="status"` — this isn't a live region, it's a static annotation).
- `sr-only` full sentence for screen readers, short visible label for sighted users — same split `ThemeProvenanceBadge` uses.
- `aria-hidden="true"` on the icon and the short visible span (redundant with the `sr-only` sentence, avoids double-announcing "Estimated... This number is estimated...").
- **Never spoken aloud.** `useVoiceOutput.speak()` is only ever called with `assistantText` (the streamed token buffer) — badges are DOM-only, never enter that string, so no explicit "exclusion" code is needed; confirming this by construction (badge text never touches the TTS pipeline) rather than adding a redundant guard. Documenting this as an explicit non-goal in the component's doc comment so a future refactor doesn't accidentally start reading canvas card text aloud (e.g. if someone later adds a generic "read the canvas" voice feature, they need to know badges/estimate-chips are opt-out).

---

## 5. Routing wiring

### 5.1 `src/data/stage-config.ts`
```tsx
export type MeeraStageId = 'snapshot' | 'recommend' | 'matching' | 'funding' | 'live' | 'performance'

export type MeeraFunctionCall =
  | 'analyze_site'
  | 'calculate_budget'
  | 'show_creators'
  | 'create_campaign'
  | 'request_payment'
  | 'confirm_launch'
  | 'get_campaign_performance'   // NEW — must match Vikram's real Python schemas.py tool name exactly (§2.2 says `get_campaign_performance`, plan prose elsewhere shorthands it `campaign_performance` — CONFIRM which is the real wire name before implementing, these must match byte-for-byte or stageForFunctionCall silently no-ops)

export const STAGE_CONFIG: Record<MeeraStageId, StageConfigEntry> = {
  // ...existing 5 entries unchanged...
  performance: {
    id: 'performance',
    order: 6,
    title: MEERA_STAGE_TITLES.performance,   // add to meera-copy.ts
    subtitle: MEERA_STAGE_SUBTITLES.performance,
    trigger: 'get_campaign_performance',
  },
}

export const STAGE_ORDER: MeeraStageId[] = ['snapshot', 'recommend', 'matching', 'funding', 'live', 'performance']
```
Verified safe to append at the end: `STAGE_ORDER` only feeds `stageForFunctionCall()` (order-independent `.find()`) and the dead `nextStage()` — `StageMorph` (the animation wrapper) keys purely off `stage` identity, not numeric order, so there's no "forward/backward" animation direction logic to break (confirmed by reading `StageMorph.tsx` in full — no order/index usage at all).

### 5.2 `src/data/meera-copy.ts`
Add `performance: '...'` to both `MEERA_STAGE_TITLES` and `MEERA_STAGE_SUBTITLES` (both already typed as loose `Record<string, string>`, so this is additive, no type-widening needed).

### 5.3 `src/components/feature/meera/MeeraChatPanel.tsx`
Add to the real gate array (line 101-107):
```tsx
const MEERA_FUNCTION_CALLS: readonly MeeraFunctionCall[] = [
  'calculate_budget',
  'show_creators',
  'create_campaign',
  'request_payment',
  'confirm_launch',
  'get_campaign_performance',   // NEW
]
```
This is the actual line that makes `onFunctionCall` (→ `advance` → canvas stage change) fire for this tool. Nothing else in this file needs to change — `onToolResult`'s handler is already generic over any name in this array.

### 5.4 `src/components/feature/meera/LivingCanvas.tsx`
Add a 6th stage branch (line ~94-106):
```tsx
{stage === 'performance' && <StagePerformance toolResult={stagePayloads?.performance} />}
```
**Open question (§7):** `escrowStateForStage()` (`LivingCanvas.tsx:31-35`) only special-cases `'funding'` and `'live'`; `'performance'` falls through to `'unfunded'`, which would visually regress the EscrowPill from "released"/secured back to the unfunded look if a brand navigates to the performance stage after a campaign has already gone live and funds released. That's almost certainly wrong — needs a decision (§7), not silently defaulted.

### 5.5 `src/components/feature/meera/ToolResultRenderer.tsx` — inline chat card
The plan's "no duplication" instruction (§2.4) is specifically about the **canvas card vs. the chat bubble narrative** (numbers on canvas, prose in the bubble). It says nothing about the **third surface** — the compact inline card `ToolResultRenderer` renders directly under the assistant's chat bubble, which every other tool (`calculate_budget`, `show_creators`, etc.) already gets *in addition to* its full canvas stage card. That existing pattern is itself a duplication (`CalculateBudgetResult`'s inline card shows the same 3 numbers `StageRecommend` shows on canvas) — Ananya isn't introducing that pattern, just deciding whether to extend it.

**My recommendation: skip the inline card for `get_campaign_performance`.** Unlike the other tools (which are one-shot forward-progressing steps where the inline card is useful as a permanent scroll-back record), campaign performance is a stat snapshot that's fully replaced by the canvas card the user is already looking at when this fires, and duplicating 3 stat tiles + badges into a cramped inline card adds visual noise without adding information — closer in spirit to why `present_options` gets a special card type rather than reusing a generic pattern. I'm treating "add a case" from the plan as "wire the routing," not "necessarily render a compact card too," and defaulting to *not* adding a `ToolResultRenderer` case unless Priya/Kavya want the redundant record-of-the-turn for scroll-back. Flagged as an open question, not a unilateral cut (§7).

---

## 6. a11y compliance summary (cross-referencing project memory + task brief)

| Requirement | How this design satisfies it |
|---|---|
| Badges text+icon, not color-only | `EstimateBadge` = `TriangleAlert` icon + literal "Estimated" text, same as `ThemeProvenanceBadge` |
| `text-destructive-foreground` not `text-destructive` | N/A directly — `meera-*` token family used instead (§4); noted the shadcn-family rule doesn't blindly transfer |
| Provenance badges never spoken aloud | By construction — badge text never enters the `speak(assistantText, lang)` call in `MeeraChatPanel.tsx:542`, which only ever receives the streamed token buffer, not DOM/badge content |
| `useReducedMotion` bypass on any new motion | `StagePerformance` reuses `StatPair`'s existing `CountUp` (already reduced-motion-safe per that component's own doc comment) — no new raw `motion.*` usage planned, so no new bypass code needed. If a card-level entrance animation is added later, follow `StageMorph`'s existing `useReducedMotion()` check. |
| `role="note"` + `sr-only` on the badge | Included in §4 design |
| No `<img>`, no `any`, Tailwind only | Same constraints as every other file in this directory — no images or `any` needed for this component, plain Tailwind classes throughout |

---

## 7. Open questions for Priya + Ash

1. **Provenance enum mismatch (§3 Q4):** §2.1 names a 2-state enum (`PLATFORM_VERIFIED|SELF_REPORTED`), §2.4's badge spec implies 3 states (`scraped`/high vs `inferred`/low — closer to the existing `price_source` 3-state pattern from `d3d1ab7`). Which is authoritative for `get_campaign_performance`'s per-field confidence? This directly changes `EstimateBadge`'s prop type and trigger condition.
2. **Tool wire-name exact spelling:** is it `get_campaign_performance` (§2.2's own text) or `campaign_performance` (§2.4's shorthand)? Needs to match Python `schemas.py` + `TOOL_TO_SPRING_PATH` byte-for-byte or the stage never advances (silent no-op, not an error — `isMeeraFunctionCall` just returns false and the tool_result still renders in chat via the "anything else off the wire is ignored" path... actually no, re-checking: `onToolResult` returns early at line 498 `if (!isStageCall && event.name !== 'present_options') return` — a name mismatch means the result is dropped entirely, not even shown in chat. This is a hard requirement, not cosmetic.)
3. **EstimateBadge vs EstimateBadge+SourceBadge as two components** (§4) — I collapsed to one; confirm that's fine or state why two were specified.
4. **EscrowPill state for the `performance` stage** (§5.4) — needs an explicit decision, not a silent default to `'unfunded'`.
5. **Inline `ToolResultRenderer` card for this tool** (§5.5) — my recommendation is to skip it; needs sign-off since it's a UX call, not purely technical.
6. **"See full breakdown" link destination:** confirmed a real analytics page exists at `/brand/analytics` (`src/pages/brand-analytics.tsx`, routed in `App.tsx:260`), plus a per-creator drill-down at `/brand/analytics/:creatorId` (`App.tsx:271`). **Neither is campaign-scoped.** `get_campaign_performance` is scoped to one campaign (§2.2), but there is no `/brand/analytics/campaign/:id` (or equivalent) route today. Options: (a) link to the general `/brand/analytics` overview (loses the "this specific campaign" context), (b) Vikram/Priya greenlight a small new campaign-scoped analytics view (out of this ticket's stated scope), (c) drop the link for v1 and ship stat tiles only. Recommend (a) for v1 with a follow-up ticket for a proper campaign-scoped destination — flagging rather than deciding unilaterally since it's a scope call.
7. **TECH-STACK.md absence on this branch** (top of doc) — confirm intentional vs. checkout gap before this becomes a recurring "which convention doc do I trust" problem across tickets.
8. **`roi`/`responseRate` numeric shape** (§3 Q2/Q3) — blocks writing `formatFn` correctly; not guessing.

---

## 8. Changes log

_(Append one entry per implementation step, once Priya/Ash approve this design and Vikram's DTO lands. Empty until then.)_

- 2026-07-21 — Design doc written. No implementation code yet. Blocked on: Vikram's `GetCampaignPerformanceResult` DTO shape (§3), Priya/Ash sign-off on §7 open questions.
- 2026-07-21 — **Implementation (Ananya).** Priya + Ash sign-off landed (APPROVED-WITH-CHANGES, F1–F3 folded in). Vikram's backend implementation had NOT landed a changes-log entry as of this pass (his §8 was still empty) — implemented against the Q2-ruled DTO shape from `phase2-priya-review.md`/`phase2-ash-review.md` directly, typing `roi` + `provenance` as the guaranteed core and `responseRate`/`avgCreatorScore` as optional per the task brief's fallback instruction. Files touched:
  - `src/lib/meera-api.ts` — added `CampaignPerformancePayload` interface + `isCampaignPerformancePayload` guard, following the existing `CalculateBudgetPayload` idiom. `roi: number | null` (null = zero spend / no revenue, never client-computed), `provenance: 'PLATFORM_VERIFIED' | 'SELF_REPORTED'` as a single top-level tag (no per-field `*Source`, no `'INFERRED'` — dropped per Priya/Ash Q1). `responseRate`/`avgCreatorScore` typed optional.
  - `src/data/stage-config.ts` — added `'performance'` to `MeeraStageId`, added `'get_campaign_performance'` to the `MeeraFunctionCall` union (locked wire name, F1/F5 — commented in place), added the `performance` `STAGE_CONFIG` entry (order 6, trigger `get_campaign_performance`) and appended to `STAGE_ORDER`.
  - `src/data/meera-copy.ts` — added `performance` entries to `MEERA_STAGE_TITLES`/`MEERA_STAGE_SUBTITLES`, added `roi`/`responseRate`/`avgCreatorScore` to `MEERA_STAT_LABELS`, added new `MEERA_PERFORMANCE_COPY` (roiUnavailable, seeFullBreakdown) — no hardcoded strings in the new component per repo convention.
  - `src/data/meera-mock.ts` — added `MockCampaignPerformance` interface + `MOCK_CAMPAIGN_PERFORMANCE` constant (roi 2.4, responseRate 0.72, avgCreatorScore 84) for the `!live` branch. Not wired into `MEERA_CONVERSATION_SCRIPT` — no scripted mock turn reaches the performance stage yet (out of this ticket's scope; the mock branch exists for direct `isApiLive()===false` rendering/testing).
  - `src/components/feature/meera/EstimateBadge.tsx` (new) — single badge component (Priya/Ash F4 approved the collapse from two), `provenance` prop, quiet-by-default (renders `null` unless `SELF_REPORTED`), `meera-warning`/`meera-text-muted` tokens (not shadcn destructive — meera-family precedent per `ThemeProvenanceBadge`), `role="note"` + sr-only caveat, dormant in v1 since the badge now gates on the single top-level tag rather than per-field.
  - `src/components/feature/meera/StagePerformance.tsx` (new) — mock/live branches mirroring `StageRecommend`'s shape; 3-tile stat grid (`StatPair`, 2-col mobile / 3-col `sm:`) for ROI/response-rate/avg-CreatorScore, skipping a tile when its field is optional-and-absent, showing a "not enough data yet" dashed placeholder when `roi === null`; single `EstimateBadge` at the card top (not per-tile, since provenance is a whole-result tag now); "See full breakdown" `Link` to `/brand/analytics` (F6 — not campaign-scoped, flagged as a follow-up, no route invented); `StageLoadingState` fallback while live and no payload yet; zero narrative text on the card (Priya/Ash Q2 — narrative lives only in the chat bubble).
  - `src/components/feature/meera/MeeraChatPanel.tsx` — added `'get_campaign_performance'` to the `MEERA_FUNCTION_CALLS` gate array (the actual stage-advancement trigger, confirmed §0's routing correction) and updated its doc comment.
  - `src/components/feature/meera/LivingCanvas.tsx` — imported `StagePerformance`, added the `stage === 'performance'` render branch; fixed `escrowStateForStage` to map `'performance'` → `'secured'` instead of falling through to `'unfunded'` (F2/F4 — a completed, funds-released campaign must not visually regress); extended `liveTotalLabel` to prefer `stagePayloads.performance.spendInr` (the real released-escrow figure) ahead of the earlier advisory `funding`/`recommend` numbers when present.
  - `src/components/feature/meera/ToolResultRenderer.tsx` — **not modified**, by design (F5 approved): the dispatcher has no case for `get_campaign_performance`, so a live `tool_result` with that name renders the generic `ToolResultWrapper` success/error chrome with no inline card body — the canvas card is the only surface for this tool's numbers.
  - Full-project `npx tsc --noEmit -p .` run clean (0 errors) after all changes.
  - **Not done in this pass (explicitly out of scope per the task brief):** the `OPTION_TAPPED` write endpoint (blocked on Vikram's firm path+shape, §3.3 of his design doc), any change to `MEERA_CONVERSATION_SCRIPT` to script a mock performance turn, and any backend DTO/route work (Vikram's side). Kavya QA + Meera local verification + Kabir's mandatory gate (k-anon/IDOR on `get_campaign_performance` itself) are all still pending and are backend-scoped, not blocked by this frontend slice.
