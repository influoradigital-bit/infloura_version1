# Kabir Red-Team Review — Brand P1-#2 "Deals (40% → live)" — counter-offer wiring

**Scope:** `src/pages/brand-chat.tsx` live deal list + `src/components/brand/deal-room/brand-deal-counter-modal.tsx`
(new) + `POST /deals/{id}/counter` (`DealService.counter()`/`doCounter()`, `influora-api/src/main/java/com/influora/service/DealService.java:202-244,312-329`).

## 1. Server-side scoping — `DealService.counter()`/`doCounter()`

- `counter()` (DealService.java:202) calls `requireOwnedCollaboration(principal, dealId)` (line 331) before
  anything else. For a BRAND principal this resolves to `brandContext.requireBrandWorkspace(principal)` (workspace
  derived entirely from the JWT principal, never a client param — confirmed in `BrandContextService.java:34-49`)
  then `collaborationRepository.findByIdAndWorkspaceId(dealId, workspace.getId())`
  (`CollaborationRepository.java:38-42`), which is a JPQL join `Collaboration.campaignId IN (SELECT Campaign.id
  WHERE Campaign.workspaceId = :workspaceId)`. A brand cannot reach another brand's deal — the query returns empty
  and the caller gets `DEAL_NOT_FOUND` 404, not a leak.
- For a CREATOR principal, `requireOwnedCollaboration` routes to `requireCreatorCollaboration` (line 345) →
  `creatorContext.requireCreator(principal)` + `collaborationRepository.findByIdAndCreatorId(dealId,
  principal.getUserId())` — scoped to the authenticated creator's own id, not a path/body param.
- `doCounter()` (line 312) itself does not re-derive scope — it trusts the already-validated `collaboration`
  object passed in from `counter()`. No secondary IDOR surface.
- **Idempotency:** key defaults to `resolveIdempotencyKey(idempotencyKey, "deal-counter:" + dealId + ":" +
  body.amount())` (line 229) when the caller doesn't send an `Idempotency-Key` header. `IdempotencyService
  .executeOnce` (`IdempotencyService.java:80-124`) inserts the key row first in its own transaction and relies on
  the DB's `UNIQUE(idempotency_key)` constraint — not app logic — to arbitrate concurrent double-submits; a FAILED
  row can be reclaimed by exactly one retrying caller via a status-guarded `UPDATE`. This is solid.

**Verdict on item 1: PASS.** No cross-tenant/cross-creator access path found; idempotency is DB-arbitrated, not
best-effort.

## 2. `CounterRequest` validation (`DealDtos.java:54-57`)

```java
public record CounterRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @Size(max = 2000) String message,
        List<DeliverableSlot> deliverables) {}
```

- `DealController.counter()` (`DealController.java:85-93`) is annotated `@Valid @RequestBody CounterRequest body`
  — bean validation runs, so a negative/zero amount 400s before reaching the service.
- `DealService.counter()` additionally calls `validateProposalAmount(campaign, body.amount())` (line 222, impl
  line 393) which re-checks `> 0` and bounds the amount to `[campaign.budgetMin, campaign.budgetMax]` when those
  are set on the campaign — an absurd amount (e.g. far above the campaign's stated budget) is rejected with
  `AMOUNT_EXCEEDS_BUDGET` 400. (Note: if a campaign has no `budgetMax` configured, only the `>0` floor applies —
  not a vulnerability since a brand can only inflate its own offer to its own creator, not move anyone else's
  money, but flagging as a data-hygiene observation, not a finding.)
- `message` persists through `persistProposalMessage()` → `TextSanitizer.sanitizePlainText(body.message())`
  (DealService.java:436) — same HTML-tag-stripping sanitizer used everywhere else in this codebase
  (`TextSanitizer.java`), confirmed by direct read, not assumed.

**Verdict on item 2: PASS.**

## 3. Frontend modal hygiene (`brand-deal-counter-modal.tsx`)

- Submit button (`onClick={() => void handleSubmit()}`) is `disabled={isSubmitting}` (line 147), and the parent
  (`brand-chat.tsx:764-778`, `handleCounterSubmit`) sets `isSubmittingCounter(true)` before awaiting
  `api.deals.counter(...)`, so a second click while the request is in flight is blocked once the state commits.
  Client-side validation (`parsed <= 0` / non-finite) is explicitly UX-only — the real floor is server-side
  `@DecimalMin`/`validateProposalAmount` above, so this is not a substitute, it's a nicety, correctly scoped.
- No client-computed total is trusted anywhere — the modal sends the raw user-entered `amount` and `message`;
  `currentAmount` is only ever displayed as read-only reference text, never re-derived into the payload.
- **Low-severity / non-blocking observation:** `api.deals.counter()` (`src/lib/api.ts:1163-1172`) does **not** pass
  a client-generated `idempotencyKey` in its `http.request(...)` call, unlike `wallet.withdraw`
  (`api.ts:2046-2053`, `` `withdraw-${Date.now()}` ``), `wallet.topUp` (mandatory param), and `messages.send`
  (`api.ts:1223-1229`, `` `${dealId}-${Date.now()}` ``) — all of which explicitly generate one. This is **not
  exploitable**: the server's deterministic fallback key (`deal-counter:{dealId}:{amount}`, item 1 above) still
  gets DB-arbitrated dedup for same-amount double-submits. It's an inconsistency with this codebase's own
  established convention for money-adjacent mutations, not a hole — recommend Ananya/Vikram align it by passing
  a generated key (e.g. `` `deal-counter-${dealId}-${Date.now()}` ``) from the modal for consistency and clearer
  audit trails, low priority.

**Verdict on item 3: PASS, with one low-severity consistency note (not a vulnerability).**

## 4. State machine — `Collaboration.canCounter()` (`Collaboration.java:172-174`)

```java
public boolean canCounter() {
    return canAccept();
}
public boolean canAccept() {
    return status == INVITED || status == APPLIED || status == SHORTLISTED || status == IN_NEGOTIATION;
}
```

`canCounter()` mirrors `canAccept()` exactly, so a deal in any terminal/downstream state — `TERMS_AGREED`,
`CONTRACT_PENDING`, `CONTRACTED`, `IN_PROGRESS`, `REVIEW_PENDING`, `REVISION_REQUESTED`, `COMPLETED`,
`CANCELLED`, `DISPUTED` — correctly 409s with `DEAL_NOT_NEGOTIABLE` (DealService.java:206-211) rather than
silently re-negotiating an already-accepted/closed deal. Frontend also gates the "Counter" button client-side to
`!isAccepted && !isCountered` proposal cards (`brand-chat.tsx:1307-1332`) — defense-in-depth only, the real
boundary is the server check above.

**Verdict on item 4: PASS.**

## 5. Accept — independent spot-check of Vikram's finding

`DealService.accept()` (DealService.java:166-183) calls `creatorContext.requireCreator(principal)` (line 167)
as its first line — `CreatorContextService.requireCreator()` (`CreatorContextService.java:21-26`) throws
`WRONG_USER_TYPE` 403 for any principal whose `UserType != CREATOR`. A brand-role JWT hitting `POST
/deals/{id}/accept` is rejected before `requireCreatorCollaboration`/`doAccept` ever run. Even hypothetically past
that gate, `doAccept()` (line 299-310) hardcodes `toDealResponse(collaboration, principal, UserType.CREATOR)` and
the system message "Creator accepted the proposal" — genuinely creator-perspective, not adaptable to a brand
caller without a real backend change. **I agree with Vikram: Accept is correctly left unwired this cycle.** The
frontend's honest "not available yet, send a counter offer instead" note (`brand-chat.tsx:1307-1315`, shown only
when `isApiLive()`) is the right call — no disagreement, no additional action needed.

## Overall Verdict: **PASS WITH FINDINGS**

0 Critical / 0 High / 0 Medium. One **LOW**, non-blocking, non-exploitable consistency note (item 3: missing
client-generated `Idempotency-Key` header on `api.deals.counter`, mitigated by the server's deterministic
fallback key). No regression, no IDOR, no injection, no unbounded-amount path, no terminal-state re-negotiation
possible. Safe to ship as-is; the idempotency-key consistency note can be picked up whenever `api.deals.counter`
next gets touched.
