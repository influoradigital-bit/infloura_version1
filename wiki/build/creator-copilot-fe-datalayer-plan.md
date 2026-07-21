# Creator AI Co-pilot Tier-1 — Frontend Data-Layer Plan

**Status:** PLANNING DRAFT — no application code yet. For Priya's review before Ananya/I start
implementation.
**Owner (this doc):** Frontend/data-layer (Sonnet 5), pairing with Ananya (components/UX — not
covered here).
**Source spec:** [`wiki/ai-review/creator-ai-copilot-tier1-build-spec.md`](../ai-review/creator-ai-copilot-tier1-build-spec.md)
(§3.5 API contract, §3.4 states, §6 QA edge cases). **Gate:** this whole feature is blocked on
Priya's money-path signoff (spec header) — this doc is the plan to execute the day that lands, not
permission to start.

**Reconciled against:** [`wiki/build/creator-copilot-fe-components-plan.md`](creator-copilot-fe-components-plan.md)
(Ananya, already posted to `SHARED_CONTEXT.md`). Her plan's §1.5/§6.5/§6.6 lay out the exact hook
shape and type names she's building components against — this doc adopts her naming
(`DailySuggestion`, `SuggestionStatus`, `src/hooks/useDailySuggestion.ts`) as canonical rather than
introducing competing names, since we're parallelizing off one contract. Where I diverge from her
draft or add detail she left open, it's called out explicitly (see §6).

Patterns mirrored, cited exactly:
- `src/hooks/trendspark/useTrendSparkNudge.ts` — react-query hook shape, mock/live split, fail-closed silence.
- `src/lib/api.ts:2862-2892` (`metaOAuth`) — OAuth client + `localStorage`-backed connection-state mirror.
- `src/lib/api.ts:3267-3293` (`trendspark`) — resource-object client pattern (`getX`/`postX`, `isLive()`/`mockOr()`).
- `src/lib/api.ts:153-164` (`ApiError`) — error shape every hook consumes.
- `src/components/creator/connected-accounts.tsx:38-53` — toast-on-catch convention for OAuth-kickoff errors.
- `src/hooks/useEscrowFund.ts` — hook that returns a plain `error: string | null` and leaves toast-firing to the component (NOT the hook) — the boundary I'm following here too.
- `src/components/DemoModeBanner.tsx` + `isApiLive()` (`src/lib/api.ts:55-56`) — demo-mode signal, unrelated to this feature's rendering but confirms the mock/live switch this client must also honor.

---

## 0. Naming / file locations

- Hook: **`src/hooks/useDailySuggestion.ts`** — spec §3.1's literal path, and what Ananya's plan
  (§1.5/§7) is already building against. (I'd considered `src/hooks/creator/` for consistency with
  `useAffiliateEarnings.ts`/`useCreatorCoupons.ts`/`useCreatorTaxIdentity.ts`/
  `useServiceInvoices.ts`, but matching the already-posted contract beats winning that
  consistency argument — not worth a rename fight over one file.)
- API client additions: new resource object in `src/lib/api.ts`, appended to the
  `export const api = { ... }` facade at `src/lib/api.ts:3299-3336`, same place `trendspark` and
  `metaOAuth` live today. **Naming isn't fully settled, flagging here and in §6's reconciliation
  summary**: every existing resource in
  this file (`trendspark`, `metaOAuth`, `creatorCoupons`, `affiliateEarnings`,
  `creatorCampaigns`...) is a **flat** top-level key, so this plan defaults to a flat
  `creatorCopilot` object (`api.creatorCopilot.getTodaySuggestion()`), not a nested
  `api.creator.copilot.*` the way Ananya's plan's file-list prose describes it (§7 of her plan:
  "`src/lib/api.ts` (`creator.copilot.*` methods...)"). That may just be shorthand on her side
  rather than a literal nesting requirement — flagging so it's confirmed once, not discovered as a
  mismatch mid-build.
- Types (`DailySuggestion`, `SuggestionStatus`, request/response DTOs) live inline in
  `src/lib/api.ts` next to the resource object — matches every existing resource
  (`TrendSparkNudge` sits directly above `trendspark`, `MetaAuthorizeResponse` etc. sit directly
  above `metaOAuth`) **and** matches how components already consume them: `useTrendSparkNudge.ts:18`
  does `import type { TrendSparkNudge } from '@/lib/api';` directly, no separate types module.
  This is my answer to Ananya's open question §6.5 (her proposal was a new
  `src/types/creator-copilot.ts`) — I'd rather follow the precedent already live in this codebase
  than introduce a second convention for exactly one feature. Not a hill to die on if Priya prefers
  her `src/types/` proposal instead.

---

## 1. `useDailySuggestion.ts` — hook

### 1.1 Signature

Adopting Ananya's plan §1.5 verbatim as the base contract (her components destructure this 1:1),
plus the two additive fields her plan itself calls for elsewhere (`error` for the toast in her §3
router table row for `error`, and an explicit `isLoadingSuggestion`-free design since `loading` is
already a `status` value, not a separate flag):

```ts
export type SuggestionStatus = 'idle' | 'loading' | 'ready' | 'dismissed' | 'error';

/** Matches DailySuggestionCard's prop type 1:1 (Ananya's plan §1.1). */
export interface DailySuggestion {
  id: string;
  theme: string;
  headline: string;
  contentIdea: string;
  expiresAt: string; // ISO
}

export interface UseDailySuggestionResult {
  suggestion: DailySuggestion | null;
  status: SuggestionStatus;
  /** Only meaningful when status === 'idle' (Ananya's plan §1.5, §3 router table row 1): true iff
   *  the last IG OAuth round-trip came back NO_BUSINESS_ACCOUNT. `idle` covers BOTH "never
   *  connected" and "connected but wrong account type" — `requiresBusinessAccount` is what
   *  distinguishes which `idle` sub-branch (IGConnectPrompt vs BusinessAccountRequired) her
   *  §3 router table renders. This is why NO_BUSINESS_ACCOUNT is a sub-branch of `idle`, not a
   *  6th status value — matches the spec's literal "5 states" framing (§3.4). */
  requiresBusinessAccount: boolean;
  /** Human-readable message for the 'error' state. Hook does NOT toast this itself (see §3) —
   *  the component's own useEffect does, per her §3 router table's "error" row. */
  error: string | null;
  /** POST .../dismiss. Takes the id explicitly (the component already has `suggestion.id` when
   *  it calls this, per her DailySuggestionCardProps.onDismiss(id) — no implicit "current
   *  suggestion" lookup inside the hook). Optimistic. */
  dismiss: (id: string) => Promise<void>;
  /** POST .../acted. Same shape as dismiss. */
  markActed: (id: string) => Promise<void>;
  /** Re-fetch after an error — the "inline retry" affordance from §3.4 / her §3 router table. */
  retry: () => void;
}

export function useDailySuggestion(): UseDailySuggestionResult
```

Note what's **not** in this hook's return shape, on purpose: no `isConnected`/`accountType`/
`connect()`/`isConnecting`. Ananya's plan §1.2 defaults `IGConnectPrompt` to option (b) — a slim
component that calls `api.metaOAuth.authorize()` **directly**, the same one-line call
`connected-accounts.tsx:41` already makes — rather than going through this hook. So the connect
action doesn't need to live in `useDailySuggestion` at all; "not connected yet" is just the `idle`
status, and the component that renders `idle` owns kicking off OAuth itself. (An earlier pass at
this hook did include a `connect()`/`isConnecting` pair — dropped once her §1.2 default made clear
the connect button lives entirely in her component tree, so a hook-level wrapper would just be a
second, unused way to do the same thing.)

### 1.2 Caching / query key

```ts
const todayKey = () => new Date().toISOString().slice(0, 10); // creator-local calendar day
export const dailySuggestionQueryKey = (day: string) =>
  ['creator', 'copilot', 'suggestion', day] as const;
```

Including the **calendar day** in the query key (not just `['creator','copilot','suggestion']`) is
the caching decision this plan is making deliberately: the per-creator/day cap (spec §2.5, §5 P1)
means a NEW day is, from the FE's point of view, simply a cache miss — no manual "reset at
midnight" timer logic needed. `staleTime: Infinity` for a given day's key (today's suggestion,
once fetched, doesn't change server-side except via dismiss/acted, which we handle via mutation
`onSuccess`, not refetch). A day boundary crossed while the tab stays open won't auto-refetch until
next mount/navigation — acceptable for a once-a-day card; flag as an open question in §5 if Priya
wants a `setInterval` day-rollover watcher instead.

```ts
const isConnected = api.metaOAuth.getLocalConnectionState().connected; // read once per render, cheap localStorage read
const { data, isLoading, isError, error: queryError, refetch } = useQuery({
  queryKey: dailySuggestionQueryKey(todayKey()),
  queryFn: () => api.creatorCopilot.getTodaySuggestion(),
  enabled: isConnected, // don't fetch until IG is linked
  staleTime: Infinity,
  retry: 1,
});
```

`isConnected` (and `requiresBusinessAccount`, sourced from the extended `MetaConnectionState`, §2.5)
are read from `api.metaOAuth.getLocalConnectionState()` INSIDE this hook — they are not part of
`UseDailySuggestionResult`'s public shape (Ananya's contract only exposes the already-collapsed
`requiresBusinessAccount` boolean, not raw connection state) — but they drive the query's `enabled`
gate and the `status` derivation below.

### 1.3 State-machine derivation

```
!isConnected                                    -> 'idle'   (requiresBusinessAccount may be true or false)
isConnected && isLoading                        -> 'loading'
isConnected && isError                          -> 'error'
data.status === 'pending_tagging'               -> 'loading'   (SuggestionEmptyState, §3.1)
data.status === 'no_suggestion_today'           -> 'dismissed' (OPEN QUESTION — see §5.1)
data.status === 'ready' && locallyDismissed      -> 'dismissed'
data.status === 'ready' && !locallyDismissed     -> 'ready'
```

Per Ananya's §3 router table row 1: `NO_BUSINESS_ACCOUNT` is a **sub-branch of `idle`**, surfaced
via `requiresBusinessAccount`, not a distinct status. Concretely: `connected === false` →
`requiresBusinessAccount` stays `false` (never attempted OAuth, or attempted and it's still
pending) — her `IGConnectPrompt` renders. `connected === false` AND the last callback returned
`NO_BUSINESS_ACCOUNT` → `requiresBusinessAccount: true` while `status` is STILL `'idle'` (a
personal-IG account never actually completes the "connected" transition per spec §3.3 — Meta OAuth
succeeds but the co-pilot can't use a personal account, so from this hook's point of view it's
still not usably connected) — her `BusinessAccountRequired` renders instead. This hook stores that
outcome via the same `sessionStorage`/`MetaConnectionState`-extension mechanism as §2.5, not a
separate flag.

`locallyDismissed`/`locallyActed` is a boolean derived from a small `sessionStorage` marker (mirrors
`metaOAuth`'s own `localStorage`-backed mirror at `src/lib/api.ts:2878-2891`,
`META_CONNECTION_KEY`/`getLocalConnectionState`/`setLocalConnectionState`) keyed on
`creator_copilot_dismissed_${todayKey()}_${suggestionId}` — so a same-day page refresh still shows
the collapsed row without depending on the backend to exclude dismissed suggestions from
`GET .../today` (open question in §4/§5 on whether it should).

`acted` has no distinct slot in the 5-state enum (spec §3.4 defines exactly 5). This plan collapses
`markActed()` into the same `'dismissed'` bucket as `dismiss()` — both stop showing the actionable
buttons and render the "next one tomorrow" collapsed row. If Ananya's card needs a visually
different acted-vs-dismissed treatment, that's still representable (the hook keeps the reason
around internally, see `CreatorSuggestionInteraction` in §1.4), it just isn't a 6th top-level
`status` value. **Flagging as an open question for Priya** — see §5.2.

### 1.4 Mutations

Signatures match Ananya's `DailySuggestionCardProps.onDismiss(id)`/`onMarkActed(id)` (her §1.1) —
the component passes `suggestion.id` back in, the hook doesn't look up "the current suggestion"
internally:

```ts
type CreatorSuggestionInteraction = 'dismissed' | 'acted';

const interactionMutation = useMutation({
  mutationFn: ({ id, kind }: { id: string; kind: CreatorSuggestionInteraction }) =>
    kind === 'dismissed'
      ? api.creatorCopilot.dismissSuggestion(id)
      : api.creatorCopilot.markSuggestionActed(id),
  onMutate: async ({ id, kind }) => {
    // optimistic: flip the local flag immediately, card collapses without waiting on the network
    setSessionDismissed(todayKey(), id, kind);
  },
  onError: (_err, { id }) => {
    // roll back the optimistic flag; component sees 'ready' again + hook's `error` is set so the
    // component can toast "Couldn't save that — try again."
    clearSessionDismissed(todayKey(), id);
  },
});

const dismiss = (id: string) => interactionMutation.mutateAsync({ id, kind: 'dismissed' });
const markActed = (id: string) => interactionMutation.mutateAsync({ id, kind: 'acted' });
```

(`Promise<void>` in the public signature → `mutateAsync`, not `mutate` — her component types both
callbacks as `(id: string) => void | Promise<void>` and shows a local button-spinner sub-state
while it's in flight (her §1.1: "Local button-level `'idle' | 'submitting'` sub-state per action"),
which only works cleanly if the returned promise actually resolves/rejects.)

No `queryClient.setQueryData`/invalidation on success is strictly required (the session-storage
flag already drives the derivation), but the plan should still call
`queryClient.invalidateQueries({ queryKey: dailySuggestionQueryKey(todayKey()) })` in `onSettled` as
a belt-and-suspenders resync, mirroring the invalidation-on-mutation-settle pattern used elsewhere
in the codebase's react-query hooks (not shown verbatim in `useTrendSparkNudge.ts`, which uses
direct `setQueryData` instead — either is acceptable, calling it out so Priya can pick one
convention rather than the two hooks disagreeing).

### 1.5 IG connect — deliberately NOT in this hook

Per §1.1's note: Ananya's `IGConnectPrompt` (her plan §1.2, default option b) calls
`api.metaOAuth.authorize()` directly, the same one-liner `connected-accounts.tsx:41` already runs,
with its own try/catch → toast on failure (mirroring `connected-accounts.tsx:45-52`). This hook
doesn't wrap that call — see §2.4 for the still-open question of whether that client call itself
needs to change (Option A/B, spec §2.5 vs §3.2 conflict).

---

## 2. API client additions (`src/lib/api.ts`)

### 2.1 Types matching spec §3.5 exactly

```ts
/** Mirrors the `{id, theme, headline, contentIdea, expiresAt}` suggestion object, spec §3.5 —
 *  same shape as Ananya's `DailySuggestion` (her plan §1.1), re-exported from here per §0's
 *  answer to her open question §6.5 (types live in api.ts, components import from '@/lib/api'). */
export interface DailySuggestion {
  id: string;
  theme: string;
  headline: string;
  contentIdea: string;
  expiresAt: string; // ISO 8601
}

/** Mirrors GET .../suggestion/today's `status` field, spec §3.5. NOTE: this is the WIRE status
 *  from the backend, distinct from the UI-facing `SuggestionStatus` union (idle/loading/ready/
 *  dismissed/error) that the hook derives from it — see §1.3's derivation table. Don't confuse
 *  the two `status` fields; they live at different layers. */
export type CreatorCopilotWireStatus = 'pending_tagging' | 'ready' | 'no_suggestion_today';

export interface CreatorSuggestionTodayResponse {
  suggestion: DailySuggestion | null;
  status: CreatorCopilotWireStatus;
}
```

### 2.2 Client methods

```ts
export const creatorCopilot = {
  /**
   * GET /creator/copilot/suggestion/today (spec §3.5 path; ⚠️ §2.5 backend section instead
   * writes `GET /api/creator/suggestion` — PATH MISMATCH, see §4/§5.3, needs Vikram to confirm
   * before this is wired for real). Never throws for the "nothing to say" case — that's the
   * `no_suggestion_today` status value, not a 204/null like TrendSpark's nudge endpoint.
   */
  getTodaySuggestion: (): Promise<CreatorSuggestionTodayResponse> =>
    isLive()
      ? http.request<CreatorSuggestionTodayResponse>('GET', '/creator/copilot/suggestion/today', { role: 'creator' })
      : mockOr<CreatorSuggestionTodayResponse>(MOCK_CREATOR_SUGGESTION),

  /** POST /creator/copilot/suggestion/:id/dismiss — stamps `dismissed_at` (creator_nudge_log). */
  dismissSuggestion: (id: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/creator/copilot/suggestion/${id}/dismiss`, { role: 'creator' })
      : mockOr(undefined),

  /** POST /creator/copilot/suggestion/:id/acted — stamps `acted_at` (flywheel logging, spec §7). */
  markSuggestionActed: (id: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/creator/copilot/suggestion/${id}/acted`, { role: 'creator' })
      : mockOr(undefined),
};

const MOCK_CREATOR_SUGGESTION: CreatorSuggestionTodayResponse = {
  status: 'ready',
  suggestion: {
    id: 'cc_mock_1',
    theme: 'skincare + winter',
    headline: 'Your skincare + winter niche is trending',
    contentIdea: 'A 3-beat reel: morning routine cold-open, ingredient close-up, "why winter skin needs this" voiceover.',
    expiresAt: new Date(Date.now() + 24 * 3600 * 1000).toISOString(),
  },
};
```

Both `role: 'creator'` (this is a creator-only surface, uses `creator_token`, matches how
`metaOAuth` and every other creator-scoped call already pass `role: 'creator'` — e.g.
`api.metaOAuth.authorize()` at `src/lib/api.ts:2864-2870`) and the `isLive()`/`mockOr()` split are
copied verbatim from `trendspark` (`src/lib/api.ts:3267-3293`) so this new resource looks identical
to its sibling in the same file.

Append `creatorCopilot,` to the facade object at `src/lib/api.ts:3299-3336`, next to `trendspark,`.

### 2.3 Auth/authz note (not this doc's call, flagging for Kabir/Vikram alignment)

Spec §5 P0 requires a new `creator` principal type in `service_token.py ENDPOINT_SCOPES`. That's
backend/AI-service, not FE — but the client-side implication is: if a `creator_token` is missing or
expired, `http.request` already does the 401→refresh→retry dance (`HttpClient.fetchWithAuthRetry`,
`src/lib/api.ts:246-263`) per-role, so no new FE plumbing is needed there. Noting it here only so
nobody assumes the FE needs bespoke 401 handling for this feature — it doesn't.

### 2.4 IG connect — the one genuinely open client-design question

Spec §2.5 (backend, Vikram) lists **`POST /api/creator/ig/connect`** as a new REST endpoint that
"routes to the flipped OAuth path." Spec §3.2 (frontend, Ananya) says the opposite: *"reuse
`handleConnect` as-is... `api.metaOAuth.authorize()`... **Do NOT fork the OAuth logic**."*

Those two instructions are in tension. Two ways this resolves, and they produce different API
clients:

- **Option A (no new FE method):** `POST /api/creator/ig/connect` is Vikram's internal name for
  the same OAuth kickoff the existing `metaOAuth.authorize()`/`metaOAuth.callback()` already call —
  the backend flips token ownership (`workspaceId = null`) server-side, transparent to the FE. Both
  my plan (§1.5) and Ananya's (`IGConnectPrompt` calling `api.metaOAuth.authorize()` directly, her
  §1.2 default option b) assume this — zero new client surface.
- **Option B:** it's a genuinely separate endpoint FE must call instead of/in addition to today's
  `metaOAuth.authorize()`, in which case I'd add `api.creatorCopilot.connectIg(): Promise<MetaAuthorizeResponse>`
  hitting `/creator/ig/connect` and `IGConnectPrompt.tsx` would call that instead of
  `connected-accounts.tsx`'s existing `handleConnect`.

**This is a real open question for Priya/Vikram (§5.3)** — I've built §1.5 and this section around
Option A per the FE section's explicit "do not fork" instruction, but it directly contradicts what
the backend section lists as a new route. Whoever resolves it should update this doc before code
starts.

### 2.5 `MetaConnectionState.accountType` extension (spec §3.5's 4th line)

```ts
// current (src/lib/api.ts:2856):
export interface MetaConnectionState { connected: boolean; scopes: string[] }

// extend to:
export interface MetaConnectionState {
  connected: boolean;
  scopes: string[];
  /** null = unknown (never resolved a callback yet). Populated post-OAuth-callback only —
   *  spec §3.3: "Backend can't detect personal IG pre-authorize, only post-callback." */
  accountType: 'personal' | 'business' | null;
}
```

`getLocalConnectionState`/`setLocalConnectionState` (`src/lib/api.ts:2878-2891`) both need the
extra field threaded through; `setLocalConnectionState` gains an `accountType` param. `callback()`
(`src/lib/api.ts:2872-2876`) needs its `MetaCallbackResponse` to either carry `accountType`
directly, or the caller infers "personal account" from an `ApiError.code === 'NO_BUSINESS_ACCOUNT'`
thrown by that same call — **which of the two the backend actually does is unconfirmed, see §5.4.**
`useDailySuggestion` collapses whichever shape wins into the single `requiresBusinessAccount`
boolean on `UseDailySuggestionResult` (§1.1) — `accountType`/`MetaConnectionState` itself stays an
internal implementation detail of the hook, per Ananya's plan §6.7 ("I only consume
`requiresBusinessAccount` via the hook, I don't read `MetaConnectionState` directly in any
component"). So once §5.4 is settled, only this extension + the hook's internal read need to
change — `useDailySuggestion`'s public contract and every component built against it stay stable.

---

## 3. Error handling: toast vs inline vs `NO_BUSINESS_ACCOUNT`

Repo convention (confirmed via `connected-accounts.tsx:45-52` and `useEscrowFund.ts`'s
component-facing `error: string | null`): **API/network errors are toast-only, field validation is
inline.** This feature has no form/field validation, so the split here is narrower:

| Case | Surface | Why |
|---|---|---|
| `GET .../suggestion/today` fails (network, 5xx, unexpected `ApiError`) | `status: 'error'` from the hook + component fires a **toast** (not the hook — see below) + renders an **inline retry button** that calls `hook.retry()` | Matches spec §3.4 literally: "toast + inline retry." The toast is the notification; "inline" describes the retry *control*, not inline error text — there is no inline error copy here. |
| `dismiss()`/`markActed()` mutation fails | Optimistic flag rolled back (`onError`, §1.4) + `error` set → component toasts "Couldn't save that, try again" | Same toast-only convention; the card silently reverts to `ready` rather than getting stuck in a half-dismissed state. |
| `NO_BUSINESS_ACCOUNT` (spec §3.3) | **Not a toast, not the `error` field.** `status` stays `'idle'` with `requiresBusinessAccount: true` (§1.3) → Ananya's dedicated `BusinessAccountRequired` component renders. | Explicitly called out in the spec as a UX branch, not a failure — "Never block the rest of the dashboard on this." Toasting it would misrepresent a normal, expected drop-off as an error. |
| IG connect kickoff fails (`api.metaOAuth.authorize()` throws) | Toast, fired by the calling component (`IGConnectPrompt`/`connected-accounts.tsx`) exactly like `connected-accounts.tsx:45-52` today | Lives entirely in Ananya's component tree per §1.5 — this hook has no code path here at all. |

**Hook vs component boundary for toasting:** following `useEscrowFund.ts`'s precedent, the hook
itself never calls `useToast()`. It only exposes a stable `error: string | null`. The reason this
matters mechanically under React Query v5 (confirmed installed version:
`@tanstack/react-query@^5.100.10`, `package.json:47`) is that `useQuery`'s `onError` callback was
**removed** in v5 — there's no hook-level place to fire a toast as a query-option callback anymore
even if we wanted to. The correct v5-idiomatic approach, and the one this plan uses, is:
1. The hook computes `error` via `useMemo` keyed on the underlying react-query `error` object
   reference (not reconstructed fresh every render), so it only changes identity on a genuinely new
   failure.
2. The **component** (Ananya's) runs `useEffect(() => { if (error) toast({...}) }, [error])` — one
   toast per distinct failure, not one per re-render/poll.

**Retry logic:** `retry: 1` on the query (one silent automatic retry, matching
`useTrendSparkNudge.ts:43`'s `retry: 1`), plus the explicit `retry()` → `refetch()` escape hatch
for the human-triggered "inline retry" button once both automatic attempts are exhausted and
`status === 'error'`. No exponential backoff needed — this is a once-a-day, human-paced surface,
not a live feed.

---

## 4. Staying in sync with the (unfrozen) API contract

Fields this hook depends on, straight from spec §3.5 — **these need to actually be frozen in an
`API-CONTRACT.md` before Ananya starts on fixtures, per the spec's own instruction ("Parallelizable
off a frozen `API-CONTRACT.md`," §9)**. Right now they are not frozen; they're inconsistent between
sections of the same spec doc (§4.1 below).

| Field | Depended on by | Notes |
|---|---|---|
| `suggestion.id` | `dismiss()`/`markActed()` URL param | Required for the mutation URLs. |
| `suggestion.theme`, `.headline`, `.contentIdea` | display only, opaque to the hook | Hook doesn't parse/validate these — Ananya's card renders them as-is. |
| `suggestion.expiresAt` | not consumed by the hook itself | Spec doesn't say what "expired" means for the once-a-day cap — flagged in §5.5. |
| `status` (`pending_tagging`/`ready`/`no_suggestion_today`) | primary driver of the state-machine (§1.3) | This is the single most load-bearing field in the whole contract — any 4th value added later needs the derivation table in §1.3 updated in lockstep. |
| IG `accountType` | `requiresBusinessAccount` derivation | Not yet on `MetaConnectionState` — see §2.5. |

### 4.1 Contract inconsistency already found (block until resolved)

Spec §2.5 (Vikram's backend section) and §3.5 (the FE-frozen block) **disagree on every single
path**:

| Endpoint | §2.5 (backend) | §3.5 (frozen-for-FE) |
|---|---|---|
| Get today's suggestion | `GET /api/creator/suggestion` | `GET /api/creator/copilot/suggestion/today` |
| Dismiss | `POST /api/creator/suggestion/{id}/dismiss` | `POST /api/creator/copilot/suggestion/:id/dismiss` |
| Acted | `POST /api/creator/suggestion/{id}/acted` | `POST /api/creator/copilot/suggestion/:id/acted` |

This plan's client (§2.2) codes the §3.5 paths since that's the block explicitly labeled "API
contract Ananya needs (freeze in `API-CONTRACT.md` so FE parallelizes)" — but **whoever writes the
actual controller needs to pick one and someone needs to edit the spec doc so it stops
contradicting itself.** This is the single biggest risk to FE/BE parallelization landing cleanly;
recommend Priya or Vikram resolve it in the same pass as the Option A/B OAuth question (§2.4) before
either team writes real code against these paths.

---

## 5. Open questions for Priya + backend (Vikram)

1. **`no_suggestion_today` UX** — the spec's own §6 "Blocks SHIP" list says this is an unresolved
   product decision ("zero-posts / zero-themes UX = 'silence' vs 'post first' message," Ash + Tejas
   to align). This doc's §1.3 tentatively maps it to the `'dismissed'` (silent, collapsed) bucket
   as the closest fit among the 5 existing states, but if the product decision lands on "show a
   'post something first' message" instead, that's arguably a **6th state**, not a variant of
   `dismissed`. Needs the product call before the derivation table in §1.3 is final.
2. **Does `acted` deserve its own `status` value?** Right now `dismiss()` and `markActed()` both
   collapse to `status: 'dismissed'` in the hook (§1.3) because the spec's 5-state enum has no slot
   for it. If Ananya's card wants different collapsed copy/visuals for "you dismissed it" vs "you
   marked it done," the hook can still expose which one happened (`CreatorSuggestionInteraction`
   internally), but the public `status` union may need a 6th member. Confirm before locking the
   type.
3. **OAuth connect: Option A vs B (§2.4)** — is `POST /api/creator/ig/connect` a new client-facing
   endpoint, or backend-internal renaming of the existing `metaOAuth.authorize()`/`callback()` flow
   now creator-owned? This changes whether `api.creatorCopilot` needs a `connectIg()` method at all.
4. **How exactly does `NO_BUSINESS_ACCOUNT` arrive?** As an `ApiError.code` thrown from
   `metaOAuth.callback()` (HTTP error path), or as a `200` success response with an
   `accountType: 'personal'` field the FE must branch on (no error thrown at all)? These need
   different handling in `MetaConnectionState`/`callback()` (§2.5) — I've assumed the latter is more
   likely (spec says "the co-pilot API returns a distinct... code," which reads more like a
   response field than an HTTP error), but it's a guess.
5. **Does `GET .../today` reflect `dismissed_at`/`acted_at` at all**, or does the same suggestion
   keep coming back as `status: 'ready'` all day once dismissed (relying entirely on the FE's
   `sessionStorage` marker from §1.3 to hide it)? If the backend doesn't filter, a second tab / a
   cleared session shows the "ready" card again after a dismiss — is that acceptable for Tier-1, or
   does the GET response need to start reflecting server-side dismissal state?
6. **`expiresAt` semantics** — is this purely a display "valid until" hint, or does something
   client-side need to actively treat an expired-but-not-yet-refetched suggestion as stale (e.g.
   force a refetch rather than trust `staleTime: Infinity` for the rest of the day)? Currently
   unused by the hook; flagging in case that's wrong.
7. **Day-rollover while the tab stays open** — §1.2 accepts that a suggestion won't refresh until
   next mount/navigation if the calendar day ticks over mid-session. Acceptable for a once-a-day
   card, or does Priya want a lightweight `setInterval` day-boundary watcher that invalidates the
   query key proactively?

---

## 6. Reconciliation with Ananya's components plan — what's locked vs. still open

**Locked (this doc adopts her contract as-is):**
- Hook path `src/hooks/useDailySuggestion.ts`, type names `DailySuggestion`/`SuggestionStatus`.
- `UseDailySuggestionResult` shape (§1.1) — `requiresBusinessAccount: boolean` as an `idle`
  sub-branch rather than a 6th status, `dismiss(id)`/`markActed(id)` taking explicit ids returning
  `Promise<void>`.
- `IGConnectPrompt` calls `api.metaOAuth.authorize()` directly (her §1.2 default) — this hook has
  zero connect-flow surface (§1.5).
- Her §3 router table's toast+inline-retry treatment of the `error` row (§3 of this doc).

**Still open, and answered here where I could, flagged for Priya where I couldn't:**
- Her §6.5 (shared type location) → I recommend `src/lib/api.ts` over a new `src/types/` module,
  citing `useTrendSparkNudge.ts:18`'s existing precedent (§0). Not binding on her — if Priya prefers
  `src/types/creator-copilot.ts`, the only change on my side is an import path, the shapes are
  identical either way.
- Her §6.4 (`ConnectedAccounts.onConnected` firing mechanism for a full-page-redirect OAuth flow) —
  **I don't have a clean answer either.** The redirect leaves and returns to
  `/creator/settings/meta/callback` (the mock URL at `src/lib/api.ts:2868`), a different mount than
  wherever `DailySuggestionSection` lives, so "fire a callback" doesn't cross that boundary for
  free. My best suggestion: the callback route calls `api.metaOAuth.setLocalConnectionState(...)`
  (already the existing mechanism, `src/lib/api.ts:2889-2891`) then `navigate()`s back to the
  dashboard; `useDailySuggestion`'s `enabled: isConnected` gate (§1.2) picks up the now-connected
  state on that next mount automatically — no explicit `onConnected` callback needed at all, the
  hook's own `enabled` flag re-evaluates. If that's sufficient, her `ConnectedAccounts.onConnected`
  prop (her §2.2) may not be needed for the co-pilot card specifically, only for surfaces that need
  to react without a remount. Genuinely a design call, not just an open question — bringing it to
  Priya rather than deciding unilaterally since it touches Ananya's component too.
- My own new findings not in her plan: the §4.1 path mismatch (§2.5/§3.5 disagreeing on every
  endpoint path) and the flat-vs-nested API namespace question (§0) — neither surfaced in her plan
  since she doesn't write the client, only consumes it.

---

## 7. Explicitly not in this doc

Component structure, visuals, animations, a11y (Ananya's — spec §3.1/§3.6). Backend service/route
implementation, migrations, AI prompt (Vikram/Meera/Ash — spec §2/§4/§7). This is data-layer only:
hook, client, types, error/cache wiring.
