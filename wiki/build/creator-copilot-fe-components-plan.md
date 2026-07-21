# FE Implementation Plan: Creator AI Co-pilot Tier-1 — Components/UX/States

**Author:** Ananya (Frontend) · **Status:** DRAFT FOR PRIYA REVIEW — no code written yet
**Source of truth:** [creator-ai-copilot-tier1-build-spec.md](../ai-review/creator-ai-copilot-tier1-build-spec.md) §3
**Scope boundary:** this plan owns components/UX/states only. A second FE agent owns
`src/hooks/useDailySuggestion.ts`'s data-fetching internals and `src/lib/api.ts` /
`API-CONTRACT.md` wiring — I consume that hook's return shape as a contract, I do not
implement its fetch logic here. Where I need something from that layer it's called out in §6.
**Gate:** per spec, nothing starts until Priya certifies money-path stability. This is the plan
to execute the day that signoff lands.

---

## 1. New component files

All new files live under `src/components/creator/copilot/` (0% today, confirmed via glob).

### 1.1 `src/components/creator/copilot/DailySuggestionCard.tsx`

**Modeled on:** `src/components/creator/hype-inbox-card.tsx` (Card shell + header badge +
local status derived from a prop, not internal polling) crossed with the AI-accent visual
language of `src/components/feature/meera/MessageBubble.tsx` (accent-soft chip for
AI-authored text) — visual reference only, this component does NOT import from
`components/feature/meera/*` (that's a Meera-workspace-scoped module tree; the copilot card
gets its own local "AI phrased this" chip using the same token, `bg-primary/10 text-primary`
or the repo's existing accent-soft equivalent — see §5).

```typescript
export interface DailySuggestion {
  id: string;
  theme: string;
  headline: string;
  contentIdea: string;
  expiresAt: string; // ISO
}

export type SuggestionStatus = 'idle' | 'loading' | 'ready' | 'dismissed' | 'error';

interface DailySuggestionCardProps {
  suggestion: DailySuggestion | null;
  status: SuggestionStatus;
  onDismiss: (id: string) => void | Promise<void>;
  onMarkActed: (id: string) => void | Promise<void>;
  onRetry?: () => void;
  className?: string;
}
```

Internals:
- Local button-level `'idle' | 'submitting'` sub-state per action (Dismiss/Mark-done), same
  pattern as `HypeInboxCard`'s `handleAccept` (optimistic disable, revert on throw) — the
  `status` prop drives which BODY renders, a local state only drives button spinners.
- Renders nothing itself for `idle` / `loading` / `error` — those are handled by sibling
  components per the router table in §3. `DailySuggestionCard` only renders the `ready` and
  `dismissed` bodies (it is the single component mounted across those two states, matching
  spec §3.4's "`dismissed` → collapsed row" being a *visual variant of this same card*, not a
  new component).
- `ready` body: header badge ("Sparkles" icon + "Today's idea", solid `Button` variant per
  §5) → `suggestion.theme` badge → `suggestion.headline` (font-medium) →
  `suggestion.contentIdea` (text-sm text-muted-foreground, the "3-beat reel idea" copy) →
  footer row with Dismiss (`variant="outline"`) and Mark-done (solid `Button`, WCAG-AA per §5).
- `dismissed` body: single collapsed row, "Next one tomorrow" (text-muted-foreground), no CTAs.
- Arrival animation: `framer-motion`, `useReducedMotion()` bypass exactly like the pattern
  mandated in this repo's coding standard (see the persona-doc example) — `initial={{ opacity:
  0, y: 12 }}` / `animate={{ opacity: 1, y: 0 }}` using `EASE_OUT`/`DURATION_NORMAL` from
  `@/lib/motion-config` (general-purpose tokens — NOT `@/data/motion-tokens`, which is a
  Meera-workspace-only constant file per its own header comment).

### 1.2 `src/components/creator/copilot/IGConnectPrompt.tsx`

**Modeled on:** `src/components/creator/connected-accounts.tsx` — reuses its
`api.metaOAuth.authorize()` call verbatim, does NOT fork OAuth logic (spec §3.2 explicit
instruction).

```typescript
interface IGConnectPromptProps {
  onConnected?: () => void; // fired by ConnectedAccounts' new callback (§2), lets this card
                             // react without polling once the OAuth redirect round-trips back
  className?: string;
}
```

This is a *thin* co-pilot-flavored wrapper, not a rebuild of `ConnectedAccounts`. Two options
depending on Priya's call — flagged as open question §6.1:
- **(a)** Render `<ConnectedAccounts />` as-is inside a co-pilot framing card ("Link Instagram
  to get your first suggestion"), OR
- **(b)** A small standalone prompt (icon + one-line copy + a `Button` that calls
  `api.metaOAuth.authorize()` directly, same as `ConnectedAccounts.handleConnect`) so this can
  sit compactly above `HypeInboxCard` without dragging in the full settings-page account list
  UI (Facebook Page row, granted-permissions panel) that doesn't belong on the dashboard.

My default: **(b)**, mirroring only the `handleConnect` function body (same try/catch → toast
pattern on failure) — the full `ConnectedAccounts` card stays the canonical settings-page
surface; this is a slimmer dashboard nudge with its own copy.

### 1.3 `src/components/creator/copilot/BusinessAccountRequired.tsx`

**Modeled on:** no existing 1:1 analog — closest structural precedent is
`hype-inbox-card.tsx`'s Card shell + `AlertDialog`-style disclosure content. New pattern per
spec §3.3 (drop-off/explainer state), detailed in §4 below.

```typescript
interface BusinessAccountRequiredProps {
  onSkip: () => void; // "skip for now" — never blocks the rest of the dashboard
  className?: string;
}
```

### 1.4 `src/components/creator/copilot/SuggestionEmptyState.tsx`

**Modeled on:** the loading/thinking visual language of
`src/components/feature/meera/ThinkingState.tsx`, simplified — this is a static "still working
on it" card, not a live step-checklist (no per-caption steps to show the creator), so I am
NOT reusing its step-interval machinery, only its shimmer/skeleton visual weight.

```typescript
interface SuggestionEmptyStateProps {
  /** distinguishes "just linked, first batch hasn't run" vs "batch ran, zero themes matched" —
   *  copy differs; both are silent/non-alarming per spec §6 "silence" default (pending Ash/Tejas
   *  ruling — see §6.2 open question) */
  reason: 'pending_tagging' | 'no_suggestion_today';
  className?: string;
}
```

Renders: icon (Sparkles, `animate-pulse` unless `useReducedMotion()`) + "Usually ready within
a day" (for `pending_tagging`) or "No new idea today — check back tomorrow" (for
`no_suggestion_today`) — no CTA, no dismiss, this is inert.

### 1.5 `src/hooks/useDailySuggestion.ts`

**NOT MY FILE** — owned by the data-layer FE agent per the task split. I document the contract
I need from it here so both agents can build in parallel off a frozen shape:

```typescript
export interface UseDailySuggestionResult {
  suggestion: DailySuggestion | null;
  status: SuggestionStatus; // 'idle' | 'loading' | 'ready' | 'dismissed' | 'error'
  /** only meaningful when status === 'idle': did OAuth complete but return NO_BUSINESS_ACCOUNT? */
  requiresBusinessAccount: boolean;
  dismiss: (id: string) => Promise<void>;
  markActed: (id: string) => Promise<void>;
  retry: () => void;
}
```

I consume `useDailySuggestion()` from `DailySuggestionSection.tsx` (§1.6) exactly like
`useBrandProfile()` is consumed in Meera surfaces — I do not care whether it's react-query or
plain state internally, only the return shape above.

### 1.6 `src/components/creator/copilot/DailySuggestionSection.tsx` (NEW — orchestrator)

Not in the spec's explicit file list, but needed: something has to own the `status` → which
component to render mapping (§3 below) so `creator-layout.tsx` mounts ONE component, not a
five-way conditional inline in the layout. This is the thinnest possible wrapper:

```typescript
interface DailySuggestionSectionProps {
  className?: string;
}
```

Internally: calls `useDailySuggestion()`, renders the router table in §3, wires
`onConnected` (from `IGConnectPrompt`) to the hook's `retry()`. Flagged as an addition to the
file list for Priya to bless or reject (she may prefer this logic inline in
`creator-layout.tsx` instead — either is fine, I have a mild preference for the extra file so
`creator-layout.tsx`'s diff stays a two-line mount, see §2).

---

## 2. Changes to existing files (diff intent, not full code)

### 2.1 `src/components/creator/creator-layout.tsx`

- **Nav:** `navItems` array (line 64-67) gets one new entry:
  ```typescript
  { label: 'Co-pilot', href: '/creator/copilot', icon: Sparkles },
  ```
  New import: `Sparkles` from `lucide-react` (already the repo's convention icon for
  AI-adjacent surfaces per spec's own wording "Sparkles icon via `IconBadge`"). Uses the
  existing `IconBadge` + `getCreatorNavIconVariant(item.href)` machinery already in the nav
  `.map()` — no new nav-rendering code, just a new array entry.
  - **Open question for Arjun/Priya:** is co-pilot a full page (`/creator/copilot`) or purely a
    dashboard-mounted card with no dedicated route? Spec §3.2 says "mount card atop the
    creator dashboard, above `HypeInboxCard`" — that reads as dashboard-mounted, not a new
    route/page. If there's no dedicated page, the nav entry may not belong at all (Tier-1 might
    be zero new nav items, just the mounted card). I'm listing the nav-entry diff as
    conditional on that call — see §6.3.
- **Mount:** wherever `<HypeInboxCard />` (or the dashboard content it lives in — that file
  isn't `creator-layout.tsx` itself, `creator-layout.tsx` is the shell/`children` wrapper; the
  actual mount point is the dashboard PAGE component, e.g. `src/pages/creator-deals.tsx` or
  wherever `HypeInboxCard` is currently rendered). **Correction to spec wording:** spec §3.2
  says "mount card atop the creator dashboard" inside `creator-layout.tsx`, but
  `creator-layout.tsx` only renders `{children}` (line 333) — it has no knowledge of dashboard
  content. I will grep for the actual `<HypeInboxCard` render site once I start implementation
  and mount `<DailySuggestionSection />` directly above it there, not in the layout shell.
  Flagging this now so Priya's review isn't blocked on a file that doesn't actually contain the
  mount point.

### 2.2 `src/components/creator/connected-accounts.tsx`

- Add optional prop:
  ```typescript
  interface ConnectedAccountsProps {
    onConnected?: () => void;
  }
  export function ConnectedAccounts({ onConnected }: ConnectedAccountsProps) {
  ```
- The callback fires after a successful connect. Current `handleConnect` (line 38-53) does a
  **full-page redirect** (`window.location.href = authorizationUrl`) — it never returns to this
  component's JS context, so `onConnected` can't fire from inside `handleConnect` itself. It
  must fire from wherever the OAuth callback round-trip lands (the `/creator/settings/meta/
  callback` route, per the mock URL at `api.ts:2868`) and `ConnectedAccounts` would need to
  read `connectionState` on mount/focus and fire `onConnected` if it flips from disconnected →
  connected. This is a **behavior addition**, not a one-line prop add — flagged as an open
  question for the data-layer agent + Priya in §6.4, since detecting "just connected" from a
  full-page-redirect flow needs either (a) a `useEffect` comparing `getLocalConnectionState()`
  on mount against a "was this a fresh return from OAuth" signal, or (b) the callback route
  itself calling a shared callback/event. I don't want to guess the mechanism before Vikram/the
  data-layer agent confirm how `MetaCallbackResponse` gets surfaced back to app state.

### 2.3 No changes needed to `src/lib/api.ts` / `src/lib/demo-data.ts`

Those are the data-layer agent's files (new `creator.copilot.*` API methods, `accountType`
field on `MetaConnectionState`). I only consume the types they export.

---

## 3. The 5 UI states → component routing table

| `status` | Rendered by | Copy / behavior |
|---|---|---|
| `idle` (not linked yet) | `IGConnectPrompt` | "Link Instagram to get your first idea." Solid CTA → `api.metaOAuth.authorize()`. If `requiresBusinessAccount` is true instead, render `BusinessAccountRequired` (see §4 — this is a sub-branch of `idle`, not a 6th status; spec's 5-state list treats it as a variant surfaced via the `NO_BUSINESS_ACCOUNT` code, not a new enum value). |
| `loading` (linked, tagging in progress) | `SuggestionEmptyState reason="pending_tagging"` | "Usually ready within a day." No spinner-as-blank — matches repo convention (`ThinkingState`'s "never a blank spinner" comment) but simplified to static copy since there's no per-step batch progress to expose to the creator. |
| `ready` | `DailySuggestionCard` (ready body) | Full card, Dismiss + Mark-done, solid WCAG-AA CTA. |
| `dismissed` | `DailySuggestionCard` (dismissed body) | Collapsed row, "Next one tomorrow." |
| `error` / offline | Toast (via `useToast`, matches this repo's convention: API errors are toast-only, never inline blocking copy) + `SuggestionEmptyState`-style inline retry affordance (small "Retry" text-button) so the dashboard isn't left with a dead card. |

`DailySuggestionSection.tsx` (§1.6) is the single `switch (status)` site. Nothing else branches
on `status`.

---

## 4. Business-account drop-off flow — `BusinessAccountRequired.tsx`

Trigger: backend can only detect "personal IG" post-callback (spec §3.3) — so this never
appears at initial mount, only after an OAuth round-trip returns `NO_BUSINESS_ACCOUNT`.

Layout (Card, same shell as `DailySuggestionCard`'s `ready` body for visual consistency):
1. **Header:** warning-toned `IconBadge` (`variant="warning"`, per the badge component's
   existing `warning` variant — `bg-warning` / `text-warning-foreground`, NOT a raw amber
   hex) + "Your Instagram needs a quick switch."
2. **Explainer (plain language, one paragraph):** "Co-pilot needs a Business or Creator
   account linked to a Facebook Page — this is free and takes under a minute in the Instagram
   app."
3. **3-step disclosure** — collapsible or always-open (`Accordion` if the repo has one under
   `components/ui/`, else a plain numbered list — I'll check `src/components/ui/accordion.tsx`
   at build time and default to the plain list if it's not there to avoid pulling in an unused
   primitive):
   1. Open Instagram → Settings → Account type
   2. Switch to Professional Account → choose Business or Creator
   3. Connect it to a Facebook Page (Instagram prompts for this automatically)
4. **Footer CTAs:** primary solid `Button` "I've switched — reconnect" (re-runs
   `api.metaOAuth.authorize()`) + secondary `variant="ghost"` or `variant="outline"` "Skip for
   now" (`onSkip` prop — fires the parent's dismissal so `DailySuggestionSection` falls back to
   a neutral idle state and **the rest of the dashboard renders unaffected**, per spec's
   explicit "never block the rest of the dashboard on this").

This card never appears standalone on a page — it only ever occupies the same mount slot as
`IGConnectPrompt`/`DailySuggestionCard`, so "skip" just means "collapse back to nothing
special," not a navigation away from anything.

---

## 5. Design-system / a11y compliance notes

- **WCAG-AA CTAs:** every primary action (`Mark-done`, `IGConnectPrompt`'s connect button,
  `BusinessAccountRequired`'s reconnect button) uses the solid `Button` component with no
  variant override toward pale/ghost — matches the standing feedback in memory
  (`feedback_brand_cta_contrast.md`: "strong, WCAG-AA CTAs from the brand palette, not pale
  pastel"). Secondary actions (Dismiss, Skip) use `variant="outline"` or `variant="ghost"`,
  never the primary solid style, so the visual hierarchy stays unambiguous.
- **Color tokens:** per `reference_semantic_color_tokens.md` (this repo's pale-bg/strong-fg
  convention, already confirmed live in `hype-inbox-card.tsx:87` and
  `connected-accounts.tsx:85` via `text-success-foreground`) — this plan uses
  `text-success-foreground` / `text-destructive-foreground` exclusively for state text, NEVER
  bare `text-destructive` (which is the pale background token, invisible as text-color per that
  memory note).
- **Icons:** every decorative icon gets `aria-hidden="true"` (matches every icon usage already
  audited in `hype-inbox-card.tsx` and `connected-accounts.tsx`).
- **Reduced motion:** `useReducedMotion()` from `framer-motion` gates the arrival animation on
  `DailySuggestionCard` and the pulse on `SuggestionEmptyState`'s icon — bypass renders the
  final state with zero animation, not a broken half-transitioned frame.
- **Typed props:** no `any` anywhere (per this repo's ban) — every prop interface above is
  fully typed; `DailySuggestion` and `SuggestionStatus` are the two shared types both FE agents
  need, so they should probably live in a shared location (`src/types/creator-copilot.ts` or
  co-located in `DailySuggestionCard.tsx` and re-exported) — flagged as open question §6.5.
- **Tailwind only:** no inline `style={}`, no new CSS files (per this repo's Tailwind-only
  rule) — every visual variant above (warning badge, success/destructive text, accent chip) is
  an existing Tailwind/token class, not a new one I need Priya to approve.
- **Images:** N/A — this feature has no `<img>`/photo content, only icons and text.

---

## 6. Open questions for Priya + what I need from the data-layer agent

1. **`IGConnectPrompt` shape (§1.2):** full `ConnectedAccounts` reuse (option a) vs. slim
   standalone prompt (option b, my default)? Affects whether this is a 20-line wrapper or a
   ~60-line component with its own copy/layout.
2. **Zero-posts/zero-themes copy ("silence" vs. "post first" message)** — spec §6 flags this as
   an explicit **blocking** product decision (Ash + Tejas), not mine to default silently. My
   plan assumes "silence" (§1.4's `no_suggestion_today` reason renders a neutral inert card,
   no nudge to post) but `SuggestionEmptyState`'s copy for that branch is a placeholder pending
   that ruling — I will not hardcode final copy until it lands.
3. **Does co-pilot get a dedicated route/nav entry, or is it dashboard-mounted only?** (§2.1) —
   spec wording says "mount... above `HypeInboxCard`" which reads as no new page. If there's no
   page, drop the `navItems` diff entirely and the Sparkles icon is unused in nav (it might
   still appear as the card's own header icon, unrelated to nav).
4. **`ConnectedAccounts.onConnected` firing mechanism** (§2.2) — needs Vikram/data-layer input
   on how the full-page-redirect OAuth callback surfaces back into app state before I can
   finalize this diff. Not a UI decision, a data-flow one.
5. **Where do `DailySuggestion` / `SuggestionStatus` types live?** — shared between my
   component layer and the other FE agent's hook layer. Proposing
   `src/types/creator-copilot.ts` as a single import site for both agents rather than each
   agent re-declaring/duplicating the shape.
6. **From the data-layer agent specifically:** the exact `useDailySuggestion()` return shape in
   §1.5 is what I'm building against — please confirm or amend before I start, since every
   component in §1 takes its props by destructuring pieces of that shape (mostly 1:1, `status`
   and `suggestion` pass straight through from hook → `DailySuggestionSection` → child).
7. **`accountType` on `MetaConnectionState`** (spec §3.5: "extend `getLocalConnectionState`") —
   confirmed today's shape is `{ connected: boolean; scopes: string[] }`
   (`src/lib/api.ts:2856`), no `accountType` field yet. That's the data-layer agent's/Vikram's
   change to `src/lib/api.ts`, not mine — I only consume `requiresBusinessAccount` via the hook
   (§1.5), I don't read `MetaConnectionState` directly in any component in this plan.

---

## 7. Files summary (for Priya's scan)

**New:**
- `src/components/creator/copilot/DailySuggestionCard.tsx`
- `src/components/creator/copilot/IGConnectPrompt.tsx`
- `src/components/creator/copilot/BusinessAccountRequired.tsx`
- `src/components/creator/copilot/SuggestionEmptyState.tsx`
- `src/components/creator/copilot/DailySuggestionSection.tsx` (proposed addition, §1.6)
- `src/types/creator-copilot.ts` (proposed, §6.5 — pending Priya's call on shared-type location)

**Modified:**
- `src/components/creator/creator-layout.tsx` (nav entry — conditional, §6.3)
- `src/components/creator/connected-accounts.tsx` (`onConnected` prop — mechanism TBD, §2.2)
- Dashboard page that currently renders `<HypeInboxCard />` (mount site — to be located at
  implementation time, NOT `creator-layout.tsx` itself, §2.1)

**Not mine (data-layer agent):**
- `src/hooks/useDailySuggestion.ts`
- `src/lib/api.ts` (`creator.copilot.*` methods, `MetaConnectionState.accountType`)
- `API-CONTRACT.md` freeze
