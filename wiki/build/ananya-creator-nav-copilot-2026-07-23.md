# Ananya — creator sidebar nav (2→6) + /creator/copilot page — 2026-07-23

## Files changed
- `src/components/creator/creator-layout.tsx` — `navItems` (line ~72) now Home,
  Deals, Campaigns, Co-pilot, Analytics, Wallet. New icon imports: `Home`,
  `Megaphone`, `Sparkles`, `BarChart3`. Rewrote the stale "3-item navigation"
  doc comment (line ~64). Mobile Sheet nav (line ~301) already maps over the
  same `navItems` array — verified, no separate edit needed. Deals unread
  badge condition (`item.label === 'Deals'`) untouched, still Deals-only.
- `src/pages/creator-copilot.tsx` — new page, route `/creator/copilot`.
- `src/components/creator/copilot/CopilotPreviewCard.tsx` — new, static
  pre-connect preview card.
- `src/App.tsx` — imports `CreatorCopilotPage`; registers
  `/creator/copilot` inside `CreatorProtectedRoute`, same pattern as
  `/creator/deals`.
- `src/pages/creator-deals.tsx` — removed the embedded `DailySuggestionSection`
  (and its import); replaced with a slim entry-point `Card` ("Get today's
  content idea from Co-pilot") that links to `/creator/copilot`. No fetch on
  Deals anymore for this — the query only runs on the Co-pilot page now.
- `src/components/creator/copilot/DailySuggestionSection.tsx` — updated its
  doc comment (was stale, said it mounts on creator-deals.tsx) to point at
  the new home.

## What the Co-pilot page does
`/creator/copilot` wraps `<CreatorLayout>`, heading "Co-pilot" / "Your AI
content partner", and hosts the existing `DailySuggestionSection` +
`useDailySuggestion` unchanged — no forked data-layer logic.

### Pre-connect preview
`useDailySuggestion`'s `enabled: isConnected` means it never fetches pre-IG-
connect, and `isConnected`/`accountType` are deliberately not surfaced
outside the hook (see its own doc comment) — the only externally visible
signal is `status`. `status === 'idle'` covers both "never connected" and
"connected but wrong account type" (both collapse to `idle` per the hook's
`requiresBusinessAccount` sub-flag).

The page reads a second `useDailySuggestion()` instance (deduped by
react-query's queryKey, not a second network call) just for `status`. When
`status === 'idle'`, it renders `CopilotPreviewCard` — a static, clearly
labelled ("Preview" badge + footer line "Preview — connect Instagram for
ideas personalised to your audience") representative example idea — above
`DailySuggestionSection`. `DailySuggestionSection` itself still renders the
real `IGConnectPrompt`/`BusinessAccountRequired` for `idle` right below it,
so the Connect Instagram CTA is untouched, not duplicated, not replaced.

- **Pre-connect**: preview card (static example) + Connect Instagram CTA.
- **Post-connect, no live wiring changed**: identical to today —
  loading/ready/dismissed/error states all come from the same
  `DailySuggestionSection`; preview card disappears once `status` leaves
  `idle`.

No live/no-auth preview API call was wired — out of scope per the task, and
the static example is explicitly labelled as a preview, not presented as a
real generated idea.

## Verification
- `npx tsc --noEmit` — clean, no errors.
- `npm run build` — succeeded (vite build + prerender, 16/16 marketing
  routes). Pre-existing unrelated warning: duplicate `baseUrl` key in the
  monorepo-root `tsconfig.json` (line 20/21) — not touched by this change.

## Not touched (per task scope)
Backend files, `src/lib/api.ts`, `src/pages/creator-disputes.tsx`, and brand
nav (`src/components/brand/brand-layout.tsx`) — brand nav is a separate step
pending a live check.
