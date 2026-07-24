# Ananya — Nav alignment (creator↔brand) + M-1c budget-null crash fix

**Date:** 2026-07-23
**Author:** Ananya (Frontend)
**Status:** READY FOR QA (Kavya)
**Branch:** `feat/creator-taxonomy-keyword-patch`

## Scope

1. **Task 1 — Creator sidebar → grouped**, mirroring `brand-layout.tsx`'s
   `navGroups` (Main/Manage) pattern, exposing the 4 remaining orphaned
   creator pages (`/creator/reviews`, `/creator/disputes`,
   `/creator/coupons`, `/creator/affiliate`).
2. **Task 2 — Brand account menu**: added an internal `Help & Support` item
   (`/brand/help`) to both the desktop and mobile account dropdowns.
3. **Add-on (M-1c, P1, folded in mid-task per coordinator)** — fixed a
   frontend crash on `/brand/campaigns` when a campaign has no `budget`
   object (Meera-created drafts legitimately have none until the wizard's
   budget step), plus the accompanying `₹NaN`/`₹null` display bugs on the
   Meera "Campaign created" card and the Pipeline card.

## Task 1 — Creator sidebar grouping

`src/components/creator/creator-layout.tsx`:

- Replaced the flat `navItems: { label, href, icon }[]` array with a
  `navGroups: CreatorNavGroup[]` structure (`CreatorNavItem`/`CreatorNavGroup`
  interfaces added, typed identically to `brand-layout.tsx`'s
  `BrandNavItem`/`BrandNavGroup`).
- **Main** group (unchanged 6 items): Home, Deals, Campaigns, Co-pilot,
  Analytics, Wallet.
- **Manage** group (new):

  | Label | Href | Icon |
  |---|---|---|
  | Reviews | `/creator/reviews` | `Star` |
  | Disputes | `/creator/disputes` | `AlertTriangle` |
  | Coupons | `/creator/coupons` | `Ticket` |
  | Affiliate | `/creator/affiliate` | `TrendingUp` |

  (`Link` was the other icon option offered for Affiliate; used `TrendingUp`
  to match the growth/earnings connotation and avoid ambiguity with the
  existing `Globe`/`Public Page` icon.)

- Desktop sidebar `<nav>` now maps over `navGroups`, rendering a
  `text-[11px] font-semibold uppercase tracking-wide text-muted-foreground/70`
  section-label `<p>` per group (byte-for-byte the same classes
  `brand-layout.tsx` uses), then each group's items in the existing
  `Tooltip`/`IconBadge`/button structure — unchanged per-item markup.
- Mobile `Sheet` nav updated the same way, and additionally switched from a
  raw `<Icon className="h-[18px] w-[18px]" />` to the shared `IconBadge`
  component (`active`/`variant` wired through `getCreatorNavIconVariant`) —
  this matches how `brand-layout.tsx`'s mobile sheet already renders icons,
  so both sidebars are now visually consistent in both breakpoints.
- Preserved as-is: the Deals unread-count `Badge`, the `isActive()` aliasing
  of `/creator/deals` over `/inbox`, `/active`, `/chat`, the avatar/account
  dropdown (Profile, Public Page, Settings, Help & Support, Log out) in both
  desktop and mobile, and the logout confirmation dialog.

`src/lib/icon-theme.ts`:

- Added 4 entries to `creatorNavIconVariant` for the new routes so they get a
  themed `IconBadge` variant instead of falling back to `muted`:
  `/creator/reviews` → `approved`, `/creator/disputes` → `disputed`,
  `/creator/coupons` → `outreach`, `/creator/affiliate` → `info`. Mirrors how
  `brandNavIconVariant` treats `/brand/reviews` (`approved`) and
  `/brand/disputes` (`disputed`).

## Task 2 — Brand Help & Support

`src/components/brand/brand-layout.tsx`:

- Desktop account dropdown (`~line 282-289`): the existing `Help & Support`
  item previously called `window.open('https://help.influora.com', '_blank')`
  — changed to `handleNavigate('/brand/help')`, routing to the real internal
  page (route already existed in `App.tsx:309`, was just unlinked from the
  desktop menu's actual destination).
- Mobile account dropdown (`~line 417-428`): previously had **only**
  Settings + Log out — added the same `Help & Support` item
  (`HelpCircle` icon, already imported) calling `handleNavigate('/brand/help')`,
  placed directly after Settings, matching the creator layout's ordering
  (Profile → Public Page → Settings → Help → Log out).

## Add-on (M-1c) — `/brand/campaigns` null-budget crash

**Root cause:** Meera-created draft campaigns have no `budget` object until
a later step in the campaign wizard. Several call sites assumed `budget` was
always present and dereferenced `.max`/`.min` unconditionally, crashing the
whole campaigns list to the error boundary, and rendering `₹NaN`/`₹null`
elsewhere budget/dealValue was missing.

Fixes (frontend-only, no backend/type-contract changes — the `Campaign`/`Deal`
types still declare `budget`/`dealValue` as required; this is runtime
defensiveness against that known contract divergence, not a contract change):

- `src/components/brand/campaigns/campaigns-list.tsx:344` — budget sort
  comparator: `(b.budget?.max ?? 0) - (a.budget?.max ?? 0)` (was
  `b.budget.max - a.budget.max`, crashed on the first budget-less row).
- `src/components/brand/campaigns/campaigns-list.tsx:359` — `stats.totalBudget`
  reduce: `sum + (c.budget?.max ?? 0)` (was `sum + c.budget.max`).
- `src/components/brand/campaigns/campaigns-list.tsx:362-367` — `formatBudget`
  signature widened to `(min?: number, max?: number)`, returns
  `'No budget set'` when either is `null`/`undefined` instead of computing
  `NaN`/throwing.
- `src/components/brand/campaigns/campaigns-list.tsx:715,767` — grid-card and
  list-row call sites pass `campaign.budget?.min`/`campaign.budget?.max`.
- `src/lib/utils.ts:12-24` — the **shared** `formatINR` (used by 42 files)
  widened to `formatINR(amount?: number | null): string`, returns
  `'No budget set'` for `null`/`undefined`/`NaN` instead of letting
  `Intl.NumberFormat` render `₹NaN`. This is the actual fix for the Meera
  "Campaign created" tool-result card
  (`src/components/feature/meera/ToolResultRenderer.tsx:156`,
  `formatINR(serverBudget)`) — no changes needed at that call site or any of
  the other 40, since they were all previously passing real numbers and are
  unaffected by the widened signature.
- `src/pages/brand-pipeline.tsx:285-291` — the **separate**, file-local
  `formatINR` (Pipeline board/list/timeline cards, not the shared
  `lib/utils.ts` one) widened the same way; this was the source of the
  literal `"₹null"` string (it fell through `amount >= 100000` /
  `amount >= 1000` checks — both `false` for `null` — to the
  `` `₹${amount}` `` fallback).

Note: `stats.active`/`stats.draft`/etc. and the rest of the campaigns list
(status badges, platform chips, progress bar, collaborator count, deadline)
don't touch `budget` and were already null-safe.

## Verification

- `npx tsc --noEmit` → **0 errors**, clean.
- `npm run build` (`vite build` + `postbuild` prerender) → **✅ PASS**, built
  in 58-99s across runs (variance is machine load, not a regression). 4759
  modules transformed. `postbuild` prerender: **16/16 marketing routes
  snapshotted**. Only pre-existing warnings: duplicate `baseUrl` key in root
  `tsconfig.json` (cosmetic, unrelated) and the standard >500kB chunk-size
  advisory (`index-*.js` ~2.65MB / `PerformanceMonitor-*.js` ~892kB) — both
  present before this patch, not introduced by it.
- No live re-verification performed per directive (nav wiring only; M-1c fix
  will be live re-verified post-deploy per the coordinator's note).

## Files changed (Task 1/2 + first M-1c pass)

- `src/components/creator/creator-layout.tsx`
- `src/components/brand/brand-layout.tsx`
- `src/lib/icon-theme.ts`
- `src/components/brand/campaigns/campaigns-list.tsx`
- `src/lib/utils.ts`
- `src/pages/brand-pipeline.tsx`

Not touched (per directive): backend, `src/lib/api.ts`,
`src/pages/creator-copilot.tsx`, `src/pages/creator-disputes.tsx`.

---

## Follow-up (same day) — M-1c round 2: sparse-draft crash on card/detail paths

**Trigger:** live QA found the round-1 fix (list-reduce/sort only) incomplete
— the campaign **card** on `/brand/campaigns` silently dropped a budget-less
Meera draft, and opening that draft's **detail** page
(`/brand/campaigns/:id`) crashed outright ("Cannot read properties of
undefined (reading 'min')"). Root cause unchanged: Meera-created drafts are
**sparse** — no `budget`, and potentially no `platforms`/`timeline`/
`maxCollaborators` either, until later wizard steps. Frontend-only fix,
backend/type-contracts untouched (drafts are correctly sparse; the bug was
unguarded consumption).

### `src/pages/brand-campaign-detail.tsx`

- `DetailCampaignView.budget` type (`~L203`) widened to optional
  (`budget?: {...}`).
- `buildLiveCampaignView` (`~L236-246`): budget now built conditionally —
  `campaign.budget ? {...} : undefined` — instead of unconditionally
  dereferencing `campaign.budget.min/max/currency`.
- `L623` — `budgetProgress` guarded: `campaign?.budget ? (...) : 0` (was
  `campaign ? (...) : 0`, still crashed via `campaign.budget.spent`).
- `L850-852` — "Budget Used" quick-stat tile: `campaign.budget ? formatCurrency(...) : 'No budget set'`,
  sub-label `'set in campaign wizard'` when absent.
- `L1670-1697` — "Budget Breakdown" sidebar card: wrapped the whole
  breakdown table in `campaign.budget ? (...) : (<p>No budget set yet — add one from the campaign wizard.</p>)`.
- `L1581-1584` — Settlement Summary (Completed-tab, mock-only path,
  `!liveApi && mockCompleted` gated so not reachable by a live sparse draft,
  but widening `budget` to optional surfaced 4 new `TS18048` errors here) —
  guarded with `campaign.budget?.spent ?? 0` to keep `tsc` clean.
- Confirmed all other `campaign.budget.*` derefs in the file were already
  inside one of the above guards (verified via grep post-fix — zero
  unguarded occurrences remain).

**Detail page now opens for a budget-less draft** — "Budget Used" tile
reads "No budget set", the Budget Breakdown card shows a placeholder
sentence instead of computing `NaN`, rest of the page (title, timeline,
platforms, bids/collaborators tabs, target audience, requirements) renders
as before since those fields aren't sparse on this draft.

### `src/components/brand/campaigns/campaigns-list.tsx` — card render (root cause of the dropped card)

- `formatDate` (`~L372-380`) widened to `(date?: Date)`, returns
  `'No deadline'` for `undefined` instead of feeding `undefined` into
  `new Date()` → `Intl.DateTimeFormat.format()` (which throws `RangeError:
  Invalid time value` on an Invalid Date — this was the actual crash/drop
  source, not a silent `NaN`).
- Grid card (`~L698-734`):
  - platforms: `(campaign.platforms ?? []).slice(0, 3)` and
    `(campaign.platforms?.length ?? 0) > 3` (was `campaign.platforms.slice/.length`
    unconditional — throws on a platform-less draft).
  - creators count: `campaign.maxCollaborators ?? 0` (was raw, rendered
    literal `"undefined"` for a draft with no `maxCollaborators` — cosmetic,
    now shows `0`).
  - deadline: `formatDate(campaign.timeline?.endDate)` (was
    `campaign.timeline.endDate` unconditional — throws on a timeline-less
    draft).
- List-view row (`~L775-782`): identical `maxCollaborators ?? 0` and
  `campaign.timeline?.endDate` guards, matching the grid card.
- Confirmed via grep post-fix: zero remaining unguarded `.timeline.` or
  `.platforms.`/`.platforms[` derefs in this file.

**Draft card now renders in both the All and Drafts tabs** — shows "No
budget set" / "No deadline" / "0/{maxCollaborators}" or "0" placeholders
instead of throwing during the `.map()` render, which is what was silently
removing it from the grid.

### `src/pages/creator-campaign-detail.tsx`, `src/components/creator/CreatorBrowseCampaignCard.tsx` — already safe

Coordinator's grep flagged `creator-campaign-detail.tsx:301,427` and
`CreatorBrowseCampaignCard.tsx:82` (text-matches `campaign.budget.min/max`,
regex-based, not AST-aware). Verified by reading each: **all three are
already wrapped in `{campaign.budget && (...)}`** — false positives, no
crash exists there, no change made. Re-grepped both files post-check to
confirm no other unguarded `.budget` derefs exist.

### `src/components/brand/campaigns/campaign-form.tsx` — edit-wizard prefill

- `~L181-183` (the `campaigns.get()` → form-prefill `useEffect`): now
  `budgetMin: c.budget?.min ?? initialFormData.budgetMin`, same pattern for
  `budgetMax`/`currency` (was `c.budget.min/max/currency` unconditional —
  crashed when opening a budget-less draft in the edit wizard, which is
  exactly the flow a brand needs to *set* that budget). Falls back to the
  same defaults (`₹5,000–₹25,000`, `INR`) a brand-new campaign starts with,
  so the wizard opens pre-filled and editable rather than crashing.
- Grepped the rest of the file for `.budget.` — no other unguarded derefs.

### Verification (round 2)

- `npx tsc --noEmit` → 0 errors (required the extra `L1581-1584` guard in
  `brand-campaign-detail.tsx` after widening `DetailCampaignView.budget` —
  TS correctly flagged those 4 lines as `TS18048` even though they're
  behind a runtime-safe `!liveApi && mockCompleted` gate, since that
  narrowing doesn't reach through to `campaign.budget` for TS).
- `npm run build` → clean, 28s, postbuild prerender 16/16 routes.

## Files changed (cumulative, both rounds)

- `src/components/creator/creator-layout.tsx`
- `src/components/brand/brand-layout.tsx`
- `src/lib/icon-theme.ts`
- `src/components/brand/campaigns/campaigns-list.tsx`
- `src/lib/utils.ts`
- `src/pages/brand-pipeline.tsx`
- `src/pages/brand-campaign-detail.tsx`
- `src/components/brand/campaigns/campaign-form.tsx`

Read but unchanged (already guarded, false positives in coordinator's grep):
`src/pages/creator-campaign-detail.tsx`,
`src/components/creator/CreatorBrowseCampaignCard.tsx`.

Not touched (per directive): backend, `src/lib/api.ts`,
`src/pages/creator-copilot.tsx`, `src/pages/creator-disputes.tsx`.
