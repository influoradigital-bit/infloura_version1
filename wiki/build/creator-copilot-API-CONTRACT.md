# Creator AI Co-pilot Tier-1 — API Contract

**STATUS: FROZEN v1** (2026-07-21) — Priya ruling, BE-services track greenlit (R1). This is one of
Priya's 7 pre-conditions to unblock code; Ananya's two FE tracks wire against this surface as-is.
Any further change requires a new frozen version, not a silent edit.

**Author:** Vikram (Backend) · **Reconciles:**
[creator-copilot-be-services-plan.md](creator-copilot-be-services-plan.md) (this doc's BE source),
[creator-copilot-fe-datalayer-plan.md](creator-copilot-fe-datalayer-plan.md) (§1-§2, the canonical
FE types/hook contract — adopted verbatim below, not re-derived), and
[creator-copilot-fe-components-plan.md](creator-copilot-fe-components-plan.md) (component-level
consumer). Source spec: [creator-ai-copilot-tier1-build-spec.md](../ai-review/creator-ai-copilot-tier1-build-spec.md) §2.5/§3.5.

**What this freeze resolves** (previously-open questions across the two FE docs, now closed):
- FE datalayer plan §2.4/§5.3 (OAuth Option A vs B) → **Option A, locked** (§4 below).
- FE datalayer plan §4.1 (path mismatch between spec §2.5 and §3.5) → **§3.5's paths win**, verbatim
  (§1 below).
- FE datalayer plan §6 / components plan §6.4 (`onConnected` firing mechanism) → **ruled: no
  callback prop** (§5 below).
- FE datalayer plan §0 (flat vs. nested `api.*` namespace) → flat `api.creatorCopilot.*` confirmed
  (matches every other resource in `src/lib/api.ts`, including `metaOAuth`/`trendspark`).

Still open post-freeze (not blocking code start, tracked separately): §6.

---

## 0. Path scheme

All Creator Co-pilot routes live under `/creator/copilot/*`, Spring `@RequestMapping` base path
(no `/api/v1` in the Java annotation — that prefix comes from
`server.servlet.context-path: /api/v1` in `application.yml:71`, applied globally to every
controller, same as the existing `/brand/trendspark`, `/me/creator-profile`, `/meta/oauth`
controllers). Externally, every path below is `/api/v1/creator/copilot/*`. `API_BASE_URL`
(`src/lib/api.ts:48-49`) already includes `/api/v1`, so FE client code below passes the
`/creator/copilot/...` suffix only — no double-prefixing.

---

## 1. Endpoints

Exactly three routes. No `POST /creator/ig/connect` (see §4 — that line item in the build spec is
resolved as "the existing OAuth flow, now creator-owned," not a new path).

### 1.1 `GET /api/v1/creator/copilot/suggestion/today`

| | |
|---|---|
| **Auth** | Creator principal only. Server resolves identity via `CreatorContextService.requireCreatorProfile(principal)` (`service/CreatorContextService.java:47`) — never a param. FE sends `role: 'creator'` (→ `creator_token`); 401 handling is generic (`HttpClient.fetchWithAuthRetry`), no bespoke FE plumbing needed. |
| **Request** | No body, no query params. |
| **Success (200)** | `{ suggestion: DailySuggestion \| null, status: CreatorCopilotWireStatus }` (§2 for exact shapes) |
| **Error codes** | Standard envelope failure only (`ApiError` with generic `code`/`message`, e.g. `500` on unexpected server error). No feature-specific error code on this endpoint — "nothing to say today" is a **200 success** with `status: 'pending_tagging' | 'no_suggestion_today'` and `suggestion: null`, never a 4xx/204. |
| **Idempotency** | Same-day repeat calls return the identical row (`ready`) with no new AI spend — this is the mechanism behind the per-creator/day cap (BE plan §1 step 2, §5). |
| **Dismissed/acted suggestions still return here** | **Ruling, closes FE plan §5.5:** the backend does **not** filter dismissed/acted suggestions out of this GET. `dismissed_at`/`acted_at` are pure audit stamps (BE plan §2.3) — the same suggestion keeps coming back as `status: 'ready'` with the same `suggestion` object for the rest of the day. The FE's `sessionStorage` marker (FE datalayer plan §1.3) is the ONLY thing that collapses the card to the `dismissed` UI state client-side; a second tab or a cleared session will show the `ready` card again post-dismiss. This is accepted for Tier-1, not a bug. |

### 1.2 `POST /api/v1/creator/copilot/suggestion/{id}/dismiss`

| | |
|---|---|
| **Auth** | Same as §1.1. `{id}` is the suggestion id only — ownership enforced server-side via `CreatorNudgeLogRepository.findByIdAndCreatorProfileId(id, creatorProfileId)` (BE plan §1), never trusted from the path alone. |
| **Request** | No body. |
| **Success (200)** | Empty data (`ApiResponse.ok(null)` → envelope `{success:true, data:null}` → `http.request<void>` resolves `undefined`). |
| **Error codes** | `SUGGESTION_NOT_FOUND` (404) — id doesn't exist or doesn't belong to the caller (resolve-then-check failure, same 404 either way, per IDOR discipline — never distinguish "not yours" from "doesn't exist" in the response). |
| **Effect** | Stamps `dismissed_at` (idempotent — a second call on an already-dismissed row is a no-op, mirrors `NudgeLog.markClicked`'s guard). |

### 1.3 `POST /api/v1/creator/copilot/suggestion/{id}/acted`

Identical shape to §1.2 (`markActed`/`acted_at` instead of `dismiss`/`dismissed_at`).

---

## 2. Shared types (frozen — FE and BE must match these exactly)

Canonical source: **FE datalayer plan §2.1**, adopted as-is. BE Java DTO field names below use
plain camelCase (default Jackson serialization, no `@JsonProperty` needed) so the wire JSON matches
the TS interface byte-for-byte — this is a browser-facing contract, unlike the Spring→influora-ai
internal calls which use snake_case.

```ts
// FE (src/lib/api.ts) — TypeScript, canonical per creator-copilot-fe-datalayer-plan.md §2.1
export interface DailySuggestion {
  id: string;
  theme: string;
  headline: string;
  contentIdea: string;
  expiresAt: string; // ISO 8601
}

export type CreatorCopilotWireStatus = 'pending_tagging' | 'ready' | 'no_suggestion_today';

export interface CreatorSuggestionTodayResponse {
  suggestion: DailySuggestion | null;
  status: CreatorCopilotWireStatus;
}
```

```java
// BE (web/dto/creatorcopilot/CreatorCopilotDtos.java) — Java record, wire-identical
public record SuggestionDto(
    String id, String theme, String headline, String contentIdea, String expiresAt) {}

public record SuggestionTodayResponse(SuggestionDto suggestion, String status) {}
// `status` is a plain String carrying exactly "pending_tagging" | "ready" | "no_suggestion_today"
// (CreatorNudgeService.SuggestionResult's status field, BE plan §1, already uses these literal
// strings internally — no enum/string mapping layer needed).
```

**Envelope note:** every response above is the *unwrapped* shape. On the wire it's actually
`{ success: true, data: <above> }` (`ApiEnvelope<T>`, `src/lib/api.ts:112-117`;
`com.influora.common.ApiResponse` server-side) — `http.request<T>()` (`src/lib/api.ts:265-318`)
unwraps this automatically and returns `T` directly, or throws `ApiError(code, message, status)` on
`!success`/non-2xx. Neither FE nor BE code below needs to think about the envelope explicitly; it's
existing plumbing, called out here only so the shapes in this doc read correctly as "what the hook
actually receives."

**`expiresAt` semantics:** end of the creator's current UTC day (ties to the daily-cap window,
BE plan §5). Not actively consumed by the hook (FE datalayer plan §1.4/§5.6) — display-only for
Tier-1.

---

## 3. FE client (frozen shape, matches `creator-copilot-fe-datalayer-plan.md` §2.2 verbatim)

```ts
export const creatorCopilot = {
  getTodaySuggestion: (): Promise<CreatorSuggestionTodayResponse> =>
    isLive()
      ? http.request<CreatorSuggestionTodayResponse>('GET', '/creator/copilot/suggestion/today', { role: 'creator' })
      : mockOr<CreatorSuggestionTodayResponse>(MOCK_CREATOR_SUGGESTION),

  dismissSuggestion: (id: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/creator/copilot/suggestion/${id}/dismiss`, { role: 'creator' })
      : mockOr(undefined),

  markSuggestionActed: (id: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/creator/copilot/suggestion/${id}/acted`, { role: 'creator' })
      : mockOr(undefined),
};
```
Appended to the `api` facade (`src/lib/api.ts:3299-3336`) as flat `creatorCopilot,` — same
convention as `trendspark`/`metaOAuth`, not a nested `api.creator.copilot.*`. No `connectIg()`
method (§4).

---

## 4. IG linking — reuses the existing OAuth flow, NOT a new route (Priya ruling, Option A locked)

The build spec §2.5 line "`POST /api/creator/ig/connect` → routes to the flipped OAuth path" is
**not** a new endpoint. It describes the existing `/meta/oauth/authorize` +
`/meta/oauth/callback` flow (`web/MetaOAuthController.java`), fixed to actually work for creators
(`creator-copilot-be-services-plan.md` §0/§3 — that path was silently broken: it already tried to
store a creator-owned token with `workspaceId=null` against a `NOT NULL` column). Ananya's
component plan already assumes this (§1.2: `IGConnectPrompt` calls `api.metaOAuth.authorize()`
directly, "Do NOT fork the OAuth logic") and the FE datalayer plan builds its hook the same way
(§1.5: zero connect-flow surface inside `useDailySuggestion`). **No `api.creatorCopilot.connectIg()`
method exists or is planned.**

### 4.1 Existing endpoints (unchanged paths, fixed backend behavior)
- `GET /api/v1/meta/oauth/authorize` → `MetaAuthorizeResponse { authorizationUrl: string; state: string }`
- `GET /api/v1/meta/oauth/callback?code=&state=` → `MetaCallbackResponse` (extended, below)

### 4.2 `NO_BUSINESS_ACCOUNT` — delivery mechanism (ruling, closes FE datalayer plan §5.4)

**Ruling: a 200 success response with a field, not a thrown `ApiError`.** The OAuth code exchange
itself genuinely succeeded (Meta issued a valid token) — only the *linked account type* is wrong
for co-pilot purposes. Per spec §3.3 ("never block the rest of the dashboard on this"), this is an
expected branch, not a failure — toasting it via the `ApiError` path would misrepresent a normal
drop-off as an error, contradicting the repo's own toast-is-for-errors convention
(`connected-accounts.tsx:45-52`, `useEscrowFund.ts`).

```ts
export interface MetaCallbackResponse {
  connected: boolean;
  grantedScopes: string[];
  /** NEW. 'personal' means OAuth succeeded but the linked IG is not a Business/Creator account —
   *  co-pilot cannot use it. `connected` is `false` in this case (spec §3.3: a personal account
   *  never completes a USABLE connection). Absent/undefined on the ordinary success path. */
  accountType?: 'personal' | 'business';
}

export interface MetaConnectionState {
  connected: boolean;
  scopes: string[];
  /** null = unknown (never resolved a callback yet). Persisted alongside `connected`/`scopes` in
   *  the same localStorage mirror (`META_CONNECTION_KEY`, src/lib/api.ts:2858). */
  accountType: 'personal' | 'business' | null;
}
```
Server-side: `CreatorMetaOAuthService.connect` (BE plan §3.5/§3.8) resolves the linked IG business
account via `FacebookPageClient.resolveConnectedInstagram`; `null`/no linked account →
`MetaCallbackResponse(connected=false, grantedScopes=[...], accountType="personal")`; a resolved
account → `accountType="business"`, persists `ig_business_account_id` (closing the same historical
bug class as `V65__meta_oauth_ig_business_account_id.sql`'s H-9 fix).

FE: `useDailySuggestion` collapses `accountType` into the single `requiresBusinessAccount: boolean`
on its public return shape (FE datalayer plan §1.1/§1.3) — components never read
`MetaConnectionState`/`accountType` directly (per Ananya's plan §6.7), so this extension is fully
internal to the hook.

---

## 5. `onConnected` resolution (Priya ruling — closes FE datalayer plan §6 / components plan §6.4)

**Ruling: no `onConnected` callback prop.** Mechanism:

1. The OAuth callback route (`/creator/settings/meta/callback`, the mock URL already referenced at
   `src/lib/api.ts:2868`) calls `metaOAuth.callback(code, state)`, then writes the result via
   `setLocalConnectionState(...)` (existing mechanism, `src/lib/api.ts:2889-2891`, now carrying
   `accountType` per §4.2), then `navigate()`s back to the dashboard.
2. `useDailySuggestion` computes `isConnected` fresh from `api.metaOAuth.getLocalConnectionState()`
   on every render (FE datalayer plan §1.2) and passes it as `useQuery`'s `enabled` option. The
   dashboard remount after `navigate()` re-evaluates this from scratch and picks up the
   newly-connected state with zero extra plumbing.
3. **`ConnectedAccounts.onConnected` (components plan §2.2) is therefore NOT required for the
   co-pilot card** and should be dropped from that component's diff unless some other consumer
   (outside this feature) genuinely needs a live-without-remount signal. The full-page-redirect
   OAuth flow makes a remount unavoidable anyway, so there's no case within this feature where a
   callback prop would fire without a remount already having happened first.

---

## 6. Still open (not blocking code start — tracked, not frozen)

These are real product/behavior questions from the two FE plans that this freeze does **not**
resolve, because they weren't part of the four items Priya asked me to rule on. Do not treat
silence on these as an implicit ruling:

1. **`no_suggestion_today` UX** ("silence" vs. "post something first" copy) — explicit Ash+Tejas
   blocker per the build spec §6/§8. Unaffected by this freeze; the wire `status` value
   (`no_suggestion_today`) is frozen, the *copy* shown for it is not.
2. **Does `acted` deserve a 6th `status` value** distinct from `dismissed`? Currently both collapse
   to the same UI bucket client-side (FE datalayer plan §1.3/§5.2). The wire contract in §2 above
   is unaffected either way — this is purely a UI-state-machine question, not a wire-shape one.
3. **Day-rollover while a tab stays open** (FE datalayer plan §5.7) — cosmetic, not a contract
   question.

If any of these resolve to a wire-shape change (e.g. a 6th `status` value), that requires a new
frozen version of this doc, not a silent amendment.
